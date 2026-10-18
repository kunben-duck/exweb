/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.observation;

import static com.huawei.it.ex.one.application.service.observation.ObservationTestFixture.await;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.MessageCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.observation.ObservationMetadata;

import reactor.core.publisher.SignalType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

class TurnObservationTest {
    private final AgentObservationRecorder.Target domain = new AgentObservationRecorder.Target(
            "DOMAIN_AGENT", null, "QUERY", "skill-a", "real-downstream-session");

    @Test
    void disabledCreatesNeitherContextNorHeadersNorWritesAndDoesNotClassify() {
        try (var f = new ObservationTestFixture(false, event -> { throw new AssertionError("disabled classifier"); })) {
            f.start("run-a");
            var scope = f.recorder.begin(f.turns.context("run-a"), domain);
            HttpHeaders headers = new HttpHeaders();
            headers.set("legacy", "untouched");
            scope.headers(headers);
            scope.event(RuntimeEvent.card("run-a", "session", Map.of("cardType", "test")));
            scope.finish(SignalType.ON_COMPLETE);
            f.turns.finishStart("run-a");
            assertThat(f.turns.context("run-a")).isNull();
            assertThat(headers).containsOnlyKeys("legacy");
            assertThat(f.calls).isEmpty();
            assertThat(f.traces).isEmpty();
            assertThat(f.writer.queueSize()).isZero();
        }
    }

    @Test
    void rejectionWinsOverCancellationAndDoesNotCountAsFirstBusinessFrame() {
        try (var f = new ObservationTestFixture()) {
            ChatRun run = f.start("run-a");
            var scope = f.recorder.begin(f.turns.context("run-a"), domain);
            f.advance(Duration.ofMillis(40));
            scope.event(RuntimeEvent.metadata("run-a", "session", Map.of("sourceType", "agent.refusal",
                    "code", "OUT_OF_SCOPE", "reasonCode", "SKILL", "recoverable", true,
                    "reason", "token=secret " + "字".repeat(1050))));
            scope.finish(SignalType.CANCEL);
            f.turns.committed(f.turns.decorate(run.completed(9)), null);
            await(() -> f.calls.stream().anyMatch(c -> "FINISHED".equals(c.status())));
            var ended = f.calls.stream().filter(c -> "FINISHED".equals(c.status())).findFirst().orElseThrow();
            assertThat(ended.outcome()).isEqualTo("REJECTED");
            assertThat(ended.firstBusinessFrameAt()).isNull();
            assertThat(ended.durationMs()).isEqualTo(40L);
            assertThat(ended.rejectMessage()).doesNotContain("secret");
            assertThat(ended.rejectMessage()).isEqualTo("[REDACTED]");
            await(() -> f.traces.stream().anyMatch(t -> "COMPLETED".equals(t.status())));
            assertThat(f.traces.getLast().isRefusal()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"questionnaire", "QUESTIONNAIRE", "Questionnaire"})
    void questionnaireCaseVariantsKeepWaitingOutcomeAcrossCompletionAndCancellation(String operationType) {
        try (var f = new ObservationTestFixture()) {
            f.start("run-a");
            var target = new AgentObservationRecorder.Target(
                    "RELAY", "DOMAIN_EXPERT", "QUERY", "expert-a", "real-downstream-session");
            for (SignalType signal : List.of(SignalType.ON_COMPLETE, SignalType.CANCEL)) {
                for (boolean messageCompleted : List.of(false, true)) {
                    var scope = f.recorder.begin(f.turns.context("run-a"), target);
                    scope.event(RuntimeEvent.card("run-a", "session", Map.of(
                            "sourceType", "approval-request", "operation_type", operationType,
                            "approval_id", "approval-1")));
                    if (messageCompleted) {
                        scope.event(MessageCompletedEvent.of("run-a", "session"));
                    }
                    scope.finish(signal);
                }
            }
            await(() -> f.calls.stream().filter(c -> "FINISHED".equals(c.status())).count() == 4);
            assertThat(f.calls.stream().filter(c -> "FINISHED".equals(c.status())))
                    .allSatisfy(call -> assertThat(call.outcome()).isEqualTo("WAITING_USER"));
            f.turns.finishStart("run-a");
        }
    }

    @ParameterizedTest
    @MethodSource("nonQuestionnairePayloads")
    void otherApprovalPayloadsDoNotBecomeWaitingObservations(Map<String, Object> payload) {
        try (var f = new ObservationTestFixture()) {
            f.start("run-a");
            var scope = f.recorder.begin(f.turns.context("run-a"), domain);
            scope.event(RuntimeEvent.card("run-a", "session", payload));
            scope.finish(SignalType.ON_COMPLETE);
            await(() -> f.calls.stream().anyMatch(c -> "FINISHED".equals(c.status())));
            assertThat(f.calls.stream().filter(c -> "FINISHED".equals(c.status())))
                    .singleElement().extracting(c -> c.outcome()).isEqualTo("STREAM_ENDED");
            f.turns.finishStart("run-a");
        }
    }

    private static Stream<Map<String, Object>> nonQuestionnairePayloads() {
        Map<String, Object> nullOperation = new HashMap<>();
        nullOperation.put("sourceType", "approval-request");
        nullOperation.put("operation_type", null);
        return Stream.of(
                Map.of("sourceType", "approval-request"),
                nullOperation,
                Map.of("sourceType", "approval-request", "operation_type", ""),
                Map.of("sourceType", "approval-request", "operation_type", " "),
                Map.of("sourceType", "approval-request", "operation_type", " questionnaire "),
                Map.of("sourceType", "approval-request", "operation_type", "approval"),
                Map.of("sourceType", "approval-request", "operation_type", true),
                Map.of("sourceType", "approval-request", "operation_type", 1),
                Map.of("sourceType", "approval-response", "operation_type", "questionnaire"),
                Map.of("sourceType", "APPROVAL-REQUEST", "operation_type", "questionnaire"),
                Map.of("operation_type", "questionnaire"));
    }

    @Test
    void continuationKeepsRootAndFirstFrameAcrossWaitAndUsesMonotonicClockLocally() {
        try (var f = new ObservationTestFixture()) {
            ChatRun run = f.start("run-a");
            var scope = f.recorder.begin(f.turns.context("run-a"), domain);
            f.advance(Duration.ofMillis(20));
            var card = RuntimeEvent.card("run-a", "session", Map.of("sourceType", "approval-request",
                    "operation_type", "questionnaire", "approval_id", "q"));
            scope.event(card);
            scope.finish(SignalType.CANCEL);
            Map<String, Object> payload = f.turns.interactionMetadata("run-a", Map.of("approval_id", "q"));
            f.turns.committed(f.turns.decorate(run.waitingUser(2)), null);
            String traceId = f.turns.context("run-a").traceId();
            f.turns.finishStart("run-a");
            assertThat(f.turns.context("run-a")).isNull();
            f.advance(Duration.ofSeconds(10));
            // 本机墙钟回拨不能改变耗时；另一实例将改用可信UTC起点。
            f.clock.value = f.clock.value.minusSeconds(100);
            f.turns.prepareContinuation("run-b");
            f.turns.inherit(interaction(payload), "run-b");
            ChatRun next = f.turns.decorate(f.run("run-b", "clarification-user-2", Map.of()));
            f.turns.committed(next, null);
            var inherited = f.turns.context("run-b");
            assertThat(inherited.traceId()).isEqualTo(traceId);
            assertThat(inherited.rootUserMessageId()).isEqualTo("user-1");
            assertThat(inherited.userMessageId()).isEqualTo("clarification-user-2");
            assertThat(inherited.runOrdinal()).isEqualTo(2);
            assertThat(inherited.parentObservationId()).isEqualTo(scope.id());
            assertThat(inherited.firstBusinessFrameLatencyMs()).isEqualTo(20);
            f.advance(Duration.ofMillis(30));
            var nextCall = f.recorder.begin(inherited, domain);
            nextCall.event(RuntimeEvent.card("run-b", "session", Map.of("cardType", "result")));
            nextCall.finish(SignalType.ON_COMPLETE);
            f.turns.committed(f.turns.decorate(next.completed(6)), null);
            await(() -> f.traces.stream().anyMatch(t -> "COMPLETED".equals(t.status())));
            assertThat(f.traces.getLast().durationMs()).isEqualTo(10050);
            assertThat(f.traces.getLast().firstBusinessFrameLatencyMs()).isEqualTo(20);
        }
    }

    @Test
    void crossInstanceRestoresTrustedContextAndUtcButRejectsForeignOwner() {
        try (var first = new ObservationTestFixture(); var second = new ObservationTestFixture()) {
            ChatRun original = first.start("run-a");
            second.advance(Duration.ofSeconds(30));
            var restored = second.turns.restore(original, false);
            assertThat(restored.traceId()).isEqualTo(first.turns.context("run-a").traceId());
            second.turns.committed(original.completed(10), null);
            await(() -> !second.traces.isEmpty());
            assertThat(second.traces.getLast().durationMs()).isEqualTo(30000);
            assertThat(ObservationMetadata.read(original.metadata(), "other", "u")).isNull();
            assertThat(ObservationMetadata.read(original.metadata(), "t", "other")).isNull();
            assertThat(ObservationMetadata.remove(original.metadata())).doesNotContainKey(ObservationMetadata.KEY);
        }
    }

    @Test
    void rollbackAndFailedContinuationDoNotPublishSuccessOrTakeOverWaitingTrace() {
        try (var f = new ObservationTestFixture()) {
            ChatRun run = f.start("run-a");
            var payload = f.turns.interactionMetadata("run-a", Map.of());
            f.turns.committed(f.turns.decorate(run.waitingUser(2)), null);
            f.turns.finishStart("run-a");
            f.turns.prepareContinuation("run-b");
            f.turns.inherit(interaction(payload), "run-b");
            new TransactionTemplate(f.transactions).executeWithoutResult(tx -> {
                var next = f.turns.decorate(f.run("run-b", "user-2", Map.of()));
                f.turns.committed(next, null);
                tx.setRollbackOnly();
            });
            f.turns.finishStart("run-b");
            await(() -> f.traces.size() >= 3);
            assertThat(f.traces).noneMatch(t -> "run-b".equals(t.currentRunId()));
            assertThat(f.traces.getLast().status()).isEqualTo("WAITING_USER");
            assertThat(f.turns.context("run-b")).isNull();
        }
    }

    @Test
    void rolledBackRootAdmissionRemainsOrdinalZeroWithoutUncommittedUserMessage() {
        try (var f = new ObservationTestFixture()) {
            f.turns.begin(new UserContext("t", "u", "u"), "run-new", "NEXT", "session");
            new TransactionTemplate(f.transactions).executeWithoutResult(tx -> {
                f.turns.committed(f.turns.decorate(f.run("run-new", "uncommitted-user", Map.of())), null);
                tx.setRollbackOnly();
            });
            f.turns.finishStart("run-new");
            await(() -> f.traces.size() == 2);
            assertThat(f.traces.getLast().currentRunOrdinal()).isZero();
            assertThat(f.traces.getLast().rootUserMessageId()).isNull();
            assertThat(f.traces.getLast().status()).isEqualTo("FAILED");
        }
    }

    @Test
    void rejectSummaryIsUnicodeBoundedAndCredentialBearingTextIsDiscarded() {
        try (var f = new ObservationTestFixture()) {
            f.start("run-a");
            var first = f.recorder.begin(f.turns.context("run-a"), domain);
            first.event(RuntimeEvent.metadata("run-a", "session",
                    Map.of("sourceType", "agent.refusal", "reason", "😀".repeat(1100))));
            first.finish(SignalType.CANCEL);
            var second = f.recorder.begin(f.turns.context("run-a"), domain);
            second.event(RuntimeEvent.metadata("run-a", "session",
                    Map.of("sourceType", "agent.refusal", "reason", "Cookie: a=secret; b=another secret")));
            second.finish(SignalType.CANCEL);
            await(() -> f.calls.size() == 4);
            assertThat(f.calls.get(1).rejectMessage()).isEqualTo("😀".repeat(1024));
            assertThat(f.calls.getLast().rejectMessage()).isEqualTo("[REDACTED]");
        }
    }

    @Test
    void noStoreOmitsRejectTextAndRetriesHaveNewIds() {
        try (var f = new ObservationTestFixture()) {
            f.start("run-a");
            var context = f.turns.context("run-a").withRetention(false);
            // 独立recorder没有本机状态覆盖，模拟从持久化no-store上下文恢复。
            try (var other = new ObservationTestFixture()) {
                var first = other.recorder.begin(context, domain);
                first.event(RuntimeEvent.metadata("run-a", "session", Map.of("sourceType", "agent.refusal",
                        "reason", "must-not-store")));
                first.finish(SignalType.CANCEL);
                var second = other.recorder.begin(context, domain, first);
                second.error(new java.util.concurrent.TimeoutException());
                second.finish(SignalType.ON_ERROR);
                await(() -> other.calls.size() == 4);
                assertThat(other.calls.get(1).rejectMessage()).isNull();
                var end = other.calls.getLast();
                assertThat(end.outcome()).isEqualTo("TIMEOUT");
                assertThat(end.attemptNo()).isEqualTo(2);
                assertThat(end.retryOfObservationId()).isEqualTo(first.id());
                assertThat(end.observationId()).isNotEqualTo(first.id());
            }
        }
    }

    @Test
    void classifierFailureDoesNotLoseRefusalAndIntentResultDoesNotSetTraceFirstFrame() {
        try (var f = new ObservationTestFixture(true, event -> { throw new IllegalStateException("classifier"); })) {
            f.start("run-a");
            var intent = f.recorder.begin(f.turns.context("run-a"),
                    new AgentObservationRecorder.Target("INTENT", null, "RECOGNIZE", null, null));
            intent.intentResult();
            intent.outcome("COMPLETED");
            intent.finish(SignalType.ON_COMPLETE);
            assertThat(f.turns.context("run-a").firstBusinessFrameAt()).isNull();
            var agent = f.recorder.begin(f.turns.context("run-a"), domain);
            agent.event(RuntimeEvent.metadata("run-a", "session", Map.of("sourceType", "agent.refusal")));
            agent.finish(SignalType.CANCEL);
            await(() -> f.calls.size() == 4);
            assertThat(f.calls.getLast().outcome()).isEqualTo("REJECTED");
            assertThat(f.turns.context("run-a").refusal()).isTrue();
        }
    }

    @Test
    void lateSnapshotsAreFiniteAndLegacyStopDoesNotInventAsyncAssociation() {
        try (var f = new ObservationTestFixture()) {
            ChatRun run = f.start("run-a");
            for (int i = 0; i < 100; i++) { f.turns.committed(run, null); }
            f.turns.committed(f.turns.decorate(run.completed(3)), null);
            await(() -> f.traces.size() == 3);
            f.turns.finishStart("run-a");
            assertThat(f.turns.context("run-a")).isNull();
            var legacy = f.run("legacy", "old-user", Map.of());
            assertThat(f.turns.restore(legacy, false)).isNull();
            assertThat(f.turns.restore(legacy, true).originType()).isEqualTo("LEGACY_STOP");
            assertThat(f.turns.context("legacy")).isNull();
            try (var scope = f.turns.openScope(legacy, true)) {
                assertThat(f.turns.context("legacy").rootUserMessageId()).isEqualTo("old-user");
                assertThat(f.turns.restore(legacy, true)).isEqualTo(f.turns.context("legacy"));
            }
            assertThat(f.turns.context("legacy")).isNull();
        }
    }

    private ChatInteractionRequest interaction(Map<String, Object> payload) {
        return new ChatInteractionRequest("interaction", "t", "u", "session", "run-a", null, "user-1",
                "assistant", "domain-agent", "binding", "real-downstream-session", "approval",
                ChatInteractionType.AGENT_CLARIFICATION, ChatInteractionStatus.WAITING,
                payload, Map.of(), null, null, null, null, null);
    }
}
