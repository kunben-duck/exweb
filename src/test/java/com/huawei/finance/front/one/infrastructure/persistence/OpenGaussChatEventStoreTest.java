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
    void appendUsesDatabaseSequenceAsRecoveryCursor() {
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
        private ChatEventRow insertedRow;

        @Override
        public Long nextSeq() {
            return 42L;
        }

        @Override
        public int insertFromSession(String id, String sessionId, String runId, long seq, String eventType,
                                     String payloadJson, Instant createdAt) {
            assertThat(id).isEqualTo("event_1");
            assertThat(sessionId).isEqualTo("session1");
            assertThat(runId).isEqualTo("run1");
            assertThat(seq).isEqualTo(42L);
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
            insertedRow = row;
            return 1;
        }

        @Override
        public ChatEventRow findById(String id) {
            assertThat(id).isEqualTo("event_1");
            return insertedRow;
        }

        @Override
        public List<ChatEventRow> findByOwnerAndSessionAfterSeq(String tenantId, String userId, String sessionId, long afterSeq) {
            return List.of();
        }

        @Override
        public List<ChatEventRow> findByOwnerAndRunAfterSeq(String tenantId, String userId, String sessionId,
                                                            String runId, long afterSeq) {
            return List.of();
        }

        @Override
        public long findLatestSeqByOwnerAndSession(String tenantId, String userId, String sessionId) {
            return 0;
        }
    }
}
