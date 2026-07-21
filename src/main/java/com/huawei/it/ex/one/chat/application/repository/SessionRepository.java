package com.huawei.it.ex.one.chat.application.repository;

import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.domain.ChatSessionNumberPage;
import com.huawei.it.ex.one.chat.domain.ChatSessionPage;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 聊天会话事实源端口。
 *
 * <p>所有查询都应通过 tenantId/userId 进行归属过滤；无 owner 条件的方法仅供内部已校验场景使用。</p>
 */
public interface SessionRepository {
    /**
     * 按会话 ID 查询会话。
     *
     * @param sessionId 前端聊天会话标识。
     * @return 会话快照；不存在时为空。
     */
    Optional<ChatSession> findById(String sessionId);

    /**
     * 按租户、用户和会话 ID 查询当前用户拥有的会话。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return 当前用户拥有的会话；不存在或不属于当前用户时为空。
     */
    Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId);

    /**
     * 查询当前用户全部会话。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @return 当前用户会话列表。
     */
    List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId);

    /**
     * 分页查询当前用户会话。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param cursor 上一页返回的游标。
     * @param limit 最大返回条数。
     * @return 会话分页结果。
     */
    ChatSessionPage pageByTenantIdAndUserId(String tenantId, String userId, String cursor, int limit);

    /**
     * 按可选应用标识分页查询当前用户会话。
     */
    default ChatSessionPage pageByTenantIdAndUserId(
            String tenantId, String userId, String appId, String cursor, int limit) {
        if (appId == null || appId.isBlank()) {
            return pageByTenantIdAndUserId(tenantId, userId, cursor, limit);
        }
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        List<ChatSession> items = findByTenantIdAndUserId(tenantId, userId).stream()
                .filter(session -> !"DELETED".equals(session.status()))
                .filter(session -> appId.trim().equals(session.appId()))
                .sorted(Comparator.comparing(ChatSession::updatedAt).reversed()
                        .thenComparing(ChatSession::id, Comparator.reverseOrder()))
                .limit(pageSize)
                .toList();
        return new ChatSessionPage(items, null);
    }

    /**
     * 基于页码分页查询当前用户会话。
     *
     * <p>默认实现仅服务测试和非数据库替代仓储，生产数据库实现会使用 count + offset SQL。
     * 查询语义必须与游标分页保持一致：当前 owner、排除 DELETED、按 updatedAt/id 倒序。</p>
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param curPage 当前页码，从 1 开始；非法值按 1 处理。
     * @param pageSize 每页条数；非法值按 20 处理，上限 100。
     * @return 页码分页结果。
     */
    default ChatSessionNumberPage pageNumberByTenantIdAndUserId(
            String tenantId, String userId, int curPage, int pageSize) {
        return pageNumberFromSessions(findByTenantIdAndUserId(tenantId, userId), null, curPage, pageSize);
    }

    /**
     * 按可选应用标识执行页码分页查询。
     */
    default ChatSessionNumberPage pageNumberByTenantIdAndUserId(
            String tenantId, String userId, String appId, int curPage, int pageSize) {
        return pageNumberFromSessions(findByTenantIdAndUserId(tenantId, userId), appId, curPage, pageSize);
    }

    private static ChatSessionNumberPage pageNumberFromSessions(
            List<ChatSession> sessions, String appId, int curPage, int pageSize) {
        int normalizedPage = Math.max(1, curPage);
        int normalizedSize = Math.max(1, Math.min(pageSize <= 0 ? 20 : pageSize, 100));
        List<ChatSession> all = sessions.stream()
                .filter(session -> !"DELETED".equals(session.status()))
                .filter(session -> appId == null || appId.isBlank() || appId.trim().equals(session.appId()))
                .sorted(Comparator.comparing(ChatSession::updatedAt).reversed()
                        .thenComparing(ChatSession::id, Comparator.reverseOrder()))
                .toList();
        int totalRows = all.size();
        long requestedOffset = (long) (normalizedPage - 1) * normalizedSize;
        int fromIndex = requestedOffset >= totalRows ? totalRows : (int) requestedOffset;
        int toIndex = Math.min(fromIndex + normalizedSize, totalRows);
        long totalPages = totalRows == 0 ? 0 : (long) Math.ceil((double) totalRows / normalizedSize);
        return new ChatSessionNumberPage(all.subList(fromIndex, toIndex), normalizedPage, normalizedSize,
                totalRows, totalPages);
    }

    /**
     * 保存或更新会话快照。
     *
     * @param session 会话快照。
     * @return 已保存的会话。
     */
    ChatSession save(ChatSession session);

    /**
     * 推进会话最新可见 assistant 消息水位，不改变会话列表排序时间。
     */
    default void advanceLatestMessageSeq(String tenantId, String userId, String sessionId, long messageSeq) {
        ChatSession session = findByTenantIdAndUserIdAndId(tenantId, userId, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或不属于当前用户: " + sessionId));
        save(session.withMessageWatermarks(
                Math.max(session.latestMessageSeq(), messageSeq), session.lastReadSeq()));
    }

    /**
     * 将当前用户的已读水位原子推进到实际展示位置，不能越过服务端最新消息水位。
     */
    default ChatSession markReadThrough(String tenantId, String userId, String sessionId, long readThroughSeq) {
        ChatSession session = findByTenantIdAndUserIdAndId(tenantId, userId, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或不属于当前用户: " + sessionId));
        long nextReadSeq = Math.max(session.lastReadSeq(),
                Math.min(Math.max(0L, readThroughSeq), session.latestMessageSeq()));
        return save(session.withMessageWatermarks(session.latestMessageSeq(), nextReadSeq));
    }

    /**
     * 在当前事务内锁定会话消息树写入水位，但不递增节点序号。
     *
     * <p>调用方必须已经开启事务。该锁用于统一同一会话的 run admission 与终态消息写入顺序；
     * 非数据库实现只需完成归属和存在性校验。</p>
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     */
    default void lockForMessageMutation(String tenantId, String userId, String sessionId) {
        findByTenantIdAndUserIdAndId(tenantId, userId, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或不属于当前用户: " + sessionId));
    }

    /**
     * 为会话生成下一个消息树节点序号。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @return 会话内新的 nodeOrder。
     */
    default long nextNodeOrder(String tenantId, String userId, String sessionId) {
        return findByTenantIdAndUserIdAndId(tenantId, userId, sessionId)
                .map(session -> session.lastNodeOrder() + 1)
                .orElse(1L);
    }

    /**
     * 更新会话当前 active path 叶子。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param leafMessageId 新的叶子消息 ID。
     */
    default void updateCurrentLeaf(String tenantId, String userId, String sessionId, String leafMessageId) {
        findByTenantIdAndUserIdAndId(tenantId, userId, sessionId)
                .map(session -> new ChatSession(session.id(), session.tenantId(), session.userId(), session.title(),
                        session.status(), session.channel(), session.appId(), session.appName(),
                        leafMessageId, session.rootSessionId(),
                        session.branchSourceSessionId(), session.branchSourceMessageId(), session.lastNodeOrder(),
                        session.latestMessageSeq(), session.lastReadSeq(), session.metadataJson(),
                        session.createdAt(), java.time.Instant.now()))
                .ifPresent(this::save);
    }
}
