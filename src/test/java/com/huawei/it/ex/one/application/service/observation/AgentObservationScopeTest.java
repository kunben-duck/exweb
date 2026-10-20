/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.observation;

import static com.huawei.it.ex.one.application.service.observation.ObservationTestFixture.await;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.observation.FirstBusinessFrameClassifier;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.observation.TurnTraceContext;

import reactor.core.publisher.SignalType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

class AgentObservationScopeTest {
    private static final AgentObservationRecorder.Target TARGET = new AgentObservationRecorder.Target(
            "DOMAIN_AGENT", null, "QUERY", "skill-a", "downstream-session");
    private static final RuntimeEvent CARD = RuntimeEvent.card("run-a", "session", Map.of(
            "source", "domain-agent", "diyCardScene", Map.of("scene", "result")));

    @ParameterizedTest
    @MethodSource("noopCases")
    void noopMethodsReturnWhileAnotherThreadHoldsTheScopeMonitor(NoopOrigin origin, ScopeAction action)
            throws Exception {
        var turns = mock(TurnTraceCoordinator.class);
        var writer = mock(ObservationWriter.class);
        var classifier = mock(FirstBusinessFrameClassifier.class);
        var diagnostics = mock(ObservationDiagnostics.class);
        var clock = mock(Clock.class);
        var nanos = mock(LongSupplier.class);
        when(turns.enabled()).thenReturn(origin != NoopOrigin.DISABLED);
        if (origin == NoopOrigin.FAILED_INITIALIZATION) {
            when(turns.context("run-a")).thenThrow(new IllegalStateException("start failed"));
        }
        var recorder = new AgentObservationRecorder(turns, writer, classifier, diagnostics, clock, nanos);
        var scope = recorder.begin(origin == NoopOrigin.MISSING_CONTEXT ? null : context(), TARGET);
        assertThat(scope).isSameAs(AgentObservationRecorder.DISABLED.begin(null, TARGET));
        HttpHeaders headers = new HttpHeaders();
        headers.set("legacy", "unchanged");
        scope.headers(headers);
        assertThat(scope.id()).isNull();
        assertThat(headers).containsOnlyKeys("legacy");
        assertThat(headers.getFirst("legacy")).isEqualTo("unchanged");

        var task = new FutureTask<Void>(() -> { action.invoke(scope); return null; });
        Thread worker = Thread.ofPlatform().daemon().unstarted(task);
        try {
            synchronized (scope) {
                worker.start();
                task.get(2, TimeUnit.SECONDS);
            }
        } finally {
            worker.join(Duration.ofSeconds(5));
        }
        assertThat(worker.isAlive()).isFalse();
        verifyNoInteractions(writer, classifier, clock, nanos);
        if (origin == NoopOrigin.FAILED_INITIALIZATION) {
            verify(diagnostics).dropped("call_start");
        }
        verifyNoMoreInteractions(diagnostics);
    }

    @ParameterizedTest
    @EnumSource(ScopeAction.class)
    void activeMethodsStillUseTheScopeMonitor(ScopeAction action) throws Exception {
        try (var f = new ObservationTestFixture()) {
            f.start("run-a");
            var scope = f.recorder.begin(f.turns.context("run-a"), TARGET);
            var entered = new CountDownLatch(1);
            var task = new FutureTask<Void>(() -> {
                entered.countDown();
                action.invoke(scope);
                return null;
            });
            Thread worker = Thread.ofPlatform().daemon().unstarted(task);
            try {
                synchronized (scope) {
                    worker.start();
                    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                    await(() -> worker.getState() == Thread.State.BLOCKED || task.isDone());
                    assertThat(worker.getState()).isEqualTo(Thread.State.BLOCKED);
                    assertThat(task.isDone()).isFalse();
                }
                task.get(2, TimeUnit.SECONDS);
            } finally {
                worker.join(Duration.ofSeconds(5));
            }
            assertThat(worker.isAlive()).isFalse();
            scope.finish(SignalType.ON_COMPLETE);
            await(() -> f.calls.stream().anyMatch(call -> "FINISHED".equals(call.status())));
            f.turns.finishStart("run-a");
        }
    }

    @Test
    void concurrentSamplingAndFinishingCaptureOneFirstFrameAndOneEndSnapshot() throws Exception {
        try (var f = new ObservationTestFixture(); var workers = Executors.newFixedThreadPool(8)) {
            f.start("run-a");
            Clock clock = spy(f.clock);
            LongSupplier nanos = mock(LongSupplier.class);
            when(nanos.getAsLong()).thenAnswer(ignored -> f.nanos.get());
            var recorder = new AgentObservationRecorder(f.turns, f.writer,
                    new DefaultFirstBusinessFrameClassifier(), f.diagnostics, clock, nanos);
            var scope = recorder.begin(f.turns.context("run-a"), TARGET);
            f.advance(Duration.ofMillis(40));
            var startEvents = new CountDownLatch(1);
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 32; i++) {
                tasks.add(workers.submit(() -> {
                    startEvents.await();
                    scope.event(CARD);
                    return null;
                }));
            }
            startEvents.countDown();
            for (Future<?> task : tasks) { task.get(5, TimeUnit.SECONDS); }
            verify(clock, times(2)).instant();
            verify(nanos, times(2)).getAsLong();

            f.advance(Duration.ofMillis(60));
            tasks.clear();
            var startFinishes = new CountDownLatch(1);
            for (int i = 0; i < 32; i++) {
                tasks.add(workers.submit(() -> {
                    startFinishes.await();
                    scope.finish(SignalType.ON_COMPLETE);
                    return null;
                }));
            }
            startFinishes.countDown();
            for (Future<?> task : tasks) { task.get(5, TimeUnit.SECONDS); }
            scope.event(CARD);
            scope.error(new IllegalStateException("late"));
            scope.outcome("ERROR");
            scope.finish(SignalType.CANCEL);
            await(() -> f.calls.stream().anyMatch(call -> "FINISHED".equals(call.status())));
            verify(clock, times(3)).instant();
            verify(nanos, times(3)).getAsLong();
            assertThat(f.calls).hasSize(2);
            var ended = f.calls.getLast();
            assertThat(ended.status()).isEqualTo("FINISHED");
            assertThat(ended.outcome()).isEqualTo("STREAM_ENDED");
            assertThat(ended.firstBusinessFrameLatencyMs()).isEqualTo(40L);
            assertThat(ended.durationMs()).isEqualTo(100L);
            f.turns.finishStart("run-a");
        }
    }

    private static Stream<Arguments> noopCases() {
        return Arrays.stream(NoopOrigin.values()).flatMap(origin ->
                Arrays.stream(ScopeAction.values()).map(action -> Arguments.of(origin, action)));
    }

    private static TurnTraceContext context() {
        return new TurnTraceContext("turn-test", "tenant", "user", "session", "run-a", "message-a",
                null, null, "NEXT", "run-a", "message-a", 1, Instant.EPOCH, null, null,
                false, null, true, "clock", 0L);
    }

    private enum NoopOrigin { DISABLED, MISSING_CONTEXT, FAILED_INITIALIZATION }

    private enum ScopeAction {
        EVENT, INTENT_RESULT, OUTCOME, ERROR, FINISH;

        void invoke(AgentObservationRecorder.Scope scope) {
            switch (this) {
                case EVENT -> scope.event(CARD);
                case INTENT_RESULT -> scope.intentResult();
                case OUTCOME -> scope.outcome("COMPLETED");
                case ERROR -> scope.error(new IllegalArgumentException("test"));
                case FINISH -> scope.finish(SignalType.ON_COMPLETE);
            }
        }
    }
}
