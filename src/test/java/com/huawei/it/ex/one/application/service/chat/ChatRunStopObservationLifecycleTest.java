/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static com.huawei.it.ex.one.application.service.observation.ObservationTestFixture.await;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.service.observation.AgentObservationRecorder;
import com.huawei.it.ex.one.application.service.observation.ObservationTestFixture;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStopDecision;
import com.huawei.it.ex.one.domain.chat.ChatRunStopResult;
import com.huawei.it.ex.one.domain.chat.RunCancelledEvent;

import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class ChatRunStopObservationLifecycleTest {
    @Test
    void localStopPreservesLatestSamplesAndHeadersAfterExecutionSubscriptionIsCancelled() {
        try (var f = new Fixture()) {
            var expected = f.observation.turns.context(f.run.id());
            f.coordinator.stopRun(f.user, f.run.id(), "USER_STOP", RuntimeForwardHeaders.empty())
                    .block(Duration.ofSeconds(5));
            assertThat(f.observation.turns.context(f.run.id())).isNull();
            await(() -> f.observation.traces.stream().anyMatch(t -> "CANCELLED".equals(t.status())));
            var terminal = f.observation.traces.getLast();
            assertThat(terminal.traceId()).isEqualTo(expected.traceId());
            assertThat(terminal.firstBusinessFrameLatencyMs()).isEqualTo(20);
            assertThat(terminal.isRefusal()).isTrue();
            assertThat(f.headers.get().getFirst("X-Trace-Id")).isEqualTo(expected.traceId());
            assertThat(f.headers.get().getFirst("X-Observation-Id")).startsWith("obs_");
            verify(f.runtime).cancel(any(AgentRuntimeCancelRequest.class));
            verify(f.registry).cancel(f.run.id());
        }
    }

    @Test
    void cancelledStopSubscriberDoesNotReleaseInFlightSynchronousCommit() throws Exception {
        try (var f = new Fixture()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                entered.countDown();
                awaitUninterruptibly(release);
                assertThat(f.observation.turns.context(f.run.id())).isNotNull();
                return f.committed();
            }).when(f.terminal).commitExternalTerminal(any());
            var subscription = f.coordinator.stopRun(
                    f.user, f.run.id(), "USER_STOP", RuntimeForwardHeaders.empty()).subscribe();
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                subscription.dispose();
                assertThat(f.observation.turns.context(f.run.id())).isNotNull();
            } finally { release.countDown(); subscription.dispose(); }
            await(() -> f.observation.turns.context(f.run.id()) == null);
            await(() -> f.observation.traces.stream().anyMatch(t -> "CANCELLED".equals(t.status())));
        }
    }

    @Test
    void failedStopCommitReleasesScopeWithoutInventingTerminal() {
        try (var f = new Fixture()) {
            doThrow(new IllegalStateException("commit failed")).when(f.terminal).commitExternalTerminal(any());
            assertThatThrownBy(() -> f.coordinator.stopRun(
                    f.user, f.run.id(), "USER_STOP", RuntimeForwardHeaders.empty()).block(Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("commit failed");
            assertThat(f.observation.turns.context(f.run.id())).isNull();
            assertThat(f.observation.traces).noneMatch(t -> "CANCELLED".equals(t.status())
                    || "FAILED".equals(t.status()));
        }
    }

    @Test
    void waitingStopFailureReleasesRestoredScope() {
        try (var f = new Fixture()) {
            f.observation.turns.finishStart(f.run.id());
            var waiting = f.run.waitingUser(4);
            when(f.runs.requireOwnedRun(f.user, f.run.id())).thenReturn(waiting);
            var waitingService = mock(ChatWaitingStopCommitService.class);
            f.coordinator.setWaitingStopCommitService(waitingService);
            when(waitingService.cancelWaiting(any(), any(), any()))
                    .thenThrow(new IllegalStateException("waiting commit failed"));
            assertThatThrownBy(() -> f.coordinator.stopRun(
                    f.user, f.run.id(), "USER_STOP", RuntimeForwardHeaders.empty()).block(Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("waiting commit failed");
            assertThat(f.observation.turns.context(f.run.id())).isNull();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (latch.getCount() != 0) {
                try {
                    assertThat(latch.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)).isTrue();
                } catch (InterruptedException ex) { interrupted = true; }
            }
        } finally { if (interrupted) { Thread.currentThread().interrupt(); } }
    }

    private static final class Fixture implements AutoCloseable {
        private final ObservationTestFixture observation = new ObservationTestFixture();
        private final UserContext user = new UserContext("t", "u", "u");
        private final ChatRun run = observation.start("stop-run");
        private final ChatRunApplicationService runs = mock(ChatRunApplicationService.class);
        private final LocalChatRunExecutionRegistry registry = mock(LocalChatRunExecutionRegistry.class);
        private final ChatRunTerminalCommitService terminal = mock(ChatRunTerminalCommitService.class);
        private final AgentRuntime runtime = mock(AgentRuntime.class);
        private final AtomicReference<HttpHeaders> headers = new AtomicReference<>();
        private final ChatRunStopCoordinator coordinator;

        private Fixture() {
            observation.advance(Duration.ofMillis(20));
            observation.turns.sample(run.id(), false, null);
            observation.turns.sample(run.id(), true, null);
            when(runtime.provider()).thenReturn("domain-agent");
            when(runtime.cancel(any())).thenAnswer(invocation -> {
                AgentRuntimeCancelRequest request = invocation.getArgument(0);
                return Mono.fromRunnable(() -> {
                    var call = observation.recorder.begin(request.turnTraceContext(),
                            new AgentObservationRecorder.Target("DOMAIN_AGENT", null, "STOP", "skill-a", "downstream"));
                    HttpHeaders outbound = new HttpHeaders();
                    call.headers(outbound);
                    headers.set(outbound);
                    call.outcome("STOP_ACCEPTED");
                    call.finish(SignalType.ON_COMPLETE);
                });
            });
            var executor = new AgentRuntimeExecutor(runtime, mock(WorkloadConcurrencyLimiter.class));
            observation.wire(executor);
            var streams = mock(ChatStreamApplicationService.class);
            when(streams.findPersistedRunEvents(any(), any())).thenReturn(List.of());
            coordinator = new ChatRunStopCoordinator(mock(SessionApplicationService.class), streams, runs,
                    mock(ChatRunLeaseApplicationService.class), registry, executor,
                    mock(ChatInteractionApplicationService.class), terminal, mock(IdGenerator.class));
            coordinator.setObservationSupport(observation.turns);
            when(runs.requireOwnedRun(user, run.id())).thenReturn(run);
            when(runs.requestStop(user, run, "USER_STOP"))
                    .thenReturn(new ChatRunStopDecision(run.cancelling("USER_STOP"), true));
            when(registry.cancel(run.id())).thenAnswer(invocation -> {
                observation.turns.finishStart(run.id());
                assertThat(observation.turns.context(run.id())).isNotNull();
                return true;
            });
            when(runs.shouldAcceptEvent(any())).thenReturn(true);
            when(terminal.commitExternalTerminal(any())).thenAnswer(invocation -> committed());
            when(runs.toStopResult(any())).thenAnswer(invocation -> {
                ChatRun value = invocation.getArgument(0);
                return new ChatRunStopResult(value.id(), value.sessionId(), value.status(), 5,
                        value.finishedAt(), false, null, null);
            });
        }

        private ChatRunTerminalCommitService.ExternalTerminalCommitResult committed() {
            var cancelled = run.cancelled(5);
            observation.turns.committed(cancelled, null);
            return new ChatRunTerminalCommitService.ExternalTerminalCommitResult(
                    RunCancelledEvent.of(run.id(), run.sessionId(), "USER_STOP"), cancelled, true);
        }

        @Override public void close() { observation.close(); }
    }
}
