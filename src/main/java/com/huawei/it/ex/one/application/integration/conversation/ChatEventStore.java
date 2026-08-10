package com.huawei.it.ex.one.application.integration.conversation;

import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import java.util.List;

/**
 * 聊天事件事实源端口。
 *
 * <p>事件表既服务审计，也服务前端断线恢复。写入时必须返回带持久化 seq 的事件，
 * 这样实时输出、恢复输出和数据库中的顺序完全一致。</p>
 */
public interface ChatEventStore {
    /**
     * 追加事件并返回带持久化序号的事件。
     *
     * @param event 原始领域事件。
     * @return 带持久化 seq 的事件。
     */
    ChatEvent append(ChatEvent event);

    /**
     * 在 execution 写入权保护下追加事件，并返回带持久化序号的事件。
     *
     * <p>这是高并发流式输出的生产写入路径。实现层必须在同一条数据库写入语句内校验
     * run/session/tenant/user 归属、业务 run 状态、execution owner 和 fencing token。
     * 条件不满足时应抛出 {@link ChatEventAppendRejectedException}，调用方据此停止当前后台流。</p>
     *
     * @param event 原始领域事件。
     * @param claim 后台执行流启动时获得的 execution 写入权声明。
     * @return 带持久化 seq 的事件。
     */
    ChatEvent appendWithExecutionGuard(ChatEvent event, RunExecutionClaim claim);

    /**
     * 在同一个 execution 写入权保护下批量追加同一 run 的普通运行事件。
     *
     * <p>默认实现保持兼容，逐条调用单事件方法；生产数据库实现应覆盖该方法，使用一个短事务
     * 完成一次 run 栅栏、一次序号分配和一次批量写入。</p>
     *
     * @param events 同一 run、同一 session 的有序事件。
     * @param claim 当前 execution 写入权声明。
     * @return 按输入顺序返回带持久化 seq 的事件。
     */
    default List<ChatEvent> appendBatchWithExecutionGuard(List<ChatEvent> events, RunExecutionClaim claim) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream().map(event -> appendWithExecutionGuard(event, claim)).toList();
    }

    /**
     * 在 execution 写入权保护下为仅实时事件分配全局序号，但不写入事件事实表。
     *
     * <p>默认实现故意失败关闭，避免新仓储实现把留存策略要求不落库的业务事件误写入事实源。
     * 生产实现必须只执行 owner/fencing 校验和数据库 sequence 分配。</p>
     *
     * @param events 同一 run、同一 session 的有序实时事件。
     * @param claim 当前 execution 写入权声明。
     * @return 按输入顺序返回带全局 sequence 的非持久化事件。
     */
    default List<ChatEvent> sequenceLiveBatchWithExecutionGuard(
            List<ChatEvent> events,
            RunExecutionClaim claim) {
        throw new UnsupportedOperationException(
                "ChatEventStore does not support live-only sequence allocation");
    }

    /**
     * 按用户归属查询指定会话在某个序号之后的事件。
     *
     * <p>事件补发接口必须直接在事实源查询中携带 owner 条件，不能只依赖上层先校验 session
     * 归属后再按裸 sessionId 查询；这样即使事件表存在异常数据，也不会跨租户或跨用户补发。</p>
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @param afterSeq 已处理的最大事件序号。
     * @return 大于 afterSeq 的会话事件，按 seq 正序排列。
     */
    List<ChatEvent> findByOwnerAndSessionAfterSeq(String tenantId, String userId, String sessionId, long afterSeq);

    /**
     * 按用户归属查询指定 run 在某个序号之后的事件。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId run 所属前端聊天会话标识。
     * @param runId run 标识。
     * @param afterSeq 已处理的最大事件序号。
     * @return 大于 afterSeq 的 run 事件，按 seq 正序排列。
     */
    List<ChatEvent> findByOwnerAndRunAfterSeq(String tenantId, String userId, String sessionId, String runId, long afterSeq);

    /**
     * 按seq游标分页读取指定run事件，供stop在owner失联时进行有界历史投影。
     */
    default List<ChatEvent> findPageByOwnerAndRunAfterSeq(ChatEventPageQuery query) {
        if (query == null) {
            return List.of();
        }
        return findByOwnerAndRunAfterSeq(
                        query.tenantId(), query.userId(), query.sessionId(), query.runId(), query.afterSeq())
                .stream()
                .limit(query.limit())
                .toList();
    }

    /**
     * 按用户归属查询会话当前最大事件序号。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return 当前会话最大 seq；无事件时返回 0。
     */
    long findLatestSeqByOwnerAndSession(String tenantId, String userId, String sessionId);
}
