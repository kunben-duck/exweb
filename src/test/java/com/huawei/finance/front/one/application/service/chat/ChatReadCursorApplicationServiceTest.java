package com.huawei.finance.front.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.config.ChatReadCursorProperties;
import com.huawei.finance.front.one.application.integration.conversation.ChatReadCursorCache;
import com.huawei.finance.front.one.application.integration.conversation.ChatReadCursorRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatReadCursor;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
class ChatReadCursorApplicationServiceTest {
    @Test
    void acknowledgeWritesRedisEveryTimeAndFlushesDatabaseByPolicy() {
        InMemoryCursorRepository repository = new InMemoryCursorRepository();
        InMemoryCursorCache cache = new InMemoryCursorCache();
        ChatReadCursorApplicationService service = service(repository, cache, Duration.ZERO);

        service.acknowledgeTrustedSession(user(), "session1", 8L);
        service.acknowledgeTrustedSession(user(), "session1", 6L);

        assertThat(cache.seq).isEqualTo(8L);
        assertThat(repository.seq).isEqualTo(8L);
    }

    @Test
    void findUsesCacheBeforeOpenGaussAndFallsBackToRepository() {
        InMemoryCursorRepository repository = new InMemoryCursorRepository();
        InMemoryCursorCache cache = new InMemoryCursorCache();
        repository.seq = 9L;
        ChatReadCursorApplicationService service = service(repository, cache, Duration.ZERO);

        assertThat(service.findLastConsumedSeq(user(), "session1")).isEqualTo(9L);
        assertThat(cache.seq).isEqualTo(9L);

        repository.seq = 12L;
        assertThat(service.findLastConsumedSeq(user(), "session1")).isEqualTo(9L);
    }

    @Test
    void flushAlwaysPersistsLatestAcknowledgement() {
        InMemoryCursorRepository repository = new InMemoryCursorRepository();
        ChatReadCursorApplicationService service = service(repository, new InMemoryCursorCache(), Duration.ofHours(1));

        service.acknowledgeTrustedSession(user(), "session1", 3L);
        service.flushTrustedSession(user(), "session1", 7L);

        assertThat(repository.seq).isEqualTo(7L);
    }

    private ChatReadCursorApplicationService service(InMemoryCursorRepository repository, InMemoryCursorCache cache,
                                                     Duration flushInterval) {
        ChatReadCursorProperties properties = new ChatReadCursorProperties();
        properties.setDatabaseFlushInterval(flushInterval);
        return new ChatReadCursorApplicationService(
                repository,
                cache,
                new PermissionChecker(),
                new FixedSessionRepository(),
                properties
        );
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private static class InMemoryCursorRepository implements ChatReadCursorRepository {
        private long seq;

        @Override
        public Optional<ChatReadCursor> find(String tenantId, String userId, String sessionId) {
            return seq <= 0 ? Optional.empty()
                    : Optional.of(new ChatReadCursor("cursor1", tenantId, userId, sessionId, seq, Instant.now()));
        }

        @Override
        public ChatReadCursor upsert(String tenantId, String userId, String sessionId, long lastConsumedSeq) {
            seq = Math.max(seq, lastConsumedSeq);
            return new ChatReadCursor("cursor1", tenantId, userId, sessionId, seq, Instant.now());
        }
    }

    private static class InMemoryCursorCache implements ChatReadCursorCache {
        private long seq;

        @Override
        public Optional<ChatReadCursor> find(String tenantId, String userId, String sessionId) {
            return seq <= 0 ? Optional.empty()
                    : Optional.of(new ChatReadCursor("cursor1", tenantId, userId, sessionId, seq, Instant.now()));
        }

        @Override
        public void put(ChatReadCursor cursor) {
            seq = Math.max(seq, cursor.lastConsumedSeq());
        }
    }

    private static class FixedSessionRepository implements SessionRepository {
        @Override
        public Optional<ChatSession> findById(String sessionId) {
            return Optional.empty();
        }

        @Override
        public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
            Instant now = Instant.now();
            return Optional.of(new ChatSession(sessionId, tenantId, userId, "title", "ACTIVE", "web", now, now));
        }

        @Override
        public List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId) {
            return List.of();
        }

        @Override
        public ChatSessionPage pageByTenantIdAndUserId(String tenantId, String userId, String cursor, int limit) {
            return new ChatSessionPage(List.of(), null);
        }

        @Override
        public ChatSession save(ChatSession session) {
            return session;
        }
    }
}
