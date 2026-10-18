/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.observation;

import static com.huawei.it.ex.one.application.service.observation.ObservationTestFixture.await;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class TurnTraceContinuationLifecycleTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void endedContinuationCannotBeInitializedLater(boolean legacy) throws Exception {
        try (var f = new ObservationTestFixture()) {
            var interaction = interaction(sourcePayload(f, legacy));
            f.turns.prepareContinuation("run-b");
            f.turns.finishStart("run-b");
            f.turns.inherit(interaction, "run-b");
            assertEmpty(f.turns);
            assertThat(f.traces).noneMatch(trace -> "run-b".equals(trace.currentRunId()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationDuringContextReadPreventsLateRegistration(boolean legacy) throws Exception {
        try (var f = new ObservationTestFixture(); var executor = Executors.newSingleThreadExecutor()) {
            var payload = sourcePayload(f, legacy);
            var interaction = spy(interaction(payload));
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(interaction.requestPayload()).thenAnswer(invocation -> {
                reading.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return payload;
            });
            f.turns.prepareContinuation("run-b");
            var initialization = executor.submit(() -> f.turns.inherit(interaction, "run-b"));
            try {
                assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
                f.turns.finishStart("run-b");
                assertEmpty(f.turns);
            } finally {
                release.countDown();
            }
            initialization.get(5, TimeUnit.SECONDS);
            assertEmpty(f.turns);
        }
    }

    @Test
    void lateInitializationCannotConsumeReplacementStartRecord() throws Exception {
        try (var f = new ObservationTestFixture(); var executor = Executors.newSingleThreadExecutor()) {
            var payload = sourcePayload(f, false);
            var late = spy(interaction(payload));
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(late.requestPayload()).thenAnswer(invocation -> {
                reading.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return payload;
            });
            f.turns.prepareContinuation("run-b");
            var initialization = executor.submit(() -> f.turns.inherit(late, "run-b"));
            try {
                assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
                f.turns.finishStart("run-b");
                f.turns.prepareContinuation("run-b");
            } finally {
                release.countDown();
            }
            initialization.get(5, TimeUnit.SECONDS);
            assertThat(f.turns.context("run-b")).isNull();
            f.turns.inherit(interaction(payload), "run-b");
            assertThat(f.turns.context("run-b")).isNotNull();
            f.turns.finishStart("run-b");
            assertEmpty(f.turns);
        }
    }

    @Test
    void concurrentRegistrationAndFinishLeaveNoOrphanState() throws Exception {
        try (var f = new ObservationTestFixture(); var executor = Executors.newFixedThreadPool(2)) {
            var interaction = interaction(sourcePayload(f, false));
            for (int index = 0; index < 1000; index++) {
                String runId = "race-" + index;
                f.turns.prepareContinuation(runId);
                CountDownLatch start = new CountDownLatch(1);
                var initialization = executor.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    f.turns.inherit(interaction, runId);
                    return null;
                });
                var finish = executor.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    f.turns.finishStart(runId);
                    return null;
                });
                start.countDown();
                initialization.get(5, TimeUnit.SECONDS);
                finish.get(5, TimeUnit.SECONDS);
                assertEmpty(f.turns);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializationWinsOnlyOnceAndScopeOutlivesExecution(boolean legacy) throws Exception {
        try (var f = new ObservationTestFixture()) {
            var interaction = interaction(sourcePayload(f, legacy));
            f.turns.prepareContinuation("run-b");
            var startedAt = f.clock.instant();
            f.advance(Duration.ofSeconds(3));
            f.turns.inherit(interaction, "run-b");
            var initialized = f.turns.context("run-b");
            assertThat(initialized).isNotNull();
            if (legacy) {
                assertThat(initialized.originType()).isEqualTo("LEGACY_CONTINUATION");
                assertThat(initialized.sourceRunId()).isEqualTo("run-a");
                assertThat(initialized.startedAt()).isEqualTo(startedAt);
            }
            f.turns.sample("run-b", true, "obs-last");
            var sampled = f.turns.context("run-b");
            f.turns.inherit(interaction, "run-b");
            assertThat(f.turns.context("run-b")).isSameAs(sampled);
            var run = f.turns.decorate(f.run("run-b", "continued-user", Map.of()));
            f.turns.committed(run, null);
            try (var scope = f.turns.openScope(run, false)) {
                f.turns.finishStart("run-b");
                f.turns.finishStart("run-b");
                f.turns.inherit(interaction, "run-b");
                assertThat(f.turns.context("run-b").refusal()).isTrue();
                assertThat(f.turns.context("run-b").traceId()).isEqualTo(initialized.traceId());
            }
            assertEmpty(f.turns);
        }
    }

    @Test
    void thousandLateReturnsDoNotAccumulateEitherMap() throws Exception {
        try (var f = new ObservationTestFixture()) {
            var trusted = interaction(sourcePayload(f, false));
            var legacy = interaction(Map.of());
            for (int index = 0; index < 1000; index++) {
                String runId = "late-" + index;
                f.turns.prepareContinuation(runId);
                f.turns.finishStart(runId);
                f.turns.inherit(index % 2 == 0 ? trusted : legacy, runId);
            }
            assertEmpty(f.turns);
            assertThat(f.traces).noneMatch(trace -> trace.currentRunId().startsWith("late-"));
        }
    }

    @Test
    void missingPreparationAndDisabledObservationNeverRegister() throws Exception {
        try (var f = new ObservationTestFixture()) {
            f.turns.inherit(interaction(Map.of()), "unprepared");
            assertEmpty(f.turns);
        }
        var disabled = TurnTraceCoordinator.DISABLED;
        disabled.prepareContinuation("disabled");
        disabled.inherit(interaction(Map.of()), "disabled");
        disabled.finishStart("disabled");
        assertEmpty(disabled);
    }

    @Test
    void initializationAndWriterFailuresDoNotPreventCleanup() throws Exception {
        try (var f = new ObservationTestFixture()) {
            var malformed = spy(interaction(Map.of()));
            when(malformed.requestPayload()).thenThrow(new IllegalStateException("context read failed"));
            f.turns.prepareContinuation("invalid");
            f.turns.inherit(malformed, "invalid");
            f.turns.finishStart("invalid");
            f.writer.close();
            f.turns.prepareContinuation("writer-closed");
            f.turns.inherit(interaction(Map.of()), "writer-closed");
            f.turns.finishStart("writer-closed");
            assertEmpty(f.turns);
        }
    }

    private Map<String, Object> sourcePayload(ObservationTestFixture f, boolean legacy) {
        if (legacy) { return Map.of(); }
        f.start("run-a");
        var payload = f.turns.interactionMetadata("run-a", Map.of());
        f.turns.finishStart("run-a");
        await(() -> f.traces.size() == 2);
        return payload;
    }

    private ChatInteractionRequest interaction(Map<String, Object> payload) {
        return new ChatInteractionRequest("interaction", "t", "u", "session", "run-a", null, "user-1",
                "assistant", "domain-agent", "binding", "real-downstream-session", "approval",
                ChatInteractionType.AGENT_CLARIFICATION, ChatInteractionStatus.WAITING,
                payload, Map.of(), null, null, null, null, null);
    }

    private void assertEmpty(TurnTraceCoordinator turns) throws Exception {
        for (String name : new String[]{"states", "pendingContinuations"}) {
            var field = TurnTraceCoordinator.class.getDeclaredField(name);
            field.setAccessible(true);
            assertThat((Map<?, ?>) field.get(turns)).as(name).isEmpty();
        }
    }
}
