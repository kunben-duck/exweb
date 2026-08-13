package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeInteractionResponseContext;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.routing.RelayOutputMode;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RelayOutputModeMetadata;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

class RuntimeInteractionContinuationRetentionTest {
    @Test
    void relayQuestionnaireContinuationInheritsPlaceholderAndAnswerOnlyStates() {
        RuntimeBindingApplicationService bindingService = mock(RuntimeBindingApplicationService.class);
        AgentRuntimeExecutor runtimeExecutor = mock(AgentRuntimeExecutor.class);
        AppliedRouteRecorder routeRecorder = mock(AppliedRouteRecorder.class);
        InteractionRunLifecycle lifecycle = mock(InteractionRunLifecycle.class);
        ChatEventPersistenceCoordinator persistenceCoordinator = mock(ChatEventPersistenceCoordinator.class);
        RuntimeInteractionContinuationCoordinator coordinator =
                new RuntimeInteractionContinuationCoordinator(
                        bindingService,
                        runtimeExecutor,
                        routeRecorder,
                        new InteractionEventFactory(),
                        lifecycle,
                        persistenceCoordinator,
                        Schedulers.immediate());
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatSession session = new ChatSession(
                "session1", "tenant1", "user1", "title", "ACTIVE", "web",
                Instant.EPOCH, Instant.EPOCH);
        Instant now = Instant.now();
        ChatInteractionRequest interaction = new ChatInteractionRequest(
                "interaction1", "tenant1", "user1", session.id(), "run-a", "run-b",
                "msg-user", "msg-assistant", "relay", "binding1", "runtime-session1", "approval-1",
                ChatInteractionType.AGENT_CLARIFICATION, ChatInteractionStatus.RESPONDING,
                Map.of("sourceType", "approval-request", "operation_type", "questionnaire"), Map.of(),
                now.plusSeconds(60), now, null, now, now);
        ChatInteractionClaimResult claim = new ChatInteractionClaimResult(
                interaction, Map.of("questionnaireAnswers", Map.of("问题", "答案")));
        ChatRun run = mock(ChatRun.class);
        RuntimeBinding binding = mock(RuntimeBinding.class);
        RunStartAttempt startAttempt = mock(RunStartAttempt.class);
        RunExecutionClaim executionClaim = new RunExecutionClaim("run-b", "instance1", 7L);
        AgentDataPersistenceState persistenceState = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        AtomicReference<RunEventPipelineContext> pipelineContext = new AtomicReference<>();
        when(lifecycle.inheritedRunState(user, interaction)).thenReturn(
                new InteractionRunLifecycle.InheritedRunState(
                        persistenceState, RelayOutputMode.ANSWER_STREAM_ONLY));
        when(lifecycle.metadata(interaction)).thenReturn(Map.of(
                "interactionAssistantMessageId", "msg-assistant"));
        when(lifecycle.create(any(), eq(interaction))).thenReturn(run);
        when(lifecycle.startExecution(run, interaction)).thenReturn(executionClaim);
        when(bindingService.resumeRelayForInteraction(interaction, "run-b", executionClaim))
                .thenReturn(binding);
        when(binding.provider()).thenReturn("relay");
        when(binding.runtimeSessionId()).thenReturn("runtime-session1");
        when(persistenceCoordinator.requireCurrentOwnerRunning(eq(executionClaim), any()))
                .thenReturn(Mono.empty());
        when(persistenceCoordinator.executeAfterRunStarted(any(), any()))
                .thenAnswer(invocation -> {
                    pipelineContext.set(invocation.getArgument(0));
                    @SuppressWarnings("unchecked")
                    Supplier<Flux<ChatEvent>> supplier = invocation.getArgument(1);
                    return supplier.get();
                });
        when(runtimeExecutor.continueWithUserResponse(any()))
                .thenReturn(Flux.just(MessageDeltaEvent.of("run-b", session.id(), "真实回答")));

        List<ChatEvent> events = coordinator.execute(new RuntimeInteractionContinuationCoordinator.Request(
                        user,
                        claim,
                        "run-b",
                        session,
                        RuntimeForwardHeaders.empty(),
                        TraceContext.empty(),
                        startAttempt,
                        null))
                .collectList()
                .block();

        assertThat(events).hasSize(2);
        assertThat(pipelineContext.get()).isNotNull();
        assertThat(pipelineContext.get().assistant().persistenceState()).isSameAs(persistenceState);
        assertThat(pipelineContext.get().assistant().persistenceState().placeholderMode()).isTrue();
        verify(lifecycle).inheritedRunState(user, interaction);

        ArgumentCaptor<RouteTarget> routeCaptor = ArgumentCaptor.forClass(RouteTarget.class);
        verify(routeRecorder).bindResolvedRouteRequired(
                eq(run), routeCaptor.capture(), eq(binding), eq(executionClaim), eq(persistenceState));
        assertThat(routeCaptor.getValue().relayOutputMode())
                .isEqualTo(RelayOutputMode.ANSWER_STREAM_ONLY);

        ArgumentCaptor<RuntimeInteractionResponseContext> runtimeCaptor =
                ArgumentCaptor.forClass(RuntimeInteractionResponseContext.class);
        verify(runtimeExecutor).continueWithUserResponse(runtimeCaptor.capture());
        assertThat(runtimeCaptor.getValue().runtimeMetadata())
                .containsEntry(RelayOutputModeMetadata.RUN_METADATA_KEY, true);
    }
}
