package com.huawei.finance.front.one.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenGaussChatEventStoreTest {
    @Test
    void appendUsesDatabaseReturnedSequenceAsRecoveryCursor() {
        ChatEventMapper mapper = new ReturningEventMapper();
        OpenGaussChatEventStore store = new OpenGaussChatEventStore(
                mapper,
                new ObjectMapper(),
                (bizType, context) -> "event_1"
        );

        ChatEvent appended = store.append(MessageDeltaEvent.of("run1", "session1", "hello"));

        assertThat(appended.sequence()).isEqualTo(42L);
        assertThat(appended.createdAt()).isEqualTo(Instant.parse("2026-05-16T00:00:00Z"));
        assertThat(appended.payload()).containsEntry("delta", "hello");
    }

    private static class ReturningEventMapper implements ChatEventMapper {
        @Override
        public ChatEventRow insertFromSession(String id, String sessionId, String runId, String eventType,
                                              String payloadJson, Instant createdAt) {
            assertThat(id).isEqualTo("event_1");
            assertThat(sessionId).isEqualTo("session1");
            assertThat(runId).isEqualTo("run1");
            assertThat(eventType).isEqualTo("message.delta");
            ChatEventRow row = new ChatEventRow();
            row.setId(id);
            row.setTenantId("tenant1");
            row.setUserId("user1");
            row.setSessionId(sessionId);
            row.setRunId(runId);
            row.setSeq(42L);
            row.setEventType(eventType);
            row.setPayloadJson(payloadJson);
            row.setCreatedAt(Instant.parse("2026-05-16T00:00:00Z"));
            return row;
        }

        @Override
        public List<ChatEventRow> findBySessionIdAndAfterSeq(String sessionId, long afterSeq) {
            return List.of();
        }

        @Override
        public List<ChatEventRow> findByRunIdAndAfterSeq(String runId, long afterSeq) {
            return List.of();
        }

        @Override
        public long findLatestSeqBySessionId(String sessionId) {
            return 0;
        }
    }
}
