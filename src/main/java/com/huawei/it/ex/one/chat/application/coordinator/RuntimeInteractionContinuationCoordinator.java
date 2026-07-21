package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.mapper.ChatRuntimeMapper;

import com.huawei.it.ex.one.chat.application.model.AssistantAssembly;
import com.huawei.it.ex.one.chat.application.model.RunEventPipelineContext;
import com.huawei.it.ex.one.chat.application.model.RunStartAttempt;
import com.huawei.it.ex.one.chat.application.service.ChatInteractionClaimResult;
import com.huawei.it.ex.one.chat.application.service.CreateChatRunContext;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatRunMessagePlan;
import com.huawei.it.ex.one.chat.domain.ChatRunMode;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import com.huawei.it.ex.one.common.event.RuntimeEvent;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.runtime.application.model.RuntimeInteractionResponseContext;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingService;
import com.huawei.it.ex.one.runtime.application.service.RuntimeExecutionService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Executes non-Intent, non-route-switch Interaction continuations. */
@Component
public class RuntimeInteractionContinuationCoordinator {
    private final RuntimeBindingService runtimeBindingService;
    private final RuntimeExecutionService runtimeExecutionService;
    private final RouteDecisionRecorder routeDecisionRecorder;
    private final InteractionEventFactory interactionEventFactory;
    private final InteractionRunLifecycle lifecycle;
    private final ChatEventPersistenceCoordinator eventPersistenceCoordinator;
    private final ChatRunExecutionGateCoordinator executionGateCoordinator;

    public RuntimeInteractionContinuationCoordinator(
            RuntimeBindingService runtimeBindingService,
            RuntimeExecutionService runtimeExecutionService,
            RouteDecisionRecorder routeDecisionRecorder,
            InteractionEventFactory interactionEventFactory,
            InteractionRunLifecycle lifecycle,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            ChatRunExecutionGateCoordinator executionGateCoordinator) {
        this.runtimeBindingService = runtimeBindingService;
        this.runtimeExecutionService = runtimeExecutionService;
        this.routeDecisionRecorder = routeDecisionRecorder;
        this.interactionEventFactory = interactionEventFactory;
        this.lifecycle = lifecycle;
        this.eventPersistenceCoordinator = eventPersistenceCoordinator;
        this.executionGateCoordinator = executionGateCoordinator;
    }

    public Flux<ChatEvent> execute(Request request) {
        ChatInteractionRequest interaction = request.claim().request();
        RouteTarget route = RouteTarget.agentRuntime("interaction-continuation", 1.0,
                "continue waiting user input");
        RuntimeEvent responseEvent = interactionEventFactory.clarificationResponseEvent(
                request.runId(), request.session().id(), interaction, request.claim().responsePayload());
        ChatMessage userMessage = new ChatMessage(
                interaction.userMessageId(), request.user().tenantId(), request.user().ownerUserId(),
                request.session().id(), "user", "", null, Instant.now());
        ChatRunMessagePlan messagePlan = new ChatRunMessagePlan(
                ChatRunMode.NEXT, interaction.userMessageId(), userMessage, null);
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>();
        ChatRun run = lifecycle.create(new CreateChatRunContext(
                request.runId(), request.user(), request.session().id(), route, null,
                lifecycle.metadata(interaction), ChatRunMode.NEXT,
                interaction.userMessageId(), interaction.userMessageId()), interaction);
        lifecycle.trackRun(request.startAttempt(), run, "after-interaction-run-create");
        RunExecutionClaim executionClaim;
        try {
            executionClaim = lifecycle.startExecution(run, interaction);
        } catch (RuntimeException ex) {
            return lifecycle.failInitialization(run, interaction, ex);
        }
        lifecycle.trackExecution(request.startAttempt(), executionClaim,
                "after-interaction-execution-create");
        RunEventPipelineContext context = new RunEventPipelineContext(
                request.user(), request.session(), messagePlan, new AtomicReference<>(route), bindingRef,
                new AssistantAssembly(), request.runId(), executionClaim, new AtomicReference<>(),
                interaction, request.startAttempt(), List.of());
        try {
            return eventPersistenceCoordinator.executeAfterRunStarted(context, () -> {
                RuntimeBinding binding = runtimeBindingService.resumeForInteraction(
                        ChatRuntimeMapper.interaction(interaction), request.runId());
                bindingRef.set(binding);
                routeDecisionRecorder.bindResolvedRoute(request.runId(), route, binding);
                return Flux.concat(
                        Flux.just(responseEvent),
                        executionGateCoordinator.requireCurrentOwnerRunning(
                                        executionClaim, "before-runtime-interaction")
                                .thenMany(Flux.defer(() -> runtimeExecutionService.continueWithUserResponse(
                                        new RuntimeInteractionResponseContext(
                                                request.user(), request.session().id(), request.runId(),
                                                binding.provider(), binding.runtimeSessionId(), interaction.id(),
                                                interaction.interactionType().name(), interaction.approvalId(),
                                                request.claim().responsePayload(), request.forwardHeaders(),
                                                request.traceContext())))));
            });
        } catch (RuntimeException ex) {
            return lifecycle.failContinuation(context, ex);
        }
    }

    public record Request(
            UserContext user,
            ChatInteractionClaimResult claim,
            String runId,
            ChatSession session,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            RunStartAttempt startAttempt
    ) {
    }
}
