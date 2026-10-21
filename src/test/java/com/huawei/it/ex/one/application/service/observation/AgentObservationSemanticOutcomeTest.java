/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.observation;

import static com.huawei.it.ex.one.application.service.observation.ObservationTestFixture.await;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceMetadata;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.observation.AgentObservationSnapshot;

import reactor.core.publisher.SignalType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

class AgentObservationSemanticOutcomeTest {
    @ParameterizedTest
    @MethodSource("semanticFrames")
    void classifiesSemanticFramesWithoutChangingTheirPayload(
            String agentType, ChatEvent event, String expected) {
        try (var f = new ObservationTestFixture()) {
            f.start("run-a");
            var scope = begin(f, agentType);
            var original = new HashMap<>(event.payload());
            scope.event(event);
            assertThat(f.turns.context("run-a").refusal()).isEqualTo("REJECTED".equals(expected));
            assertThat(finish(f, scope, SignalType.ON_COMPLETE).outcome()).isEqualTo(expected);
            assertThat(event.payload()).isEqualTo(original);
            f.turns.finishStart("run-a");
        }
    }

    private static Stream<Arguments> semanticFrames() {
        return Stream.of(
                Arguments.of("DOMAIN_AGENT", event("runtime.metadata", Map.of("sourceType", "agent.refusal")), "REJECTED"),
                Arguments.of("DOMAIN_AGENT", event("runtime.metadata", Map.of("type", "agent.refusal")), "REJECTED"),
                Arguments.of("RELAY", event("runtime.metadata", Map.of("sourceType", "agent.refusal")), "REJECTED"),
                Arguments.of("RELAY", event("runtime.metadata", Map.of("sourceType", "expert-rejection")), "REJECTED"),
                Arguments.of("RELAY", event("runtime.metadata", Map.of("sourceType", "expert_rejection")), "REJECTED"),
                Arguments.of("DOMAIN_AGENT", event("runtime.metadata", Map.of("sourceType", "expert-rejection")), "STREAM_ENDED"),
                Arguments.of("DOMAIN_AGENT", event("runtime.metadata", Map.of("sourceType", "expert_rejection")), "STREAM_ENDED"),
                Arguments.of("relay", event("runtime.metadata", Map.of("sourceType", "expert-rejection")), "STREAM_ENDED"),
                Arguments.of("RELAY", event("runtime.metadata", Map.of("type", "expert-rejection")), "STREAM_ENDED"),
                Arguments.of("DOMAIN_AGENT", event("runtime.metadata", Map.of("sourceType", "AGENT.REFUSAL")), "STREAM_ENDED"),
                Arguments.of("DOMAIN_AGENT", event("runtime.card", Map.of("sourceType", "approval-request",
                        "operation_type", "Questionnaire")), "WAITING_USER"),
                Arguments.of("DOMAIN_AGENT", event("run.async_running", Map.of()), "ASYNC_ACCEPTED"),
                Arguments.of("DOMAIN_AGENT", event("runtime.metadata", Map.of("sourceType", "agent.async_started")), "ASYNC_ACCEPTED"),
                Arguments.of("DOMAIN_AGENT", event("message.completed", Map.of()), "COMPLETED"),
                Arguments.of("DOMAIN_AGENT", event("run.failed", Map.of()), "ERROR"),
                Arguments.of("DOMAIN_AGENT", event("runtime.metadata", Map.of("sourceType", "session-state",
                        "state", "failed")), "STREAM_ENDED"),
                Arguments.of("DOMAIN_AGENT", event("runtime.event", Map.of()), "STREAM_ENDED"));
    }

    @ParameterizedTest
    @CsvSource({"completed,COMPLETED", "failed,ERROR", "error,ERROR", "paused,CANCELLED", "cancelled,CANCELLED",
            "running,STREAM_ENDED", "COMPLETED,STREAM_ENDED", "' completed ',STREAM_ENDED", ",STREAM_ENDED"})
    void relayStateAliasesKeepUnknownStatesFromFallingThroughToTheEnvelope(String state, String expected) {
        try (var f = new ObservationTestFixture()) {
            f.start("run-a");
            for (String sourceType : List.of("session-state", "session_state")) {
                for (String eventType : List.of("message.completed", "run.failed")) {
                    var payload = new HashMap<String, Object>();
                    payload.put("sourceType", sourceType);
                    payload.put("state", state);
                    var scope = begin(f, "RELAY");
                    scope.event(event(eventType, payload));
                    assertThat(finish(f, scope, SignalType.ON_COMPLETE).outcome()).isEqualTo(expected);
                }
            }
            f.turns.finishStart("run-a");
        }
    }

    @ParameterizedTest
    @MethodSource("combinedFrames")
    void sameFrameKeepsTheOriginalBranchPriority(ChatEvent event, String expected) {
        try (var f = new ObservationTestFixture()) {
            f.start("run-a");
            for (SignalType signal : List.of(SignalType.ON_COMPLETE, SignalType.CANCEL, SignalType.ON_ERROR)) {
                var scope = begin(f, "RELAY");
                scope.event(event);
                scope.error(new IllegalStateException("transport ended"));
                assertThat(finish(f, scope, signal).outcome()).isEqualTo(expected);
            }
            f.turns.finishStart("run-a");
        }
    }

    private static Stream<Arguments> combinedFrames() {
        return Stream.of(
                Arguments.of(event("run.async_running", Map.of("type", "agent.refusal",
                        "sourceType", "approval-request", "operation_type", "questionnaire")), "REJECTED"),
                Arguments.of(event("run.async_running", Map.of("sourceType", "expert-rejection")), "REJECTED"),
                Arguments.of(event("message.completed", Map.of("type", "agent.refusal",
                        "sourceType", "agent.async_started")), "REJECTED"),
                Arguments.of(event("run.async_running", Map.of("sourceType", "approval-request",
                        "operation_type", "questionnaire")), "WAITING_USER"),
                Arguments.of(event("message.completed", Map.of("sourceType", "agent.async_started")), "ASYNC_ACCEPTED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "ERROR", "REJECTED", "WAITING_USER", "ASYNC_ACCEPTED"})
    void terminalFallbackDoesNotOverwriteAnExistingOutcome(String previous) {
        try (var f = new ObservationTestFixture()) {
            f.start("run-a");
            var scope = begin(f, "RELAY");
            scope.outcome(previous);
            scope.event(event("runtime.metadata", Map.of("sourceType", "session-state", "state", "failed")));
            scope.event(event("message.completed", Map.of()));
            scope.event(event("run.failed", Map.of()));
            assertThat(finish(f, scope, SignalType.CANCEL).outcome()).isEqualTo(previous);
            f.turns.finishStart("run-a");
        }
    }

    @Test
    void semanticEventsCanStillUpdateAnExistingOutcomeInArrivalOrder() {
        try (var f = new ObservationTestFixture()) {
            f.start("run-a");
            var scope = begin(f, "RELAY");
            scope.outcome("COMPLETED");
            scope.event(event("runtime.metadata", Map.of("sourceType", "agent.refusal", "reason", "out of scope")));
            scope.event(event("runtime.card", Map.of("sourceType", "approval-request", "operation_type", "questionnaire")));
            scope.event(event("run.async_running", Map.of()));
            var ended = finish(f, scope, SignalType.CANCEL);
            assertThat(ended.outcome()).isEqualTo("ASYNC_ACCEPTED");
            assertThat(ended.rejectMessage()).isEqualTo("out of scope");
            assertThat(f.turns.context("run-a").refusal()).isTrue();
            f.turns.finishStart("run-a");
        }
    }

    @Test
    void refusalKeepsSummaryFallbackCodeBoundsAndTheLatestRetentionPolicy() {
        try (var f = new ObservationTestFixture()) {
            var run = f.start("run-a");
            var scope = begin(f, "DOMAIN_AGENT");
            scope.event(event("runtime.metadata", Map.of("type", "agent.refusal", "reason", "  ",
                    "userMessage", "safe fallback", "code", "C".repeat(128), "reasonCode", "R".repeat(129),
                    "recoverable", "true", "runtimeSessionId", "new-downstream-session")));
            var ended = finish(f, scope, SignalType.CANCEL);
            assertThat(ended.rejectMessage()).isEqualTo("safe fallback");
            assertThat(ended.rejectCode()).isEqualTo("C".repeat(128));
            assertThat(ended.rejectReasonCode()).isNull();
            assertThat(ended.rejectRecoverable()).isNull();
            assertThat(ended.runtimeSessionId()).isEqualTo("new-downstream-session");

            var noStore = begin(f, "DOMAIN_AGENT");
            f.turns.decorate(run.withMetadataSnapshot(AgentDataPersistenceMetadata.runMetadata(
                    AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER, "not retained")));
            noStore.event(event("runtime.metadata", Map.of("sourceType", "agent.refusal",
                    "reason", "must not retain", "userMessage", "must not fall back", "recoverable", false)));
            var hidden = finish(f, noStore, SignalType.CANCEL);
            assertThat(hidden.rejectMessage()).isNull();
            assertThat(hidden.rejectRecoverable()).isFalse();
            assertThat(f.turns.context("run-a").refusal()).isTrue();
            f.turns.finishStart("run-a");
        }
    }

    private AgentObservationRecorder.Scope begin(ObservationTestFixture f, String agentType) {
        return f.recorder.begin(f.turns.context("run-a"), new AgentObservationRecorder.Target(
                agentType, null, "QUERY", "skill-a", "downstream-session"));
    }

    private AgentObservationSnapshot finish(
            ObservationTestFixture f, AgentObservationRecorder.Scope scope, SignalType signal) {
        scope.finish(signal);
        await(() -> f.calls.stream().anyMatch(call -> scope.id().equals(call.observationId())
                && "FINISHED".equals(call.status())));
        return f.calls.stream().filter(call -> scope.id().equals(call.observationId())
                && "FINISHED".equals(call.status())).findFirst().orElseThrow();
    }

    private static ChatEvent event(String type, Map<String, Object> payload) {
        return new RuntimeEvent("run-a", "session", 0L, Instant.EPOCH, type, payload);
    }
}
