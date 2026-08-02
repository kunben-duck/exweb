package com.huawei.it.ex.one.infrastructure.session;

import com.huawei.it.ex.one.application.integration.conversation.SessionAppCategory;
import com.huawei.it.ex.one.application.integration.conversation.SessionListFilter;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ChatSessionNumberPage;
import com.huawei.it.ex.one.domain.chat.ChatSessionPage;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 聊天会话数据库事实源实现。
 */
@Repository
public class MyBatisSessionRepository implements SessionRepository {
    private static final String CURSOR_SEPARATOR = "|";
    private static final String CURSOR_VERSION_V2 = "v2";
    private static final String CURSOR_VERSION_V3 = "v3";
    private static final String APP_ID_FILTER_MISMATCH = "cursor 与当前 appId 过滤条件不一致";
    private static final String TITLE_FILTER_MISMATCH = "cursor 与当前 title 过滤条件不一致";

    private final ChatSessionMapper mapper;

    public MyBatisSessionRepository(ChatSessionMapper mapper) {
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
    public List<SessionAppCategory> findAppsByTenantIdAndUserId(String tenantId, String userId) {
        return mapper.findAppsByOwner(tenantId, userId).stream()
                .map(row -> new SessionAppCategory(row.getAppId(), row.getAppName()))
                .toList();
    }

    @Override
    public ChatSessionPage pageByTenantIdAndUserId(String tenantId, String userId, String cursor, int limit) {
        return pageByTenantIdAndUserId(tenantId, userId, SessionListFilter.empty(), cursor, limit);
    }

    @Override
    public ChatSessionPage pageByTenantIdAndUserId(String tenantId, String userId, String appId,
                                                   String cursor, int limit) {
        return pageByTenantIdAndUserId(
                tenantId, userId, new SessionListFilter(appId, null), cursor, limit);
    }

    @Override
    public ChatSessionPage pageByTenantIdAndUserId(
            String tenantId, String userId, SessionListFilter filter, String cursor, int limit) {
        String appId = filter == null ? null : filter.appId();
        String title = filter == null ? null : filter.title();
        String normalizedAppId = normalize(appId);
        String normalizedTitle = normalizeTitle(title);
        Cursor decoded = decodeCursor(cursor, normalizedAppId, normalizedTitle);
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 200));
        List<ChatSession> rows = mapper.findPageByOwner(
                        tenantId,
                        userId,
                        normalizedAppId,
                        titlePattern(normalizedTitle),
                        decoded.updatedAt(),
                        decoded.id(),
                        pageSize + 1
                ).stream()
                .map(this::toDomain)
                .toList();
        boolean hasMore = rows.size() > pageSize;
        List<ChatSession> items = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore
                ? encodeCursor(items.get(items.size() - 1), normalizedAppId, normalizedTitle)
                : null;
        return new ChatSessionPage(items, nextCursor);
    }

    @Override
    public ChatSessionNumberPage pageNumberByTenantIdAndUserId(String tenantId, String userId,
                                                               int curPage, int pageSize) {
        return pageNumberByTenantIdAndUserId(
                tenantId, userId, SessionListFilter.empty(), curPage, pageSize);
    }

    @Override
    public ChatSessionNumberPage pageNumberByTenantIdAndUserId(String tenantId, String userId, String appId,
                                                               int curPage, int pageSize) {
        return pageNumberByTenantIdAndUserId(
                tenantId, userId, new SessionListFilter(appId, null), curPage, pageSize);
    }

    @Override
    public ChatSessionNumberPage pageNumberByTenantIdAndUserId(
            String tenantId, String userId, SessionListFilter filter, int curPage, int pageSize) {
        String appId = filter == null ? null : filter.appId();
        String title = filter == null ? null : filter.title();
        int normalizedPage = Math.max(1, curPage);
        int normalizedSize = Math.max(1, Math.min(pageSize <= 0 ? 20 : pageSize, 200));
        String normalizedAppId = normalize(appId);
        String normalizedTitle = normalizeTitle(title);
        String titlePattern = titlePattern(normalizedTitle);
        long totalRows = mapper.countPageByOwner(tenantId, userId, normalizedAppId, titlePattern);
        long totalPages = totalRows == 0 ? 0 : (totalRows + normalizedSize - 1) / normalizedSize;
        long offset = (long) (normalizedPage - 1) * normalizedSize;
        List<ChatSession> items = totalRows == 0 || offset >= totalRows
                ? List.of()
                : mapper.findNumberPageByOwner(
                        tenantId, userId, normalizedAppId, titlePattern, normalizedSize, offset).stream()
                .map(this::toDomain)
                .toList();
        return new ChatSessionNumberPage(items, normalizedPage, normalizedSize, totalRows, totalPages);
    }

    @Override
    public ChatSession save(ChatSession session) {
        ChatSessionRow row = toRow(session);
        int updated = mapper.update(row);
        if (updated == 0) {
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException ex) {
                // 并发创建同一会话时，另一事务可能先插入成功；此时回退为更新，避免依赖 具体数据库专有 upsert。
                mapper.update(row);
            }
        }
        return session;
    }

    @Override
    public ChatSession touch(ChatSession session, Instant updatedAt) {
        int updated = mapper.touch(session.tenantId(), session.userId(), session.id(), updatedAt);
        if (updated != 1) {
            throw new IllegalArgumentException("会话不存在或不属于当前用户: " + session.id());
        }
        return copySession(session, session.title(), session.metadataJson(), updatedAt);
    }

    @Override
    public ChatSession updateTitleWithoutTouch(ChatSession session, String title, String metadataJson) {
        int updated = mapper.updateTitleWithoutTouch(
                session.tenantId(), session.userId(), session.id(), title, metadataJson);
        if (updated != 1) {
            throw new IllegalStateException("会话标题更新条件不再满足: " + session.id());
        }
        return copySession(session, title, metadataJson, session.updatedAt());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForMessageMutation(String tenantId, String userId, String sessionId) {
        Long current = mapper.lockNodeOrder(tenantId, userId, sessionId);
        if (current == null) {
            throw new IllegalArgumentException("会话不存在或不属于当前用户: " + sessionId);
        }
    }

    @Override
    @Transactional
    public long nextNodeOrder(String tenantId, String userId, String sessionId) {
        Long current = mapper.lockNodeOrder(tenantId, userId, sessionId);
        if (current == null) {
            throw new IllegalArgumentException("会话不存在或不属于当前用户: " + sessionId);
        }
        long next = current + 1;
        mapper.updateNodeOrder(tenantId, userId, sessionId, next, Instant.now());
        return next;
    }

    @Override
    public void updateCurrentLeaf(String tenantId, String userId, String sessionId, String leafMessageId) {
        int updated = mapper.updateCurrentLeaf(tenantId, userId, sessionId, leafMessageId, Instant.now());
        if (updated == 0) {
            throw new IllegalArgumentException("会话不存在或不属于当前用户: " + sessionId);
        }
    }

    @Override
    public void advanceLatestMessageSeq(String tenantId, String userId, String sessionId, long messageSeq) {
        if (messageSeq < 0L) {
            throw new IllegalArgumentException("messageSeq 不能小于 0");
        }
        if (mapper.advanceLatestMessageSeq(tenantId, userId, sessionId, messageSeq) == 0) {
            throw new IllegalArgumentException("会话不存在或不属于当前用户: " + sessionId);
        }
    }

    @Override
    public ChatSession markReadThrough(String tenantId, String userId, String sessionId, long readThroughSeq) {
        if (readThroughSeq < 0L) {
            throw new IllegalArgumentException("readThroughSeq 不能小于 0");
        }
        if (mapper.markReadThrough(tenantId, userId, sessionId, readThroughSeq) == 0) {
            throw new IllegalArgumentException("会话不存在或不属于当前用户: " + sessionId);
        }
        return findByTenantIdAndUserIdAndId(tenantId, userId, sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或不属于当前用户: " + sessionId));
    }

    private ChatSession toDomain(ChatSessionRow row) {
        return new ChatSession(row.getId(), row.getTenantId(), row.getUserId(), row.getTitle(), row.getStatus(),
                row.getChannel(), row.getAppId(), row.getAppName(), row.getCurrentLeafMessageId(), row.getRootSessionId(),
                row.getBranchSourceSessionId(), row.getBranchSourceMessageId(), row.getLastNodeOrder(),
                row.getLatestMessageSeq() == null ? 0L : row.getLatestMessageSeq(),
                row.getLastReadSeq() == null ? 0L : row.getLastReadSeq(), row.getMetadataJson(),
                row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt(),
                row.getUpdatedAt() == null ? Instant.EPOCH : row.getUpdatedAt());
    }

    private ChatSessionRow toRow(ChatSession session) {
        ChatSessionRow row = new ChatSessionRow();
        row.setId(session.id());
        row.setTenantId(session.tenantId());
        row.setUserId(session.userId());
        row.setTitle(session.title());
        row.setStatus(session.status());
        row.setChannel(session.channel());
        row.setAppId(session.appId());
        row.setAppName(session.appName());
        row.setCurrentLeafMessageId(session.currentLeafMessageId());
        row.setRootSessionId(session.rootSessionId());
        row.setBranchSourceSessionId(session.branchSourceSessionId());
        row.setBranchSourceMessageId(session.branchSourceMessageId());
        row.setLastNodeOrder(session.lastNodeOrder());
        row.setLatestMessageSeq(session.latestMessageSeq());
        row.setLastReadSeq(session.lastReadSeq());
        row.setMetadataJson(session.metadataJson());
        row.setCreatedAt(session.createdAt());
        row.setUpdatedAt(session.updatedAt());
        return row;
    }

    private ChatSession copySession(
            ChatSession session, String title, String metadataJson, Instant updatedAt) {
        return new ChatSession(
                session.id(), session.tenantId(), session.userId(), title, session.status(), session.channel(),
                session.appId(), session.appName(), session.currentLeafMessageId(), session.rootSessionId(),
                session.branchSourceSessionId(), session.branchSourceMessageId(), session.lastNodeOrder(),
                session.latestMessageSeq(), session.lastReadSeq(), metadataJson, session.createdAt(), updatedAt);
    }

    private String encodeCursor(ChatSession session, String appId, String title) {
        String raw;
        if (title == null) {
            raw = CURSOR_VERSION_V2 + CURSOR_SEPARATOR + encodeFilter(appId) + CURSOR_SEPARATOR
                    + session.updatedAt() + CURSOR_SEPARATOR + session.id();
        } else {
            raw = CURSOR_VERSION_V3 + CURSOR_SEPARATOR + encodeFilter(appId) + CURSOR_SEPARATOR
                    + encodeFilter(title) + CURSOR_SEPARATOR + session.updatedAt() + CURSOR_SEPARATOR + session.id();
        }
        return Base64.getUrlEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String cursor, String expectedAppId, String expectedTitle) {
        if (cursor == null || cursor.isBlank()) {
            return Cursor.empty();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            return decodeCursorValue(raw, expectedAppId, expectedTitle);
        } catch (IllegalArgumentException ex) {
            if (APP_ID_FILTER_MISMATCH.equals(ex.getMessage()) || TITLE_FILTER_MISMATCH.equals(ex.getMessage())) {
                throw ex;
            }
            return Cursor.empty();
        } catch (RuntimeException ex) {
            return Cursor.empty();
        }
    }

    private Cursor decodeCursorValue(String raw, String expectedAppId, String expectedTitle) {
        String[] parts = raw.split("\\|", -1);
        if (parts.length == 4 && CURSOR_VERSION_V2.equals(parts[0])) {
            validateFilter(expectedAppId, decodeFilter(parts[1]), APP_ID_FILTER_MISMATCH);
            validateFilter(expectedTitle, null, TITLE_FILTER_MISMATCH);
            return new Cursor(Instant.parse(parts[2]), parts[3]);
        }
        if (parts.length == 5 && CURSOR_VERSION_V3.equals(parts[0])) {
            validateFilter(expectedAppId, decodeFilter(parts[1]), APP_ID_FILTER_MISMATCH);
            validateFilter(expectedTitle, decodeFilter(parts[2]), TITLE_FILTER_MISMATCH);
            return new Cursor(Instant.parse(parts[3]), parts[4]);
        }
        return Cursor.empty();
    }

    private void validateFilter(String expected, String actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(message);
        }
    }

    private String encodeFilter(String value) {
        return value == null ? "" : Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeFilter(String value) {
        return value.isEmpty() ? null : new String(
                Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private String titlePattern(String normalizedTitle) {
        if (normalizedTitle == null) {
            return null;
        }
        String escaped = normalizedTitle
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeTitle(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private record Cursor(Instant updatedAt, String id) {
        static Cursor empty() {
            return new Cursor(null, null);
        }
    }
}
