package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.conversation.RunStopControlBus;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import reactor.core.Disposable;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

class LocalChatRunExecutionRegistryTest {
    @Test
    void conditionalCancelDisposesOnlyMatchingClaim() {
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        RunExecutionClaim oldClaim = new RunExecutionClaim("run-1", "instance-a", 1L);
        RunExecutionClaim newClaim = new RunExecutionClaim("run-1", "instance-a", 2L);
        AtomicBoolean disposed = new AtomicBoolean();
        Disposable subscription = () -> disposed.set(true);
        registry.register("run-1", subscription, newClaim);

        assertThat(registry.cancel(oldClaim)).isFalse();
        assertThat(disposed).isFalse();
        assertThat(registry.activeClaims()).containsExactly(newClaim);

        assertThat(registry.cancel(newClaim)).isTrue();
        assertThat(disposed).isTrue();
        assertThat(registry.activeClaims()).isEmpty();
    }

    @Test
    void conditionalCancelKeepsClaimUntilSubscriptionIsRegistered() {
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        RunExecutionClaim claim = new RunExecutionClaim("run-1", "instance-a", 1L);
        registry.registerClaim(claim);

        assertThat(registry.cancel(claim)).isFalse();
        assertThat(registry.activeClaims()).containsExactly(claim);

        AtomicBoolean disposed = new AtomicBoolean();
        registry.register("run-1", () -> disposed.set(true));
        assertThat(registry.cancel(claim)).isTrue();
        assertThat(disposed).isTrue();
    }

    @Test
    void conditionalCompletionDoesNotRemoveNewerFencingOwner() {
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        RunExecutionClaim oldClaim = new RunExecutionClaim("run-1", "instance-a", 1L);
        RunExecutionClaim newClaim = new RunExecutionClaim("run-1", "instance-b", 2L);
        registry.registerClaim(oldClaim);
        registry.registerClaim(newClaim);

        registry.complete(oldClaim);

        assertThat(registry.activeClaims()).containsExactly(newClaim);
        registry.complete(newClaim);
        assertThat(registry.activeClaims()).isEmpty();
    }

    @Test
    void confirmedOwnerStopKeepsAssemblyContextUntilFinalizationCompletes() {
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        RunExecutionClaim claim = new RunExecutionClaim("run-1", "instance-a", 7L);
        RunEventPipelineContext context = mock(RunEventPipelineContext.class);
        when(context.runId()).thenReturn("run-1");
        when(context.executionClaim()).thenReturn(claim);
        registry.registerClaim(claim);
        registry.attachContext(context);
        AtomicBoolean disposed = new AtomicBoolean();
        registry.register("run-1", () -> disposed.set(true), claim);
        RunStopControlBus.Request request = new RunStopControlBus.Request(
                "stop-1", "run-1", "instance-b", "instance-a", 7L,
                "USER_STOP");

        LocalChatRunExecutionRegistry.OwnerStopRegistration registration = registry
                .beginOwnerStop(request, ignored -> { })
                .orElseThrow();
        assertThat(registration.context()).isSameAs(context);
        assertThat(registry.activeClaims()).isEmpty();

        assertThat(registry.confirmOwnerStop("run-1", "stop-1", cancellingRun())).isTrue();
        assertThat(registry.disposeOwnerStop("run-1", "stop-1")).isTrue();
        assertThat(disposed).isTrue();
        LocalChatRunExecutionRegistry.OwnerStopFinalization finalization = registry
                .finishPipeline(claim)
                .orElseThrow();
        assertThat(finalization.context()).isSameAs(context);
        assertThat(finalization.cancellingRun().block().status()).isEqualTo(ChatRunStatus.CANCELLING);

        registry.completeOwnerStopFinalization(claim);
        assertThat(registry.activeClaims()).isEmpty();
        assertThat(registry.finishPipeline(claim)).isEmpty();
    }

    @Test
    void pipelineFinishingBeforeDatabaseConfirmationKeepsAssemblyUntilConfirmation() {
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        RunExecutionClaim claim = new RunExecutionClaim("run-1", "instance-a", 7L);
        RunEventPipelineContext context = mock(RunEventPipelineContext.class);
        when(context.runId()).thenReturn("run-1");
        when(context.executionClaim()).thenReturn(claim);
        registry.registerClaim(claim);
        registry.attachContext(context);
        registry.register("run-1", () -> { }, claim);
        RunStopControlBus.Request request = new RunStopControlBus.Request(
                "stop-1", "run-1", "instance-b", "instance-a", 7L,
                "USER_STOP");
        registry.beginOwnerStop(request, ignored -> { }).orElseThrow();

        LocalChatRunExecutionRegistry.OwnerStopFinalization finalization = registry
                .finishPipeline(claim)
                .orElseThrow();
        assertThat(finalization.context()).isSameAs(context);
        assertThat(registry.finishPipeline(claim)).isEmpty();

        assertThat(registry.confirmOwnerStop("run-1", "stop-1", cancellingRun())).isTrue();
        assertThat(finalization.cancellingRun().block()).isNotNull()
                .extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.CANCELLING);
        registry.completeOwnerStopFinalization(claim);
        assertThat(registry.activeClaims()).isEmpty();
    }

    private ChatRun cancellingRun() {
        Instant now = Instant.now();
        return new ChatRun(
                "run-1", "tenant1", "user1", "session1", ChatRunStatus.CANCELLING,
                "AGENT_RUNTIME", null, "relay", "relay-session", 1L, 2L,
                "USER_STOP", now, null, Map.of(), now, now);
    }
}
