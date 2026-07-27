package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeInteractionResponseContext;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Executes non-Intent, non-route-switch Interaction continuations. */
final class RuntimeInteractionContinuationCoordinator {
    private final RuntimeBindingApplicationService runtimeBindingService;
    private final AgentRuntimeExecutor runtimeExecutor;
    private final AppliedRouteRecorder appliedRouteRecorder;
    private final InteractionEventFactory interactionEventFactory;
    private final InteractionRunLifecycle lifecycle;
    private final ChatEventPersistenceCoordinator eventPersistenceCoordinator;

    RuntimeInteractionContinuationCoordinator(
            RuntimeBindingApplicationService runtimeBindingService,
            AgentRuntimeExecutor runtimeExecutor,
            AppliedRouteRecorder appliedRouteRecorder,
            InteractionEventFactory interactionEventFactory,
            InteractionRunLifecycle lifecycle,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator) {
        this.runtimeBindingService = runtimeBindingService;
        this.runtimeExecutor = runtimeExecutor;
        this.appliedRouteRecorder = appliedRouteRecorder;
        this.interactionEventFactory = interactionEventFactory;
        this.lifecycle = lifecycle;
        this.eventPersistenceCoordinator = eventPersistenceCoordinator;
    }

    Flux<ChatEvent> execute(Request request) {
        ChatInteractionRequest interaction = request.claim().request();
        RouteTarget route = RouteTarget.agentRuntime(
                "interaction-continuation", 1.0, "continue waiting user input");
        RuntimeEvent responseEvent = interactionEventFactory.clarificationResponseEvent(
                request.runId(),
                request.session().id(),
                interaction,
                request.claim().responsePayload());
        ChatMessage userMessage = new ChatMessage(
                interaction.userMessageId(),
                request.user().tenantId(),
                request.user().ownerUserId(),
                request.session().id(),
                "user",
                "",
                null,
                Instant.now());
        ChatRunMessagePlan messagePlan = new ChatRunMessagePlan(
                ChatRunMode.NEXT,
                interaction.userMessageId(),
                userMessage,
                null);
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>();
        ChatRun run = lifecycle.create(new CreateChatRunContext(
                request.runId(),
                request.user(),
                request.session().id(),
                route,
                null,
                lifecycle.metadata(interaction),
                ChatRunMode.NEXT,
                interaction.userMessageId(),
                interaction.userMessageId()), interaction);
        lifecycle.trackRun(
                request.startAttempt(), run, "after-interaction-run-create");
        RunExecutionClaim executionClaim;
        try {
            executionClaim = lifecycle.startExecution(run, interaction);
        } catch (RuntimeException ex) {
            return lifecycle.failInitialization(run, interaction, ex);
        }
        lifecycle.trackExecution(
                request.startAttempt(),
                executionClaim,
                "after-interaction-execution-create");
        RunEventPipelineContext context = new RunEventPipelineContext(
                request.user(),
                request.session(),
                messagePlan,
                new AtomicReference<>(route),
                bindingRef,
                new AssistantAssembly(),
                request.runId(),
                executionClaim,
                new AtomicReference<>(),
                interaction,
                request.startAttempt(),
                List.of());
        try {
            return eventPersistenceCoordinator.executeAfterRunStarted(context, () -> {
                RuntimeBinding binding = runtimeBindingService.resumeForInteraction(
                        interaction, request.runId(), request.agentMode());
                bindingRef.set(binding);
                appliedRouteRecorder.bindResolvedRoute(request.runId(), route, binding);
                return Flux.concat(
                        Flux.just(responseEvent),
                        eventPersistenceCoordinator.requireCurrentOwnerRunning(
                                        executionClaim, "before-runtime-interaction")
                                .thenMany(Flux.defer(() -> runtimeExecutor.continueWithUserResponse(
                                        new RuntimeInteractionResponseContext(
                                                request.user(),
                                                request.session().id(),
                                                request.runId(),
                                                binding.provider(),
                                                binding.runtimeSessionId(),
                                                interaction.id(),
                                                interaction.interactionType().name(),
                                                interaction.approvalId(),
                                                request.claim().responsePayload(),
                                                request.forwardHeaders(),
                                                request.traceContext())))));
            });
        } catch (RuntimeException ex) {
            return lifecycle.failContinuation(context, ex);
        }
    }

    record Request(
            UserContext user,
            ChatInteractionClaimResult claim,
            String runId,
            ChatSession session,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            RunStartAttempt startAttempt,
            AgentModeProfile agentMode
    ) {
    }
}
