package com.huawei.finance.front.one.application.integration.memory;

import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import java.util.List;
import java.util.Optional;

/**
 * 聊天消息仓储，也是当前短期记忆的应用层抽象。
 *
 * <p>application 层只表达保存和读取最近会话消息的能力，不关心底层是 Redis、数据库还是组合存储。</p>
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
     * 按归属查询单条消息。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param messageId 消息标识。
     * @return 当前用户拥有的消息；不存在或不属于当前用户时为空。
     */
    Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId);

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
