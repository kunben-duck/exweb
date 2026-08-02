package com.huawei.it.ex.one.application.service.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;
import com.huawei.it.ex.one.application.config.MemoryProperties;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.application.integration.memory.LongTermMemoryStore;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceMetadata;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePage;
import com.huawei.it.ex.one.domain.memory.LongTermMemoryItem;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class MemoryApplicationServiceTest {
    private final ChatCommand command = new ChatCommand("cmd1", "tenant1", "user1", "session1",
            null, "web", "帮我分析预算", List.of(), Map.of());

    @Test
    void disabledMemoryReturnsEmptyContextWithoutCallingStores() {
        RecordingMessageRepository messages = new RecordingMessageRepository();
        RecordingLongTermMemoryStore longTermMemory = new RecordingLongTermMemoryStore();

        var context = new MemoryApplicationService(messages, longTermMemory, new MemoryProperties()).loadForRun(command);

        assertThat(context.isEmpty()).isTrue();
        assertThat(messages.findRecentCalls).isZero();
        assertThat(longTermMemory.searchCalls).isZero();
    }

    @Test
    void shortTermMemoryUsesConfiguredRecentTurnsAsMessageLimit() {
        MemoryProperties properties = new MemoryProperties();
        properties.getShortTerm().setEnabled(true);
        properties.getShortTerm().setRecentTurns(3);
        RecordingMessageRepository messages = new RecordingMessageRepository();
        messages.recentMessages = List.of(message("m1", "user"), message("m2", "assistant"));

        var context = new MemoryApplicationService(messages, new RecordingLongTermMemoryStore(), properties)
                .loadForRun(command);

        assertThat(messages.findRecentCalls).isEqualTo(1);
        assertThat(messages.lastLimit).isEqualTo(6);
        assertThat(context.recentMessages()).hasSize(2);
        assertThat(context.longTermMemories()).isEmpty();
    }

    @Test
    void longTermMemoryUsesConfiguredTopK() {
        MemoryProperties properties = new MemoryProperties();
        properties.getLongTerm().setEnabled(true);
        properties.getLongTerm().setTopK(7);
        RecordingLongTermMemoryStore longTermMemory = new RecordingLongTermMemoryStore();
        longTermMemory.items = List.of(new LongTermMemoryItem("mem1", "tenant1", "user1",
                "business_fact", "预算口径按集团规则", 0.9, Instant.now()));

        var context = new MemoryApplicationService(new RecordingMessageRepository(), longTermMemory, properties)
                .loadForRun(command);

        assertThat(longTermMemory.searchCalls).isEqualTo(1);
        assertThat(longTermMemory.lastTopK).isEqualTo(7);
        assertThat(context.recentMessages()).isEmpty();
        assertThat(context.longTermMemories()).hasSize(1);
    }

    @Test
    void shortTermMemoryExcludesPlaceholderAssistant() {
        MemoryProperties properties = new MemoryProperties();
        properties.getShortTerm().setEnabled(true);
        RecordingMessageRepository messages = new RecordingMessageRepository();
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        messages.recentMessages = List.of(
                message("m1", "user"),
                message("m2", "assistant", AgentDataPersistenceMetadata.mergeAssistantMetadata(null, state)),
                message("m3", "assistant"));

        var context = new MemoryApplicationService(
                messages, new RecordingLongTermMemoryStore(), properties).loadForRun(command);

        assertThat(context.recentMessages())
                .extracting(ChatMessage::id)
                .containsExactly("m1", "m3");
    }

    @Test
    void longTermMemoryExcludesConfiguredPlaceholderContent() {
        MemoryProperties properties = new MemoryProperties();
        properties.getLongTerm().setEnabled(true);
        AgentDataPersistenceProperties persistenceProperties = new AgentDataPersistenceProperties();
        persistenceProperties.setPlaceholderContent("回答已隐藏");
        RecordingLongTermMemoryStore longTermMemory = new RecordingLongTermMemoryStore();
        longTermMemory.items = List.of(
                new LongTermMemoryItem("mem1", "tenant1", "user1", "assistant",
                        "回答已隐藏", 1.0, Instant.now()),
                new LongTermMemoryItem("mem2", "tenant1", "user1", "business_fact",
                        "预算口径按集团规则", 0.9, Instant.now()));

        var context = new MemoryApplicationService(
                new RecordingMessageRepository(), longTermMemory, properties, persistenceProperties)
                .loadForRun(command);

        assertThat(context.longTermMemories())
                .extracting(LongTermMemoryItem::id)
                .containsExactly("mem2");
    }

    private ChatMessage message(String id, String role) {
        return new ChatMessage(id, "tenant1", "user1", "session1", role, role + " content", null, Instant.now());
    }

    private ChatMessage message(String id, String role, String metadataJson) {
        return new ChatMessage(id, "tenant1", "user1", "session1", null, 1L, 0, 0,
                role, role + " content", null, null, "NORMAL", false, null, null, null,
                null, metadataJson, Instant.now());
    }

    private static class RecordingMessageRepository implements ChatMessageRepository {
        private int findRecentCalls;
        private int lastLimit;
        private List<ChatMessage> recentMessages = List.of();

        @Override
        public ChatMessage save(ChatMessage message) {
            return message;
        }

        @Override
        public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
            findRecentCalls++;
            lastLimit = limit;
            return recentMessages;
        }

        @Override
        public ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String cursor, int limit) {
            return new ChatMessagePage(List.of(), null);
        }

        @Override
        public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
            return Optional.empty();
        }
    }

    private static class RecordingLongTermMemoryStore implements LongTermMemoryStore {
        private int searchCalls;
        private int lastTopK;
        private List<LongTermMemoryItem> items = List.of();

        @Override
        public List<LongTermMemoryItem> searchRelevant(String tenantId, String userId, String query, int topK) {
            searchCalls++;
            lastTopK = topK;
            return items;
        }

        @Override
        public void save(LongTermMemoryItem item) {
        }
    }
}
