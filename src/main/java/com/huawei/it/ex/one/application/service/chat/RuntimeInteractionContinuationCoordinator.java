package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.RuntimeInteractionDispatchState;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeInteractionResponseContext;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
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
import com.huawei.it.ex.one.domain.routing.RelayOutputMode;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.RelayOutputModeMetadata;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeProfileMetadata;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Executes non-Intent, non-route-switch Interaction continuations. */
final class RuntimeInteractionContinuationCoordinator {
    private static final AppLogger log = AppLoggerFactory.getLogger(RuntimeInteractionContinuationCoordinator.class);

    private final RuntimeBindingApplicationService runtimeBindingService;
    private final AgentRuntimeExecutor runtimeExecutor;
    private final AppliedRouteRecorder appliedRouteRecorder;
    private final InteractionEventFactory interactionEventFactory;
    private final InteractionRunLifecycle lifecycle;
    private final ChatEventPersistenceCoordinator eventPersistenceCoordinator;
    private final Scheduler eventIoScheduler;

    RuntimeInteractionContinuationCoordinator(
            RuntimeBindingApplicationService runtimeBindingService,
            AgentRuntimeExecutor runtimeExecutor,
            AppliedRouteRecorder appliedRouteRecorder,
            InteractionEventFactory interactionEventFactory,
            InteractionRunLifecycle lifecycle,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            Scheduler eventIoScheduler) {
        this.runtimeBindingService = runtimeBindingService;
        this.runtimeExecutor = runtimeExecutor;
        this.appliedRouteRecorder = appliedRouteRecorder;
        this.interactionEventFactory = interactionEventFactory;
        this.lifecycle = lifecycle;
        this.eventPersistenceCoordinator = eventPersistenceCoordinator;
        this.eventIoScheduler = eventIoScheduler;
    }

    Flux<ChatEvent> execute(Request request) {
        ChatInteractionRequest interaction = request.claim().request();
        InteractionRunLifecycle.InheritedRunState inheritedState =
                lifecycle.inheritedRunState(request.user(), interaction);
        RelayOutputMode relayOutputMode = inheritedState.relayOutputMode();
        RouteTarget route = relayOutputMode == RelayOutputMode.ANSWER_STREAM_ONLY
                ? RouteTarget.agentRuntimeAnswerStreamOnly(
                        "interaction-continuation", 1.0, "continue waiting user input",
                        inheritedState.invocationSkillId())
                : RouteTarget.agentRuntimeWithInvocationSkill(
                        "interaction-continuation", 1.0, "continue waiting user input",
                        inheritedState.invocationSkillId());
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
        AgentDataPersistenceState persistenceState = inheritedState.persistenceState();
        RuntimeInteractionDispatchState dispatchState = RelayQuestionnaireAnswerValidator.isRelayQuestionnaire(interaction)
                ? RuntimeInteractionDispatchState.tracked()
                : RuntimeInteractionDispatchState.untracked();
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
        AssistantAssembly assistant = new AssistantAssembly(persistenceState);
        RunEventPipelineContext context = new RunEventPipelineContext(
                request.user(),
                request.session(),
                messagePlan,
                new AtomicReference<>(route),
                bindingRef,
                assistant,
                request.runId(),
                executionClaim,
                new AtomicReference<>(),
                interaction,
                request.startAttempt(),
                List.of(),
                dispatchState);
        InteractionExecution execution = new InteractionExecution(
                request,
                interaction,
                run,
                responseEvent,
                route,
                executionClaim,
                bindingRef,
                assistant);
        try {
            return eventPersistenceCoordinator.executeAfterRunStarted(context, () ->
                    eventPersistenceCoordinator.requireCurrentOwnerRunning(
                                    executionClaim, "before-runtime-interaction-binding")
                            .thenMany(Flux.usingWhen(
                                    Mono.fromCallable(() -> resumeInteractionBinding(
                                            request, interaction, executionClaim, dispatchState))
                                            .subscribeOn(eventIoScheduler),
                                    bindingLifecycle -> executeInteraction(execution, bindingLifecycle),
                                    lifecycle -> cleanupUnstartedInteraction(
                                            interaction, request.runId(), bindingRef, lifecycle, "complete"),
                                    (lifecycle, failure) -> cleanupUnstartedInteraction(
                                            interaction, request.runId(), bindingRef, lifecycle, "error"),
                                    lifecycle -> cleanupUnstartedInteraction(
                                            interaction, request.runId(), bindingRef, lifecycle, "cancel"))));
        } catch (RuntimeException ex) {
            return lifecycle.failContinuation(context, ex);
        }
    }

    private InteractionBindingLifecycle resumeInteractionBinding(
            Request request,
            ChatInteractionRequest interaction,
            RunExecutionClaim executionClaim,
            RuntimeInteractionDispatchState dispatchState) {
        if (RelayQuestionnaireAnswerValidator.isRelayQuestionnaire(interaction)) {
            RuntimeBinding binding = runtimeBindingService.resumeRelayForInteraction(
                    interaction, request.runId(), executionClaim);
            return new InteractionBindingLifecycle(binding, true, dispatchState);
        }
        RuntimeBinding binding = runtimeBindingService.resumeForInteraction(
                interaction, request.runId(), request.agentMode());
        return new InteractionBindingLifecycle(binding, false, dispatchState);
    }

    private Flux<ChatEvent> executeInteraction(
            InteractionExecution execution,
            InteractionBindingLifecycle bindingLifecycle) {
        RuntimeBinding binding = bindingLifecycle.binding();
        execution.bindingRef().set(binding);
        appliedRouteRecorder.bindResolvedRouteRequired(
                execution.run(), execution.route(), binding, execution.executionClaim(),
                execution.assistant().persistenceState());
        execution.assistant().messageSkill().replace(execution.route().invocationSkillId());
        return Flux.concat(
                Flux.just(execution.responseEvent()),
                eventPersistenceCoordinator.requireCurrentOwnerRunning(
                                execution.executionClaim(), "before-runtime-interaction")
                        .then(Mono.fromRunnable(() -> appliedRouteRecorder.markRuntimeDispatchStartedRequired(
                                execution.run(),
                                execution.route(),
                                binding,
                                execution.executionClaim(),
                                execution.assistant().persistenceState())))
                        .thenMany(Flux.defer(() -> runtimeExecutor
                                .continueWithUserResponse(new RuntimeInteractionResponseContext(
                                        execution.request().user(),
                                        execution.request().session().id(),
                                        execution.request().runId(),
                                        binding.provider(),
                                        binding.runtimeSessionId(),
                                        execution.interaction().id(),
                                        execution.interaction().interactionType().name(),
                                        execution.interaction().approvalId(),
                                        execution.request().claim().responsePayload(),
                                        execution.request().forwardHeaders(),
                                        execution.request().traceContext(),
                                        runtimeMetadata(binding, execution.route()),
                                        bindingLifecycle.dispatchState())))));
    }

    private Map<String, Object> runtimeMetadata(RuntimeBinding binding, RouteTarget route) {
        Map<String, Object> metadata = new LinkedHashMap<>(
                RuntimeProfileMetadata.copyBindingProfileAsRunMetadata(binding.metadata()));
        metadata.putAll(RelayOutputModeMetadata.runMetadataOverlay(route));
        return Map.copyOf(metadata);
    }

    private Mono<Void> cleanupUnstartedInteraction(
            ChatInteractionRequest interaction,
            String runId,
            AtomicReference<RuntimeBinding> bindingRef,
            InteractionBindingLifecycle bindingLifecycle,
            String terminationSignal) {
        if (!bindingLifecycle.restoreUnstartedRelayQuestionnaire()
                || bindingLifecycle.dispatchState().responseDispatched()) {
            return Mono.empty();
        }
        return Mono.<Void>fromRunnable(() -> {
                    RuntimeBinding binding = bindingLifecycle.binding();
                    boolean restored = runtimeBindingService.restoreUnstartedRelayInteraction(
                            binding, runId, interaction.sourceRunId());
                    if (restored) {
                        bindingLifecycle.dispatchState().markBindingRestored();
                        bindingRef.compareAndSet(binding, binding.withRun(interaction.sourceRunId(), null));
                    } else {
                        bindingLifecycle.dispatchState().markBindingRestoreFailed();
                    }
                })
                .subscribeOn(eventIoScheduler)
                .onErrorResume(ex -> {
                    bindingLifecycle.dispatchState().markBindingRestoreFailed();
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_WRITE_FAILED,
                                    "Unstarted Relay interaction binding restore failed")
                            .runId(runId)
                            .sessionId(interaction.sessionId())
                            .operation("relay.interaction.binding-restore")
                            .attribute("bindingId", bindingLifecycle.binding().id())
                            .attribute("terminationSignal", terminationSignal)
                            .build(), ex);
                    return Mono.empty();
                })
                .then();
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

    private record InteractionExecution(
            Request request,
            ChatInteractionRequest interaction,
            ChatRun run,
            RuntimeEvent responseEvent,
            RouteTarget route,
            RunExecutionClaim executionClaim,
            AtomicReference<RuntimeBinding> bindingRef,
            AssistantAssembly assistant
    ) {
    }

    private static final class InteractionBindingLifecycle {
        private final RuntimeBinding binding;
        private final boolean restoreUnstartedRelayQuestionnaire;
        private final RuntimeInteractionDispatchState dispatchState;

        private InteractionBindingLifecycle(
                RuntimeBinding binding,
                boolean restoreUnstartedRelayQuestionnaire,
                RuntimeInteractionDispatchState dispatchState) {
            this.binding = binding;
            this.restoreUnstartedRelayQuestionnaire = restoreUnstartedRelayQuestionnaire;
            this.dispatchState = dispatchState;
        }

        private RuntimeBinding binding() {
            return binding;
        }

        private RuntimeInteractionDispatchState dispatchState() {
            return dispatchState;
        }

        private boolean restoreUnstartedRelayQuestionnaire() {
            return restoreUnstartedRelayQuestionnaire;
        }
    }
}
