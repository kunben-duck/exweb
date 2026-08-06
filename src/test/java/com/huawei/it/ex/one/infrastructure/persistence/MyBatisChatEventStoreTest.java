package com.huawei.it.ex.one.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.SequencedChatEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class MyBatisChatEventStoreTest {
    @Test
    void appendUsesDatabaseSequenceAsRecoveryCursor() {
        ChatEventMapper mapper = new ReturningEventMapper();
        MyBatisChatEventStore store = new MyBatisChatEventStore(
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
        MyBatisChatEventStore store = new MyBatisChatEventStore(
                mapper,
                new ObjectMapper(),
                (bizType, context) -> "event_1"
        );

        ChatEvent appended = store.appendWithExecutionGuard(
                new MessageDeltaEvent("run1", "session1", 0L,
                        Instant.parse("2026-05-16T00:00:00Z"), "hello", Map.of("delta", "hello")),
                new RunExecutionClaim("run1", "instance-1", 7L));

        assertThat(appended.sequence()).isEqualTo(42L);
        assertThat(mapper.lockOwnerInstanceId).isEqualTo("instance-1");
        assertThat(mapper.lockFencingToken).isEqualTo(7L);
        assertThat(mapper.singleRow.tenantId()).isEqualTo("tenant1");
        assertThat(mapper.singleRow.userId()).isEqualTo("user1");
    }

    @Test
    void appendWithExecutionGuardRejectsBeforeAllocatingSequenceWhenRunGateDoesNotMatch() {
        ReturningEventMapper mapper = new ReturningEventMapper();
        mapper.lockResult = null;
        MyBatisChatEventStore store = new MyBatisChatEventStore(
                mapper,
                new ObjectMapper(),
                (bizType, context) -> "event_1"
        );

        assertThatThrownBy(() -> store.appendWithExecutionGuard(
                MessageDeltaEvent.of("run1", "session1", "hello"),
                new RunExecutionClaim("run1", "instance-1", 7L)))
                .isInstanceOf(ChatEventAppendRejectedException.class)
                .hasMessageContaining("行栅栏拒绝");
        assertThat(mapper.sequenceCalls).isZero();
    }

    @Test
    void appendWithExecutionGuardConvertsNowaitConflictToRejectedEvent() {
        ReturningEventMapper mapper = new ReturningEventMapper();
        mapper.lockFailure = new CannotAcquireLockException("could not obtain lock");
        MyBatisChatEventStore store = new MyBatisChatEventStore(
                mapper,
                new ObjectMapper(),
                (bizType, context) -> "event_1"
        );

        assertThatThrownBy(() -> store.appendWithExecutionGuard(
                MessageDeltaEvent.of("run1", "session1", "hello"),
                new RunExecutionClaim("run1", "instance-1", 7L)))
                .isInstanceOf(ChatEventAppendRejectedException.class)
                .hasMessageContaining("run 行锁竞争");
        assertThat(mapper.sequenceCalls).isZero();
    }

    @Test
    void appendWithExecutionGuardRejectsWhenDatabaseConditionDoesNotMatch() {
        ReturningEventMapper mapper = new ReturningEventMapper();
        mapper.guardInsertResult = 0;
        MyBatisChatEventStore store = new MyBatisChatEventStore(
                mapper,
                new ObjectMapper(),
                (bizType, context) -> "event_1"
        );

        assertThatThrownBy(() -> store.appendWithExecutionGuard(
                MessageDeltaEvent.of("run1", "session1", "hello"),
                new RunExecutionClaim("run1", "instance-1", 7L)))
                .isInstanceOf(ChatEventAppendRejectedException.class);
    }

    @Test
    void appendBatchWithExecutionGuardUsesOneGateSequenceAllocationAndInsert() {
        ReturningEventMapper mapper = new ReturningEventMapper();
        MyBatisChatEventStore store = new MyBatisChatEventStore(
                mapper,
                new ObjectMapper(),
                new com.huawei.it.ex.one.application.integration.id.IdGenerator() {
                    private int value;

                    @Override
                    public String newId(String bizType,
                                        com.huawei.it.ex.one.application.integration.id.IdGenerateContext context) {
                        return "event_" + ++value;
                    }
                }
        );

        List<ChatEvent> appended = store.appendBatchWithExecutionGuard(List.of(
                        MessageDeltaEvent.of("run1", "session1", "a"),
                        MessageDeltaEvent.of("run1", "session1", "b"),
                        MessageDeltaEvent.of("run1", "session1", "c")),
                new RunExecutionClaim("run1", "instance-1", 7L));

        assertThat(appended).extracting(ChatEvent::sequence).containsExactly(42L, 43L, 44L);
        assertThat(mapper.lockCalls).isEqualTo(1);
        assertThat(mapper.batchSequenceCalls).isEqualTo(1);
        assertThat(mapper.batchInsertCalls).isEqualTo(1);
        assertThat(mapper.batchRows).extracting(ChatEventWriteRow::eventType)
                .containsExactly("message.delta", "message.delta", "message.delta");
    }

    @Test
    void appendBatchWithExecutionGuardRejectsWholeBatchWhenGuardedInsertDoesNotMatch() {
        ReturningEventMapper mapper = new ReturningEventMapper();
        mapper.guardInsertResult = 0;
        java.util.concurrent.atomic.AtomicInteger eventIds = new java.util.concurrent.atomic.AtomicInteger();
        MyBatisChatEventStore store = new MyBatisChatEventStore(
                mapper,
                new ObjectMapper(),
                (bizType, context) -> "event_" + eventIds.incrementAndGet()
        );

        assertThatThrownBy(() -> store.appendBatchWithExecutionGuard(List.of(
                        MessageDeltaEvent.of("run1", "session1", "a"),
                        MessageDeltaEvent.of("run1", "session1", "b")),
                new RunExecutionClaim("run1", "instance-1", 7L)))
                .isInstanceOf(ChatEventAppendRejectedException.class)
                .hasMessageContaining("批量写入被 execution guard 拒绝");
        assertThat(mapper.batchInsertCalls).isEqualTo(1);
    }

    @Test
    void sequenceLiveBatchUsesGuardAndSequenceWithoutInsertingOrCreatingEventIds() {
        ReturningEventMapper mapper = new ReturningEventMapper();
        java.util.concurrent.atomic.AtomicInteger generatedIds = new java.util.concurrent.atomic.AtomicInteger();
        MyBatisChatEventStore store = new MyBatisChatEventStore(
                mapper,
                new ObjectMapper(),
                (bizType, context) -> {
                    generatedIds.incrementAndGet();
                    return "unexpected";
                }
        );

        List<ChatEvent> sequenced = store.sequenceLiveBatchWithExecutionGuard(List.of(
                        MessageDeltaEvent.of("run1", "session1", "a"),
                        MessageDeltaEvent.of("run1", "session1", "b")),
                new RunExecutionClaim("run1", "instance-1", 7L));

        assertThat(sequenced).allSatisfy(event -> assertThat(event)
                .isInstanceOf(SequencedChatEvent.class));
        assertThat(sequenced).extracting(ChatEvent::sequence).containsExactly(42L, 43L);
        assertThat(mapper.lockCalls).isEqualTo(1);
        assertThat(mapper.lockOwnerInstanceId).isEqualTo("instance-1");
        assertThat(mapper.lockFencingToken).isEqualTo(7L);
        assertThat(mapper.batchSequenceCalls).isEqualTo(1);
        assertThat(mapper.batchInsertCalls).isZero();
        assertThat(mapper.singleRow).isNull();
        assertThat(generatedIds).hasValue(0);
    }

    @Test
    void sequenceLiveBatchRejectsBeforeAllocatingSequenceWhenRunGateDoesNotMatch() {
        ReturningEventMapper mapper = new ReturningEventMapper();
        mapper.lockResult = null;
        MyBatisChatEventStore store = new MyBatisChatEventStore(
                mapper,
                new ObjectMapper(),
                (bizType, context) -> "event_1"
        );

        assertThatThrownBy(() -> store.sequenceLiveBatchWithExecutionGuard(
                List.of(MessageDeltaEvent.of("run1", "session1", "a")),
                new RunExecutionClaim("run1", "instance-1", 7L)))
                .isInstanceOf(ChatEventAppendRejectedException.class)
                .hasMessageContaining("行栅栏拒绝");
        assertThat(mapper.batchSequenceCalls).isZero();
        assertThat(mapper.batchInsertCalls).isZero();
    }

    private static class ReturningEventMapper implements ChatEventMapper {
        private int guardInsertResult = 1;
        private ChatEventAppendContextRow lockResult =
                new ChatEventAppendContextRow("tenant1", "user1", "session1", "run1");
        private RuntimeException lockFailure;
        private int sequenceCalls;
        private int batchSequenceCalls;
        private int batchInsertCalls;
        private int lockCalls;
        private List<ChatEventWriteRow> batchRows = List.of();
        private ChatEventWriteRow singleRow;
        private String lockOwnerInstanceId;
        private long lockFencingToken;

        @Override
        public Long nextSeq() {
            sequenceCalls++;
            return 42L;
        }

        @Override
        public List<Long> nextSeqs(int count) {
            batchSequenceCalls++;
            return java.util.stream.LongStream.range(42L, 42L + count).boxed().toList();
        }

        @Override
        public ChatEventAppendContextRow findEventAppendContext(String sessionId, String runId) {
            return new ChatEventAppendContextRow("tenant1", "user1", sessionId, runId);
        }

        @Override
        public ChatEventAppendContextRow lockRunForEventAppend(String sessionId, String runId,
                                                               String ownerInstanceId, long fencingToken) {
            lockCalls++;
            if (lockFailure != null) {
                throw lockFailure;
            }
            lockOwnerInstanceId = ownerInstanceId;
            lockFencingToken = fencingToken;
            return lockResult;
        }

        @Override
        public int insert(ChatEventWriteRow row) {
            singleRow = row;
            assertThat(row.id()).isEqualTo("event_1");
            assertThat(row.tenantId()).isEqualTo("tenant1");
            assertThat(row.userId()).isEqualTo("user1");
            assertThat(row.sessionId()).isEqualTo("session1");
            assertThat(row.runId()).isEqualTo("run1");
            assertThat(row.seq()).isEqualTo(42L);
            assertThat(row.eventType()).isEqualTo("message.delta");
            return guardInsertResult;
        }

        @Override
        public int insertBatch(List<ChatEventWriteRow> rows) {
            batchInsertCalls++;
            batchRows = List.copyOf(rows);
            return guardInsertResult == 0 ? 0 : rows.size();
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
