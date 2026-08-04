package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceMetadata;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

class AgentDataPersistenceRuntimeDispatchMarkerTest {
    @Test
    void interactionContinuationInheritsPolicyWithoutSourceDispatchMarker() {
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        InteractionRunLifecycle lifecycle = new InteractionRunLifecycle(
                runService, null, null, null);
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatInteractionRequest interaction = mock(ChatInteractionRequest.class);
        ChatRun sourceRun = mock(ChatRun.class);
        when(interaction.sourceRunId()).thenReturn("run-source");
        when(interaction.sessionId()).thenReturn("session1");
        when(sourceRun.sessionId()).thenReturn("session1");
        when(sourceRun.metadata()).thenReturn(AgentDataPersistenceMetadata.runMetadata(
                AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER, "回答已隐藏", true));
        when(runService.requireOwnedRun(user, "run-source")).thenReturn(sourceRun);

        AgentDataPersistenceState inherited =
                lifecycle.inheritedPersistenceState(user, interaction);

        assertThat(inherited.placeholderMode()).isTrue();
        assertThat(inherited.placeholderContent()).isEqualTo("回答已隐藏");
        assertThat(inherited.runtimeDispatchStarted()).isFalse();
    }

    @Test
    void interactionContinuationRejectsMalformedSourcePolicyMetadata() {
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        InteractionRunLifecycle lifecycle = new InteractionRunLifecycle(
                runService, null, null, null);
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatInteractionRequest interaction = mock(ChatInteractionRequest.class);
        ChatRun sourceRun = mock(ChatRun.class);
        when(interaction.sourceRunId()).thenReturn("run-source");
        when(interaction.sessionId()).thenReturn("session1");
        when(sourceRun.sessionId()).thenReturn("session1");
        when(sourceRun.metadata()).thenReturn(Map.of(
                AgentDataPersistenceMetadata.RUN_METADATA_KEY,
                Map.of("policy", "UNKNOWN")));
        when(runService.requireOwnedRun(user, "run-source")).thenReturn(sourceRun);

        assertThatThrownBy(() -> lifecycle.inheritedPersistenceState(user, interaction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("metadata 非法");
    }

    @Test
    void legacySourceWithoutPolicyKeepsFullContinuationBehavior() {
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        InteractionRunLifecycle lifecycle = new InteractionRunLifecycle(
                runService, null, null, null);
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatInteractionRequest interaction = mock(ChatInteractionRequest.class);
        ChatRun sourceRun = mock(ChatRun.class);
        when(interaction.sourceRunId()).thenReturn("run-source");
        when(interaction.sessionId()).thenReturn("session1");
        when(sourceRun.sessionId()).thenReturn("session1");
        when(sourceRun.metadata()).thenReturn(Map.of());
        when(runService.requireOwnedRun(user, "run-source")).thenReturn(sourceRun);

        AgentDataPersistenceState inherited =
                lifecycle.inheritedPersistenceState(user, interaction);

        assertThat(inherited.placeholderMode()).isFalse();
        assertThat(inherited.resolved()).isFalse();
        assertThat(inherited.runtimeDispatchStarted()).isFalse();
    }

    @Test
    void placeholderMarkerIsPersistedWithExecutionGuardBeforeUpdatingMemoryState() {
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        AppliedRouteRecorder recorder = new AppliedRouteRecorder(null, null, runService);
        ChatRun run = mock(ChatRun.class);
        RuntimeBinding binding = mock(RuntimeBinding.class);
        RouteTarget route = RouteTarget.domainAgent("skill1", "intent-agent");
        RunExecutionClaim claim = new RunExecutionClaim("run1", "instance1", 9L);
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        when(runService.bindResolvedRoute(eq(run), eq(route), eq(binding), eq(claim), any()))
                .thenReturn(run);

        recorder.markRuntimeDispatchStartedRequired(run, route, binding, claim, state);

        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(runService).bindResolvedRoute(
                eq(run), eq(route), eq(binding), eq(claim), metadataCaptor.capture());
        AgentDataPersistenceMetadata.RunPolicySnapshot snapshot =
                AgentDataPersistenceMetadata.readRunPolicy(metadataCaptor.getValue());
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.policy()).isEqualTo(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        assertThat(snapshot.runtimeDispatchStarted()).isTrue();
        assertThat(state.runtimeDispatchStarted()).isTrue();
    }

    @Test
    void failedGuardedMarkerDoesNotUpdateMemoryState() {
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        AppliedRouteRecorder recorder = new AppliedRouteRecorder(null, null, runService);
        ChatRun run = mock(ChatRun.class);
        RuntimeBinding binding = mock(RuntimeBinding.class);
        RouteTarget route = RouteTarget.domainAgent("skill1", "intent-agent");
        RunExecutionClaim claim = new RunExecutionClaim("run1", "instance1", 9L);
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);

        assertThatThrownBy(() -> recorder.markRuntimeDispatchStartedRequired(
                run, route, binding, claim, state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Runtime dispatch marker");
        assertThat(state.runtimeDispatchStarted()).isFalse();
    }

    @Test
    void fullPolicyDoesNotAddDispatchMarkerOrWriteRun() {
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        AppliedRouteRecorder recorder = new AppliedRouteRecorder(null, null, runService);
        AgentDataPersistenceState state = AgentDataPersistenceState.full();

        recorder.markRuntimeDispatchStartedRequired(
                mock(ChatRun.class),
                RouteTarget.domainAgent("skill1", "intent-agent"),
                mock(RuntimeBinding.class),
                new RunExecutionClaim("run1", "instance1", 9L),
                state);

        verify(runService, never()).bindResolvedRoute(
                any(ChatRun.class), any(), any(), any(), any());
        assertThat(state.runtimeDispatchStarted()).isFalse();
    }
}
