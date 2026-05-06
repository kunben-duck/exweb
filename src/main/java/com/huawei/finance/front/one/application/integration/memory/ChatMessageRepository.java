package com.huawei.finance.front.one.application.integration.memory;

import com.huawei.finance.front.one.domain.chat.ChatMessage;
import java.util.List;

/**
 * 聊天消息仓储，也是当前短期记忆的应用层抽象。
 *
 * <p>application 层只表达保存和读取最近会话消息的能力，不关心底层是 Redis、数据库还是组合存储。</p>
 */
public interface ChatMessageRepository {
    ChatMessage save(ChatMessage message);

    /**
     * 按租户、用户和会话读取最近消息，避免跨用户会话记忆串用。
     */
    List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit);

    default List<ChatMessage> findRecentMessages(String sessionId, int limit) {
        return findRecentMessages(null, null, sessionId, limit);
    }
}
