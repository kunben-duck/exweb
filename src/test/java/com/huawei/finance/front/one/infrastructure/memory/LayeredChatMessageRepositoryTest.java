package com.huawei.finance.front.one.infrastructure.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.domain.chat.ChatMessage;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class LayeredChatMessageRepositoryTest {
    @Test
    void databaseSaveUpdatesRedisOnlyAfterTransactionCommit() {
        FakeRedisCache cache = new FakeRedisCache();
        FakeDatabaseStore database = new FakeDatabaseStore();
        LayeredChatMessageRepository repository = repository(cache, database);
        beginTransactionSynchronization();
        try {
            repository.save(message());

            assertThat(database.saveCalls).hasValue(1);
            assertThat(cache.appendCalls).hasValue(0);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            assertThat(cache.appendCalls).hasValue(1);
        } finally {
            endTransactionSynchronization();
        }
    }

    @Test
    void rolledBackTransactionDoesNotPublishMessageToRedis() {
        FakeRedisCache cache = new FakeRedisCache();
        FakeDatabaseStore database = new FakeDatabaseStore();
        LayeredChatMessageRepository repository = repository(cache, database);
        beginTransactionSynchronization();
        try {
            repository.save(message());

            assertThat(database.saveCalls).hasValue(1);
            assertThat(cache.appendCalls).hasValue(0);
        } finally {
            endTransactionSynchronization();
        }

        assertThat(cache.appendCalls).hasValue(0);
    }

    private LayeredChatMessageRepository repository(FakeRedisCache cache, FakeDatabaseStore database) {
        ShortTermMemoryStorageProperties properties = new ShortTermMemoryStorageProperties();
        properties.setDatabaseRequired(true);
        return new LayeredChatMessageRepository(cache, database, properties);
    }

    private ChatMessage message() {
        return new ChatMessage("msg1", "tenant1", "user1", "session1",
                "assistant", "answer", null, Instant.now());
    }

    private void beginTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private void endTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static final class FakeRedisCache extends RedisShortTermMemoryCache {
        private final AtomicInteger appendCalls = new AtomicInteger();

        private FakeRedisCache() {
            super(null, null, new ShortTermMemoryRedisProperties(), null);
        }

        @Override
        public boolean append(ChatMessage message) {
            appendCalls.incrementAndGet();
            return true;
        }

        @Override
        public void remove(ChatMessage message) {
        }
    }

    private static final class FakeDatabaseStore extends MyBatisChatMessageStore {
        private final AtomicInteger saveCalls = new AtomicInteger();
        private ChatMessage saved;

        private FakeDatabaseStore() {
            super(null, null);
        }

        @Override
        public ChatMessage save(ChatMessage message) {
            saveCalls.incrementAndGet();
            saved = message;
            return message;
        }

        @Override
        public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
            return Optional.ofNullable(saved);
        }
    }
}
