package com.huawei.finance.front.one.infrastructure.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePart;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    @Test
    void assistantUpdateKeepsPreviousAndNewPartsInRedisProjection() {
        FakeRedisCache cache = new FakeRedisCache();
        FakeDatabaseStore database = new FakeDatabaseStore();
        LayeredChatMessageRepository repository = repository(cache, database);
        ChatMessagePart refusal = part("part1", "DOMAIN_AGENT_REFUSAL", 1);
        ChatMessagePart answer = part("part2", "ANSWER", 2);
        database.saved = message().withParts(List.of(refusal));

        ChatMessage updated = repository.updateAssistantMessage(message().withParts(List.of(answer)));

        assertThat(updated.parts()).extracting(ChatMessagePart::partType)
                .containsExactly("DOMAIN_AGENT_REFUSAL", "ANSWER");
        assertThat(cache.appended.parts()).extracting(ChatMessagePart::partType)
                .containsExactly("DOMAIN_AGENT_REFUSAL", "ANSWER");
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

    private ChatMessagePart part(String id, String type, int order) {
        return new ChatMessagePart(id, "tenant1", "user1", "session1", "msg1", "run1",
                type, "test", type, Map.of(), order, Instant.now());
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
        private ChatMessage appended;

        private FakeRedisCache() {
            super(null, null, new ShortTermMemoryRedisProperties(), null);
        }

        @Override
        public boolean append(ChatMessage message) {
            appendCalls.incrementAndGet();
            appended = message;
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
        public ChatMessage updateAssistantMessage(ChatMessage message) {
            saved = message;
            return message;
        }

        @Override
        public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
            return Optional.ofNullable(saved);
        }
    }
}
