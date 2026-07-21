package com.huawei.it.ex.one.intent.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.intent.application.config.MemoryProperties;
import com.huawei.it.ex.one.intent.application.service.IntentHistoryService;
import com.huawei.it.ex.one.intent.application.model.IntentMemoryRequest;
import com.huawei.it.ex.one.intent.application.model.IntentMessageSnapshot;
import com.huawei.it.ex.one.intent.application.repository.LongTermMemoryStore;
import com.huawei.it.ex.one.intent.domain.memory.LongTermMemoryItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
class MemoryApplicationServiceTest {
    private final IntentMemoryRequest request =
            new IntentMemoryRequest("tenant1", "user1", "session1", "帮我分析预算");

    @Test
    void disabledMemoryReturnsEmptyContextWithoutCallingStores() {
        RecordingMessageRepository messages = new RecordingMessageRepository();
        RecordingLongTermMemoryStore longTermMemory = new RecordingLongTermMemoryStore();

        var context = new MemoryApplicationService(messages, longTermMemory, new MemoryProperties()).loadForRun(request);

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
                .loadForRun(request);

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
                .loadForRun(request);

        assertThat(longTermMemory.searchCalls).isEqualTo(1);
        assertThat(longTermMemory.lastTopK).isEqualTo(7);
        assertThat(context.recentMessages()).isEmpty();
        assertThat(context.longTermMemories()).hasSize(1);
    }

    private IntentMessageSnapshot message(String id, String role) {
        return new IntentMessageSnapshot(id, role, role + " content", Instant.now());
    }

    private static class RecordingMessageRepository implements IntentHistoryService {
        private int findRecentCalls;
        private int lastLimit;
        private List<IntentMessageSnapshot> recentMessages = List.of();

        @Override
        public List<IntentMessageSnapshot> findRecentMessages(
                String tenantId, String userId, String sessionId, int limit) {
            findRecentCalls++;
            lastLimit = limit;
            return recentMessages;
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
