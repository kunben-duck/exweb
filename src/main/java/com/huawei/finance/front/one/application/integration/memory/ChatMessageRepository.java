package com.huawei.finance.front.one.application.integration.memory;

import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessageAttachment;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePart;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 聊天消息仓储，也可作为短期最近问答记忆的数据来源。
 *
 * <p>会话历史消息始终是可审计事实；短期记忆是否读取最近消息由 MemoryApplicationService 配置决定。
 * application 层只表达保存和读取最近会话消息的能力，不关心底层是 Redis、数据库还是组合存储。</p>
 */
public interface ChatMessageRepository {
    /**
     * 保存聊天消息。
     *
     * @param message 用户或助手完整消息。
     * @return 已保存的消息。
     */
    ChatMessage save(ChatMessage message);

    /**
     * 按租户、用户和会话读取最近消息，避免跨用户会话记忆串用。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @param limit 最大返回条数。
     * @return 最近消息列表，通常按时间倒序返回给 MemoryContext 使用。
     */
    List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit);

    /**
     * 分页查询历史消息，供前端会话详情页使用。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param cursor 上一页返回的游标。
     * @param limit 最大返回条数。
     * @return 按时间正序排列的消息分页。
     */
    ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String cursor, int limit);

    /**
     * 批量查询每个会话的第一条 assistant 完整消息。
     *
     * <p>该方法用于会话分页列表摘要装配，必须以 tenantId/userId/sessionIds 联合过滤，避免跨租户、
     * 跨用户读取其他会话内容。返回 Map 的 key 为 sessionId。</p>
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionIds 当前页会话 ID 列表。
     * @return sessionId 到第一条 assistant 消息的映射。
     */
    default Map<String, ChatMessage> findFirstAssistantMessagesBySessionIds(
            String tenantId, String userId, List<String> sessionIds) {
        return Map.of();
    }

    /**
     * 查询指定 leaf 的可见消息路径。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param leafMessageId 叶子消息；为空时使用会话 current leaf。
     * @param cursor 保留分页游标；active path 首版返回空 nextCursor。
     * @param limit 最大返回条数。
     * @return root 到 leaf 的 active path。
     */
    default ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String leafMessageId,
                                         String cursor, int limit) {
        return pageMessages(tenantId, userId, sessionId, cursor, limit);
    }

    /**
     * 查询当前会话内完整可见消息树节点。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @return 当前用户当前会话内全部 user/assistant 完整消息，按 nodeOrder 排序。
     */
    default List<ChatMessage> findAllBySession(String tenantId, String userId, String sessionId) {
        return pageMessages(tenantId, userId, sessionId, null, null, Integer.MAX_VALUE).items();
    }

    /**
     * 按归属查询单条消息。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param messageId 消息标识。
     * @return 当前用户拥有的消息；不存在或不属于当前用户时为空。
     */
    Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId);

    /**
     * 查询同一父节点下同角色的候选消息。
     */
    default List<ChatMessage> findSiblings(String tenantId, String userId, String sessionId,
                                           String parentMessageId, String role) {
        return List.of();
    }

    /**
     * 统计同父节点下同角色候选数量，用于生成 siblingIndex。
     */
    default int countSiblings(String tenantId, String userId, String sessionId, String parentMessageId, String role) {
        return 0;
    }

    /**
     * 查询 root 到指定消息的完整路径。
     */
    default List<ChatMessage> findPathToMessage(String tenantId, String userId, String sessionId, String leafMessageId) {
        return pageMessages(tenantId, userId, sessionId, leafMessageId, null, Integer.MAX_VALUE).items();
    }

    /**
     * 保存消息附件引用。
     */
    default ChatMessageAttachment saveAttachment(ChatMessageAttachment attachment) {
        return attachment;
    }

    /**
     * 保存一条 assistant 历史消息结构化 part。
     *
     * @param part 已补齐归属字段的 message part。
     * @return 已保存的 part。
     */
    default ChatMessagePart savePart(ChatMessagePart part) {
        return part;
    }

    /**
     * 查询指定消息的附件引用。
     */
    default List<ChatMessageAttachment> findAttachments(String tenantId, String userId, String messageId) {
        return List.of();
    }

    /**
     * 批量查询消息结构化 parts。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param messageIds 消息 ID 列表。
     * @return 当前用户当前会话内的 message parts。
     */
    default List<ChatMessagePart> findPartsByMessageIds(String tenantId, String userId, String sessionId,
                                                        List<String> messageIds) {
        return List.of();
    }

    /**
     * 按会话读取最近消息的兼容方法。
     *
     * @param sessionId 前端聊天会话标识。
     * @param limit 最大返回条数。
     * @return 最近消息列表。
     */
    default List<ChatMessage> findRecentMessages(String sessionId, int limit) {
        return findRecentMessages(null, null, sessionId, limit);
    }
}
