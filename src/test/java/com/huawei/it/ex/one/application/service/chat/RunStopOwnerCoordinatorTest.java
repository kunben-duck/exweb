package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.conversation.RunStopControlBus;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

class RunStopOwnerCoordinatorTest {
    private final UserContext user = new UserContext("tenant1", "user1", "User One");

    @Test
    void acceptsCurrentOwnerThenDisposesLocalRuntimeForAssemblyFinalization() {
        RunStopControlBus controlBus = mock(RunStopControlBus.class);
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        AgentRuntimeExecutor runtimeExecutor = mock(AgentRuntimeExecutor.class);
        RunExecutionClaim claim = new RunExecutionClaim("run-1", "instance-a", 7L);
        RunEventPipelineContext context = mock(RunEventPipelineContext.class);
        AtomicBoolean disposed = new AtomicBoolean();
        Disposable subscription = () -> disposed.set(true);
        ChatRun running = run(ChatRunStatus.RUNNING);
        ChatRun cancelling = running.cancelling("USER_STOP");
        when(context.runId()).thenReturn("run-1");
        when(context.executionClaim()).thenReturn(claim);
        when(context.user()).thenReturn(user);
        registry.registerClaim(claim);
        registry.attachContext(context);
        registry.register("run-1", subscription, claim);
        when(leaseService.currentInstanceId()).thenReturn("instance-a");
        when(leaseService.isCurrentOwnerRunning(claim)).thenReturn(true);
        when(runService.requireOwnedRun(user, "run-1")).thenReturn(running);
        when(runService.requestOwnerStop(user, running, "USER_STOP", claim))
                .thenReturn(new ChatRunApplicationService.OwnerStopDecision(cancelling, true));
        when(runtimeExecutor.cancel(any(ChatRun.class), any(), any(), any())).thenReturn(Mono.empty());
        RunStopOwnerCoordinator coordinator = new RunStopOwnerCoordinator(
                controlBus, registry, leaseService, runService, runtimeExecutor, Schedulers.immediate());

        RunStopControlBus.Response response = coordinator.requestLocal(request())
                .next()
                .block(Duration.ofSeconds(1));

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(RunStopControlBus.Status.ACCEPTED);
        assertThat(response.runStatus()).isEqualTo("CANCELLING");
        assertThat(disposed).isTrue();
        assertThat(registry.finishPipeline(claim)).isPresent();
        verify(runtimeExecutor).cancel(any(ChatRun.class), any(), any(), any());
    }

    @Test
    void relayOwnerWaitsForCancelCompletionBeforeDisposingRuntime() {
        RunStopControlBus controlBus = mock(RunStopControlBus.class);
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        AgentRuntimeExecutor runtimeExecutor = mock(AgentRuntimeExecutor.class);
        RunExecutionClaim claim = new RunExecutionClaim("run-1", "instance-a", 7L);
        RunEventPipelineContext context = mock(RunEventPipelineContext.class);
        AtomicBoolean disposed = new AtomicBoolean();
        ChatRun running = run(ChatRunStatus.RUNNING);
        ChatRun cancelling = running.cancelling("USER_STOP");
        Sinks.Empty<Void> cancelCompletion = Sinks.empty();
        when(context.runId()).thenReturn("run-1");
        when(context.executionClaim()).thenReturn(claim);
        when(context.user()).thenReturn(user);
        registry.registerClaim(claim);
        registry.attachContext(context);
        registry.register("run-1", () -> disposed.set(true), claim);
        when(leaseService.currentInstanceId()).thenReturn("instance-a");
        when(runService.requireOwnedRun(user, "run-1")).thenReturn(running);
        when(runService.requestOwnerStop(user, running, "USER_STOP", claim))
                .thenReturn(new ChatRunApplicationService.OwnerStopDecision(cancelling, true));
        when(runtimeExecutor.cancel(any(ChatRun.class), any(), any(), any()))
                .thenReturn(cancelCompletion.asMono());
        RunStopOwnerCoordinator coordinator = new RunStopOwnerCoordinator(
                controlBus, registry, leaseService, runService, runtimeExecutor, Schedulers.immediate());

        RunStopControlBus.Response response = coordinator.requestLocal(request())
                .next()
                .block(Duration.ofSeconds(1));

        assertThat(response.status()).isEqualTo(RunStopControlBus.Status.ACCEPTED);
        assertThat(disposed).isFalse();
        cancelCompletion.tryEmitEmpty();
        assertThat(disposed).isTrue();
    }

    @Test
    void rejectsStaleFencingTokenWithoutChangingRunOrRuntime() {
        RunStopControlBus controlBus = mock(RunStopControlBus.class);
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        AgentRuntimeExecutor runtimeExecutor = mock(AgentRuntimeExecutor.class);
        RunExecutionClaim claim = new RunExecutionClaim("run-1", "instance-a", 8L);
        RunEventPipelineContext context = mock(RunEventPipelineContext.class);
        when(context.runId()).thenReturn("run-1");
        when(context.executionClaim()).thenReturn(claim);
        registry.registerClaim(claim);
        registry.attachContext(context);
        registry.register("run-1", () -> { }, claim);
        when(leaseService.currentInstanceId()).thenReturn("instance-a");
        RunStopOwnerCoordinator coordinator = new RunStopOwnerCoordinator(
                controlBus, registry, leaseService, runService, runtimeExecutor, Schedulers.immediate());

        RunStopControlBus.Response response = coordinator.requestLocal(request())
                .next()
                .block(Duration.ofSeconds(1));

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(RunStopControlBus.Status.NOT_OWNER);
        verify(runService, never()).requestOwnerStop(any(), any(ChatRun.class), any(), any());
        verify(runtimeExecutor, never()).cancel(any(ChatRun.class), any(), any(), any());
    }

    @Test
    void returnsUnavailableImmediatelyWhenOwnerSchedulerRejectsTask() {
        RunStopControlBus controlBus = mock(RunStopControlBus.class);
        Scheduler scheduler = Schedulers.newSingle("stopped-owner-test");
        scheduler.dispose();
        ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        when(leaseService.currentInstanceId()).thenReturn("instance-a");
        RunStopOwnerCoordinator coordinator = new RunStopOwnerCoordinator(
                controlBus,
                new LocalChatRunExecutionRegistry(),
                leaseService,
                mock(ChatRunApplicationService.class),
                mock(AgentRuntimeExecutor.class),
                scheduler);

        RunStopControlBus.Response response = coordinator.requestLocal(request())
                .next()
                .block(Duration.ofSeconds(1));

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(RunStopControlBus.Status.UNAVAILABLE);
    }

    private RunStopControlBus.Request request() {
        return new RunStopControlBus.Request(
                "stop-1", "run-1", "instance-b", "instance-a", 7L,
                "USER_STOP");
    }

    private ChatRun run(ChatRunStatus status) {
        Instant now = Instant.now();
        return new ChatRun(
                "run-1", "tenant1", "user1", "session1", status,
                "AGENT_RUNTIME", null, "relay", "relay-session", 1L, 2L,
                null, now, null, Map.of(), now, now);
    }
}
