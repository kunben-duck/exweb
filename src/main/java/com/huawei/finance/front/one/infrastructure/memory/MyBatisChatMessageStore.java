package com.huawei.finance.front.one.infrastructure.memory;

import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import com.huawei.finance.front.one.infrastructure.memory.mybatis.ChatMessageMapper;
import com.huawei.finance.front.one.infrastructure.memory.mybatis.ChatMessageRow;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的短期记忆数据库存储。
 *
 * <p>PostgreSQL 是短期记忆最终事实源；Redis 过期、失效或重启后，都从这里恢复会话消息。</p>
 */
@Repository
public class MyBatisChatMessageStore {
    private static final String CURSOR_SEPARATOR = "|";

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

    public ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String cursor, int limit) {
        if (sessionId == null) {
            return new ChatMessagePage(List.of(), null);
        }
        Cursor decoded = decodeCursor(cursor);
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        List<ChatMessage> rows = mapper.findPageByOwner(
                        tenantId,
                        userId,
                        sessionId,
                        decoded.createdAt(),
                        decoded.id(),
                        pageSize + 1
                ).stream()
                .map(this::toDomain)
                .toList();
        boolean hasMore = rows.size() > pageSize;
        List<ChatMessage> pageItemsDescending = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore ? encodeCursor(pageItemsDescending.get(pageItemsDescending.size() - 1)) : null;
        List<ChatMessage> pageItemsAscending = pageItemsDescending.stream()
                .sorted(Comparator.comparing(ChatMessage::createdAt).thenComparing(ChatMessage::id))
                .toList();
        return new ChatMessagePage(pageItemsAscending, nextCursor);
    }

    public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        return mapper.findByOwnerAndId(tenantId, userId, messageId).map(this::toDomain);
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

    private String encodeCursor(ChatMessage message) {
        String raw = message.createdAt().toString() + CURSOR_SEPARATOR + message.id();
        return Base64.getUrlEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Cursor.empty();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf(CURSOR_SEPARATOR);
            if (separator <= 0 || separator == raw.length() - 1) {
                return Cursor.empty();
            }
            return new Cursor(Instant.parse(raw.substring(0, separator)), raw.substring(separator + 1));
        } catch (RuntimeException ex) {
            return Cursor.empty();
        }
    }

    private record Cursor(Instant createdAt, String id) {
        static Cursor empty() {
            return new Cursor(null, null);
        }
    }
}
