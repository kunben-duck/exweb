/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.config.MemoryProperties;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void assistantMetadataUpdateReusesLoadedSnapshotAndRefreshesCacheAfterCommit() {
        FakeRedisCache cache = new FakeRedisCache();
        FakeDatabaseStore database = new FakeDatabaseStore();
        LayeredChatMessageRepository repository = repository(cache, database);
        ChatMessage existing = message().withParts(List.of(part("part1", "CARD", 1)));
        beginTransactionSynchronization();
        try {
            ChatMessage updated = repository.updateAssistantMetadata(
                    existing, "{\"domainAgentAsyncTask\":{\"status\":\"COMPLETED\"}}");

            assertThat(database.metadataUpdateCalls).hasValue(1);
            assertThat(database.findMessageCalls).hasValue(0);
            assertThat(updated.content()).isEqualTo(existing.content());
            assertThat(updated.parts()).containsExactlyElementsOf(existing.parts());
            assertThat(cache.appendCalls).hasValue(0);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            assertThat(cache.removeCalls).hasValue(1);
            assertThat(cache.appendCalls).hasValue(1);
            assertThat(cache.appended.metadataJson()).contains("\"status\":\"COMPLETED\"");
        } finally {
            endTransactionSynchronization();
        }
    }

    @Test
    void asyncReplaceUsesLoadedSnapshotAndPreservesOtherRunParts() {
        FakeRedisCache cache = new FakeRedisCache();
        FakeDatabaseStore database = new FakeDatabaseStore();
        LayeredChatMessageRepository repository = repository(cache, database);
        ChatMessagePart oldCurrent = partWithRun("part-current", "run1", "CARD", 1);
        ChatMessagePart otherRun = partWithRun("part-other", "run0", "REFERENCE", 2);
        ChatMessagePart replacement = partWithRun("part-new", "run1", "ANSWER", 3);
        ChatMessage existing = messageWithRun("run1", List.of(oldCurrent, otherRun));
        ChatMessage update = messageWithRun("run1", List.of(replacement));

        ChatMessage result = repository.updateAssistantAsyncResult(existing, update, true);

        assertThat(database.asyncResultUpdateCalls).hasValue(1);
        assertThat(database.findMessageCalls).hasValue(0);
        assertThat(result.parts()).extracting(ChatMessagePart::id)
                .containsExactly("part-other", "part-new");
        assertThat(cache.appended.parts()).extracting(ChatMessagePart::id)
                .containsExactly("part-other", "part-new");
    }

    @Test
    void redisMissLoadsAndWarmsTheSameRequestedWindowFromDatabase() {
        FakeRedisCache cache = new FakeRedisCache();
        FakeDatabaseStore database = new FakeDatabaseStore();
        database.recentMessages = List.of(message(), message());
        LayeredChatMessageRepository repository = repository(cache, database);

        List<ChatMessage> messages = repository.findRecentMessages(
                "tenant1", "user1", "session1", "leaf-message", 6);

        assertThat(messages).hasSize(2);
        assertThat(cache.findRecentLimit).isEqualTo(6);
        assertThat(cache.findRecentLeaf).isEqualTo("leaf-message");
        assertThat(database.findRecentLimit).isEqualTo(6);
        assertThat(database.findRecentLeaf).isEqualTo("leaf-message");
        assertThat(cache.replacedMessages).containsExactlyElementsOf(database.recentMessages);
    }

    @Test
    void databaseReadFailureReturnsEmptyAndBacksOffEvenWhenDatabaseIsRequired() {
        FakeRedisCache cache = new FakeRedisCache();
        FakeDatabaseStore database = new FakeDatabaseStore();
        database.findRecentFailure = new IllegalStateException("database unavailable");
        LayeredChatMessageRepository repository = repository(cache, database);

        assertThat(repository.findRecentMessages("tenant1", "user1", "session1", "leaf-message", 6))
                .isEmpty();
        database.findRecentFailure = null;
        database.recentMessages = List.of(message());

        assertThat(repository.findRecentMessages("tenant1", "user1", "session1", "leaf-message", 6))
                .isEmpty();
        assertThat(database.findRecentCalls).hasValue(1);
        assertThat(cache.replaceCalls).hasValue(0);
    }

    @Test
    void redisHitRemainsAvailableDuringDatabaseReadBackoff() {
        FakeRedisCache cache = new FakeRedisCache();
        FakeDatabaseStore database = new FakeDatabaseStore();
        database.findRecentFailure = new QueryTimeoutException("query timed out");
        LayeredChatMessageRepository repository = repository(cache, database);

        assertThat(repository.findRecentMessages("tenant1", "user1", "session1", "leaf-message", 6))
                .isEmpty();
        cache.recentMessages = List.of(message());

        assertThat(repository.findRecentMessages("tenant1", "user1", "session1", "leaf-message", 6))
                .containsExactlyElementsOf(cache.recentMessages);
        assertThat(database.findRecentCalls).hasValue(1);
    }

    @Test
    void databaseRequiredStillRejectsMessageWriteFailure() {
        FakeRedisCache cache = new FakeRedisCache();
        FakeDatabaseStore database = new FakeDatabaseStore();
        database.saveFailure = new IllegalStateException("write failed");
        LayeredChatMessageRepository repository = repository(cache, database);

        assertThatThrownBy(() -> repository.save(message()))
                .isSameAs(database.saveFailure);
        assertThat(cache.appendCalls).hasValue(0);
    }

    @Test
    void ownedMessageRoleLookupDelegatesToLightweightDatabaseMethod() {
        FakeRedisCache cache = new FakeRedisCache();
        FakeDatabaseStore database = new FakeDatabaseStore();
        database.saved = message();
        LayeredChatMessageRepository repository = repository(cache, database);

        assertThat(repository.findRoleByOwnerAndId("tenant1", "user1", "msg1"))
                .contains("assistant");
        assertThat(database.findRoleCalls).hasValue(1);
        assertThat(database.findMessageCalls).hasValue(0);
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

    private ChatMessagePart partWithRun(String id, String runId, String type, int order) {
        return new ChatMessagePart(id, "tenant1", "user1", "session1", "msg1", runId,
                type, "test", type, Map.of(), order, Instant.now());
    }

    private ChatMessage messageWithRun(String runId, List<ChatMessagePart> parts) {
        return new ChatMessage(
                "msg1", "tenant1", "user1", "session1", "user1", 2L, 1, 0,
                "assistant", "answer", null, runId, "NORMAL", false,
                null, null, null, null, null, parts, Instant.now());
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
        private final AtomicInteger removeCalls = new AtomicInteger();
        private ChatMessage appended;
        private int findRecentLimit;
        private String findRecentLeaf;
        private final AtomicInteger replaceCalls = new AtomicInteger();
        private List<ChatMessage> recentMessages = List.of();
        private List<ChatMessage> replacedMessages = List.of();

        private FakeRedisCache() {
            super(null, null, new ShortTermMemoryRedisProperties(), new MemoryProperties(), null);
        }

        @Override
        public boolean append(ChatMessage message) {
            appendCalls.incrementAndGet();
            appended = message;
            return true;
        }

        @Override
        public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
            findRecentLimit = limit;
            return recentMessages;
        }

        @Override
        public List<ChatMessage> findRecentMessages(
                String tenantId, String userId, String sessionId, String leafMessageId, int limit) {
            findRecentLeaf = leafMessageId;
            return findRecentMessages(tenantId, userId, sessionId, limit);
        }

        @Override
        public void replaceSessionMessages(
                String tenantId,
                String userId,
                String sessionId,
                List<ChatMessage> messages) {
            replaceCalls.incrementAndGet();
            replacedMessages = List.copyOf(messages);
        }

        @Override
        public void remove(ChatMessage message) {
            removeCalls.incrementAndGet();
        }
    }

    private static final class FakeDatabaseStore extends MyBatisChatMessageStore {
        private final AtomicInteger saveCalls = new AtomicInteger();
        private final AtomicInteger findRecentCalls = new AtomicInteger();
        private final AtomicInteger findRoleCalls = new AtomicInteger();
        private final AtomicInteger findMessageCalls = new AtomicInteger();
        private final AtomicInteger metadataUpdateCalls = new AtomicInteger();
        private final AtomicInteger asyncResultUpdateCalls = new AtomicInteger();
        private ChatMessage saved;
        private RuntimeException saveFailure;
        private RuntimeException findRecentFailure;
        private int findRecentLimit;
        private String findRecentLeaf;
        private List<ChatMessage> recentMessages = List.of();

        private FakeDatabaseStore() {
            super(null, null, new ChatStreamProperties());
        }

        @Override
        public ChatMessage save(ChatMessage message) {
            saveCalls.incrementAndGet();
            if (saveFailure != null) {
                throw saveFailure;
            }
            saved = message;
            return message;
        }

        @Override
        public ChatMessage updateAssistantMessage(ChatMessage message) {
            saved = message;
            return message;
        }

        @Override
        public ChatMessage updateAssistantMetadata(ChatMessage existing, String metadataJson) {
            metadataUpdateCalls.incrementAndGet();
            saved = existing.withMetadataJson(metadataJson);
            return saved;
        }

        @Override
        public ChatMessage updateAssistantAsyncResult(
                ChatMessage update,
                boolean replaceCurrentRunParts) {
            asyncResultUpdateCalls.incrementAndGet();
            saved = update;
            return update;
        }

        @Override
        public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
            findMessageCalls.incrementAndGet();
            return Optional.ofNullable(saved);
        }

        @Override
        public Optional<String> findRoleByOwnerAndId(String tenantId, String userId, String messageId) {
            findRoleCalls.incrementAndGet();
            return Optional.ofNullable(saved).map(ChatMessage::role);
        }

        @Override
        public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
            findRecentCalls.incrementAndGet();
            findRecentLimit = limit;
            if (findRecentFailure != null) {
                throw findRecentFailure;
            }
            return recentMessages;
        }

        @Override
        public List<ChatMessage> findRecentMessages(
                String tenantId, String userId, String sessionId, String leafMessageId, int limit) {
            findRecentLeaf = leafMessageId;
            return findRecentMessages(tenantId, userId, sessionId, limit);
        }
    }
}
