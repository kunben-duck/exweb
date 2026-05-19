package com.huawei.finance.front.one.infrastructure.session;

import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 聊天会话 openGauss 事实源实现。
 */
@Repository
public class OpenGaussSessionRepository implements SessionRepository {
    private static final String CURSOR_SEPARATOR = "|";

    private final ChatSessionMapper mapper;

    public OpenGaussSessionRepository(ChatSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ChatSession> findById(String sessionId) {
        return Optional.ofNullable(mapper.findById(sessionId)).map(this::toDomain);
    }

    @Override
    public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
        return Optional.ofNullable(mapper.findByOwnerAndId(tenantId, userId, sessionId)).map(this::toDomain);
    }

    @Override
    public List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId) {
        return mapper.findByOwner(tenantId, userId).stream().map(this::toDomain).toList();
    }

    @Override
    public ChatSessionPage pageByTenantIdAndUserId(String tenantId, String userId, String cursor, int limit) {
        Cursor decoded = decodeCursor(cursor);
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        List<ChatSession> rows = mapper.findPageByOwner(
                        tenantId,
                        userId,
                        decoded.updatedAt(),
                        decoded.id(),
                        pageSize + 1
                ).stream()
                .map(this::toDomain)
                .toList();
        boolean hasMore = rows.size() > pageSize;
        List<ChatSession> items = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore ? encodeCursor(items.get(items.size() - 1)) : null;
        return new ChatSessionPage(items, nextCursor);
    }

    @Override
    public ChatSession save(ChatSession session) {
        mapper.upsert(session.id(), session.tenantId(), session.userId(), session.title(), session.status(),
                session.channel(), session.currentLeafMessageId(), session.rootSessionId(),
                session.branchSourceSessionId(), session.branchSourceMessageId(), session.lastNodeOrder(),
                session.metadataJson(), session.createdAt(), session.updatedAt());
        return session;
    }

    @Override
    public long nextNodeOrder(String tenantId, String userId, String sessionId) {
        Long next = mapper.incrementNodeOrder(tenantId, userId, sessionId, Instant.now());
        if (next == null) {
            throw new IllegalArgumentException("会话不存在或不属于当前用户: " + sessionId);
        }
        return next;
    }

    @Override
    public void updateCurrentLeaf(String tenantId, String userId, String sessionId, String leafMessageId) {
        int updated = mapper.updateCurrentLeaf(tenantId, userId, sessionId, leafMessageId, Instant.now());
        if (updated == 0) {
            throw new IllegalArgumentException("会话不存在或不属于当前用户: " + sessionId);
        }
    }

    private ChatSession toDomain(ChatSessionRow row) {
        return new ChatSession(row.getId(), row.getTenantId(), row.getUserId(), row.getTitle(), row.getStatus(),
                row.getChannel(), row.getCurrentLeafMessageId(), row.getRootSessionId(),
                row.getBranchSourceSessionId(), row.getBranchSourceMessageId(), row.getLastNodeOrder(),
                row.getMetadataJson(), row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt(),
                row.getUpdatedAt() == null ? Instant.EPOCH : row.getUpdatedAt());
    }

    private String encodeCursor(ChatSession session) {
        String raw = session.updatedAt().toString() + CURSOR_SEPARATOR + session.id();
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

    private record Cursor(Instant updatedAt, String id) {
        static Cursor empty() {
            return new Cursor(null, null);
        }
    }
}
