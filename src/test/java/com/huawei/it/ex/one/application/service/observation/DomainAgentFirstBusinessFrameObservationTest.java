/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.observation;

import static com.huawei.it.ex.one.application.service.observation.ObservationTestFixture.await;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.integration.observation.FirstBusinessFrameClassifier.Classification;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.SignalType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

class DomainAgentFirstBusinessFrameObservationTest {
    private final DomainAgentResponseNormalizer normalizer = new DomainAgentResponseNormalizer(new ObjectMapper());
    private final AgentObservationRecorder.Target target = new AgentObservationRecorder.Target(
            "DOMAIN_AGENT", null, "QUERY", "skill", "downstream-session");
    private static final String EXCLUDED = """
            data: {"cardUrl":"web.js"}

            data: {"content":"<think>analysis</think>"}

            data: {"type":"tool","toolTitle":"search"}

            data: {"type":"heartbeat"}

            data: {"diyCardScene":{},"contentAgent":[]}

            """;

    @ParameterizedTest
    @CsvSource({"content,false", "content,true", "diyCardScene,false", "diyCardScene,true",
            "contentAgent,false", "contentAgent,true"})
    void firstValidOutputUsesIndependentClocksAndDoesNotReset(String field, boolean noStore) {
        try (var f = new ObservationTestFixture()) {
            ChatRun run = f.start("run-a");
            if (noStore) {
                run = f.turns.decorate(run.withMetadata(new AgentDataPersistenceState("hidden")
                        .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER).runMetadataOverlay()));
            }
            f.advance(Duration.ofMillis(20));
            var scope = f.recorder.begin(f.turns.context("run-a"), target);
            f.advance(Duration.ofMillis(30));
            emit(f, scope, EXCLUDED);
            assertThat(f.turns.context("run-a").firstBusinessFrameAt()).isNull();
            f.advance(Duration.ofMillis(70));
            emit(f, scope, "{\"" + field + "\":\"answer\"}");
            var first = f.turns.context("run-a");
            assertThat(first.firstBusinessFrameAt()).isEqualTo(f.clock.instant());
            assertThat(first.firstBusinessFrameLatencyMs()).isEqualTo(120);
            assertThat(first.retainText()).isEqualTo(!noStore);
            f.advance(Duration.ofMillis(80));
            emit(f, scope, "{\"content\":\"later answer\",\"endFlag\":true}");
            scope.finish(SignalType.ON_COMPLETE);
            f.turns.committed(f.turns.decorate(run.completed(9)), null);
            f.turns.finishStart("run-a");
            await(() -> f.calls.stream().anyMatch(call -> "FINISHED".equals(call.status())));
            await(() -> f.traces.stream().anyMatch(trace -> "COMPLETED".equals(trace.status())));
            assertThat(f.calls).filteredOn(call -> "FINISHED".equals(call.status())).singleElement()
                    .satisfies(call -> {
                        assertThat(call.firstBusinessFrameAt()).isEqualTo(first.firstBusinessFrameAt());
                        assertThat(call.firstBusinessFrameLatencyMs()).isEqualTo(100);
                        assertThat(call.outcome()).isEqualTo("COMPLETED");
                    });
            assertThat(f.traces).filteredOn(trace -> "COMPLETED".equals(trace.status())).singleElement()
                    .satisfies(trace -> {
                        assertThat(trace.firstBusinessFrameAt()).isEqualTo(first.firstBusinessFrameAt());
                        assertThat(trace.firstBusinessFrameLatencyMs()).isEqualTo(120);
                        assertThat(trace.durationMs()).isEqualTo(200);
                    });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void excludedOutputsNeverInventFirstFrameEvenAtCompletion(boolean noStore) {
        try (var f = new ObservationTestFixture()) {
            ChatRun run = f.start("run-a");
            if (noStore) {
                run = f.turns.decorate(run.withMetadata(new AgentDataPersistenceState("hidden")
                        .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER).runMetadataOverlay()));
            }
            var scope = f.recorder.begin(f.turns.context("run-a"), target);
            f.advance(Duration.ofSeconds(1));
            emit(f, scope, EXCLUDED + "data: {\"endFlag\":true}\n\n");
            scope.finish(SignalType.ON_COMPLETE);
            f.turns.committed(f.turns.decorate(run.completed(9)), null);
            f.turns.finishStart("run-a");
            await(() -> f.calls.stream().anyMatch(call -> "FINISHED".equals(call.status())));
            await(() -> f.traces.stream().anyMatch(trace -> "COMPLETED".equals(trace.status())));
            assertThat(f.calls).allSatisfy(call -> {
                assertThat(call.firstBusinessFrameAt()).isNull();
                assertThat(call.firstBusinessFrameLatencyMs()).isNull();
            });
            assertThat(f.traces).allSatisfy(trace -> {
                assertThat(trace.firstBusinessFrameAt()).isNull();
                assertThat(trace.firstBusinessFrameLatencyMs()).isNull();
            });
        }
    }

    @Test
    void intentKeepsItsOwnFirstFrameWithoutSettingTurnFirstFrame() {
        try (var f = new ObservationTestFixture()) {
            f.start("run-a");
            var scope = f.recorder.begin(f.turns.context("run-a"),
                    new AgentObservationRecorder.Target("INTENT", null, "RECOGNIZE", null, null));
            f.advance(Duration.ofMillis(15));
            scope.intentResult();
            scope.outcome("COMPLETED");
            scope.finish(SignalType.ON_COMPLETE);
            await(() -> f.calls.stream().anyMatch(call -> "FINISHED".equals(call.status())));
            assertThat(f.calls).filteredOn(call -> "FINISHED".equals(call.status())).singleElement()
                    .satisfies(call -> assertThat(call.firstBusinessFrameLatencyMs()).isEqualTo(15));
            assertThat(f.turns.context("run-a").firstBusinessFrameAt()).isNull();
            f.turns.finishStart("run-a");
        }
    }

    @Test
    void customClassifierStillControlsBothLevels() {
        try (var f = new ObservationTestFixture(true, event -> Classification.BUSINESS)) {
            f.start("run-a");
            var scope = f.recorder.begin(f.turns.context("run-a"), target);
            f.advance(Duration.ofMillis(15));
            emit(f, scope, "{\"cardUrl\":\"web.js\"}");
            scope.finish(SignalType.ON_COMPLETE);
            await(() -> f.calls.stream().anyMatch(call -> "FINISHED".equals(call.status())));
            assertThat(f.calls).filteredOn(call -> "FINISHED".equals(call.status())).singleElement()
                    .satisfies(call -> assertThat(call.firstBusinessFrameLatencyMs()).isEqualTo(15));
            assertThat(f.turns.context("run-a").firstBusinessFrameLatencyMs()).isEqualTo(15);
            f.turns.finishStart("run-a");
        }
    }

    private void emit(ObservationTestFixture f, AgentObservationRecorder.Scope scope, String frame) {
        normalizer.normalize("run-a", "session", frame).forEach(event -> {
            scope.event(event);
            f.turns.event(event);
        });
    }
}
