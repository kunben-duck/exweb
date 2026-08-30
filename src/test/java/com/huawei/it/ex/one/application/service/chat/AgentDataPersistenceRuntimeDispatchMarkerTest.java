/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.agent.MessageSkillContext;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceMetadata;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.routing.RelayOutputMode;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RelayOutputModeMetadata;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeProfileMetadata;

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
    void interactionContinuationInheritsAnswerOnlyModeFromTrustedSourceRun() {
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
                RelayOutputModeMetadata.RUN_METADATA_KEY, true));
        when(runService.requireOwnedRun(user, "run-source")).thenReturn(sourceRun);

        InteractionRunLifecycle.InheritedRunState inherited =
                lifecycle.inheritedRunState(user, interaction);

        assertThat(inherited.relayOutputMode()).isEqualTo(RelayOutputMode.ANSWER_STREAM_ONLY);
        assertThat(inherited.persistenceState().placeholderMode()).isFalse();
    }

    @Test
    void sensitiveRelayRoutePersistsAnswerOnlyMarkerWithExecutionGuard() {
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        AppliedRouteRecorder recorder = new AppliedRouteRecorder(null, null, runService);
        ChatRun run = mock(ChatRun.class);
        RuntimeBinding binding = mock(RuntimeBinding.class);
        RouteTarget route = RouteTarget.agentRuntimeAnswerStreamOnly(
                "intent-agent", 1.0, "sensitive information");
        RunExecutionClaim claim = new RunExecutionClaim("run1", "instance1", 9L);
        when(runService.bindResolvedRoute(eq(run), eq(route), eq(binding), eq(claim), any()))
                .thenReturn(run);

        recorder.bindResolvedRouteRequired(
                run, route, binding, claim, AgentDataPersistenceState.full());

        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(runService).bindResolvedRoute(
                eq(run), eq(route), eq(binding), eq(claim), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry(RelayOutputModeMetadata.RUN_METADATA_KEY, true);
        assertThat(binding.metadata()).doesNotContainKey(RelayOutputModeMetadata.RUN_METADATA_KEY);
    }

    @Test
    void placeholderDispatchMarkerKeepsSensitiveOutputModeAndRuntimeProfile() {
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        AppliedRouteRecorder recorder = new AppliedRouteRecorder(null, null, runService);
        ChatRun run = mock(ChatRun.class);
        RuntimeBinding binding = mock(RuntimeBinding.class);
        RouteTarget route = RouteTarget.agentRuntimeAnswerStreamOnly(
                "intent-agent", 1.0, "sensitive information");
        RunExecutionClaim claim = new RunExecutionClaim("run1", "instance1", 9L);
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        when(binding.metadata()).thenReturn(Map.of(
                RuntimeProfileMetadata.PROFILE_KEY, "DELEGATE",
                RuntimeProfileMetadata.APP_MODE_KEY, "delegate"));
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
        assertThat(metadataCaptor.getValue())
                .containsEntry(RelayOutputModeMetadata.RUN_METADATA_KEY, true)
                .containsKey(RuntimeProfileMetadata.RUN_METADATA_KEY);
        assertThat(RuntimeProfileMetadata.copyRunMetadata(metadataCaptor.getValue()))
                .containsKey(RuntimeProfileMetadata.RUN_METADATA_KEY);
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

    @Test
    void interactionContinuationInheritsFinalInvocationSkill() {
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        InteractionRunLifecycle lifecycle = new InteractionRunLifecycle(runService, null, null, null);
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatInteractionRequest interaction = mock(ChatInteractionRequest.class);
        ChatRun sourceRun = mock(ChatRun.class);
        when(interaction.sourceRunId()).thenReturn("run-source");
        when(interaction.sessionId()).thenReturn("session1");
        when(sourceRun.sessionId()).thenReturn("session1");
        when(sourceRun.metadata()).thenReturn(Map.of(
                MessageSkillContext.RUN_METADATA_KEY, "skill-b"));
        when(runService.requireOwnedRun(user, "run-source")).thenReturn(sourceRun);

        assertThat(lifecycle.inheritedRunState(user, interaction).invocationSkillId())
                .isEqualTo("skill-b");
    }

    @Test
    void legacyDomainAgentContinuationFallsBackToTrustedRunAgentCode() {
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        InteractionRunLifecycle lifecycle = new InteractionRunLifecycle(runService, null, null, null);
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatInteractionRequest interaction = mock(ChatInteractionRequest.class);
        ChatRun sourceRun = mock(ChatRun.class);
        when(interaction.sourceRunId()).thenReturn("run-source");
        when(interaction.sessionId()).thenReturn("session1");
        when(sourceRun.sessionId()).thenReturn("session1");
        when(sourceRun.metadata()).thenReturn(Map.of());
        when(sourceRun.runtimeProvider()).thenReturn("domain-agent");
        when(sourceRun.agentCode()).thenReturn(" skill-legacy ");
        when(runService.requireOwnedRun(user, "run-source")).thenReturn(sourceRun);

        assertThat(lifecycle.inheritedRunState(user, interaction).invocationSkillId())
                .isEqualTo("skill-legacy");
    }
}
