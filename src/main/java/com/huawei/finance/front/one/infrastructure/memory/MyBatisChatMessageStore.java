package com.huawei.finance.front.one.infrastructure.memory;

import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.infrastructure.memory.mybatis.ChatMessageMapper;
import com.huawei.finance.front.one.infrastructure.memory.mybatis.ChatMessageRow;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的短期记忆数据库存储。
 *
 * <p>PostgreSQL 是短期记忆最终事实源；Redis 过期、失效或重启后，都从这里恢复会话消息。</p>
 */
@Repository
public class MyBatisChatMessageStore {
    private final ChatMessageMapper mapper;

    public MyBatisChatMessageStore(ChatMessageMapper mapper) {
        this.mapper = mapper;
    }

    public ChatMessage save(ChatMessage message) {
        mapper.insert(
                message.id(),
                message.tenantId(),
                message.userId(),
                message.sessionId(),
                message.role(),
                message.content(),
                message.tokenCount(),
                message.createdAt()
        );
        return message;
    }

    public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
        if (sessionId == null || limit <= 0) {
            return List.of();
        }
        if (tenantId == null || userId == null) {
            return mapper.findRecentBySession(sessionId, limit).stream().map(this::toDomain).toList();
        }
        return mapper.findRecentByOwner(tenantId, userId, sessionId, limit).stream().map(this::toDomain).toList();
    }

    private ChatMessage toDomain(ChatMessageRow row) {
        return new ChatMessage(
                row.getId(),
                row.getTenantId(),
                row.getUserId(),
                row.getSessionId(),
                row.getRole(),
                row.getContent(),
                row.getTokenCount(),
                row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt()
        );
    }
}
