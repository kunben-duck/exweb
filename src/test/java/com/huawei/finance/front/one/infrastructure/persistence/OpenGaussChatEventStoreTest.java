package com.huawei.finance.front.one.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.domain.chat.RunExecutionClaim;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenGaussChatEventStoreTest {
    @Test
    void appendUsesDatabaseSequenceAsRecoveryCursor() {
        ChatEventMapper mapper = new ReturningEventMapper();
        OpenGaussChatEventStore store = new OpenGaussChatEventStore(
                mapper,
                new ObjectMapper(),
                (bizType, context) -> "event_1"
        );

        ChatEvent appended = store.append(new MessageDeltaEvent("run1", "session1", 0L,
                Instant.parse("2026-05-16T00:00:00Z"), "hello", Map.of("delta", "hello")));

        assertThat(appended.sequence()).isEqualTo(42L);
        assertThat(appended.createdAt()).isEqualTo(Instant.parse("2026-05-16T00:00:00Z"));
        assertThat(appended.payload()).containsEntry("delta", "hello");
    }

    @Test
    void appendWithExecutionGuardUsesOwnerAndFencingToken() {
        ReturningEventMapper mapper = new ReturningEventMapper();
        OpenGaussChatEventStore store = new OpenGaussChatEventStore(
                mapper,
                new ObjectMapper(),
                (bizType, context) -> "event_1"
        );

        ChatEvent appended = store.appendWithExecutionGuard(
                new MessageDeltaEvent("run1", "session1", 0L,
                        Instant.parse("2026-05-16T00:00:00Z"), "hello", Map.of("delta", "hello")),
                new RunExecutionClaim("run1", "instance-1", 7L));

        assertThat(appended.sequence()).isEqualTo(42L);
        assertThat(mapper.guardOwnerInstanceId).isEqualTo("instance-1");
        assertThat(mapper.guardFencingToken).isEqualTo(7L);
    }

    @Test
    void appendWithExecutionGuardRejectsWhenDatabaseConditionDoesNotMatch() {
        ReturningEventMapper mapper = new ReturningEventMapper();
        mapper.guardInsertResult = 0;
        OpenGaussChatEventStore store = new OpenGaussChatEventStore(
                mapper,
                new ObjectMapper(),
                (bizType, context) -> "event_1"
        );

        assertThatThrownBy(() -> store.appendWithExecutionGuard(
                MessageDeltaEvent.of("run1", "session1", "hello"),
                new RunExecutionClaim("run1", "instance-1", 7L)))
                .isInstanceOf(ChatEventAppendRejectedException.class);
    }

    private static class ReturningEventMapper implements ChatEventMapper {
        private int guardInsertResult = 1;
        private String guardOwnerInstanceId;
        private long guardFencingToken;

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
            return 1;
        }

        @Override
        public int insertFromSessionWithExecutionGuard(String id, String sessionId, String runId, long seq,
                                                       String eventType, String payloadJson, Instant createdAt,
                                                       String ownerInstanceId, long fencingToken) {
            guardOwnerInstanceId = ownerInstanceId;
            guardFencingToken = fencingToken;
            return guardInsertResult;
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
