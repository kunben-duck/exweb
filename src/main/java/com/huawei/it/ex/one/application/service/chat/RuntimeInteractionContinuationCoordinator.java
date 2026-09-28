/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.IntentExpertContext;
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
import reactor.core.publisher.Sinks;
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
    private final DomainAgentQuestionnaireContinuation domainAgentContinuation;

    RuntimeInteractionContinuationCoordinator(
            RuntimeBindingApplicationService runtimeBindingService,
            AgentRuntimeExecutor runtimeExecutor,
            AppliedRouteRecorder appliedRouteRecorder,
            InteractionEventFactory interactionEventFactory,
            InteractionRunLifecycle lifecycle,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            Scheduler eventIoScheduler) {
        this(runtimeBindingService, runtimeExecutor, appliedRouteRecorder, interactionEventFactory,
                lifecycle, eventPersistenceCoordinator, eventIoScheduler, null);
    }

    RuntimeInteractionContinuationCoordinator(
            RuntimeBindingApplicationService runtimeBindingService, AgentRuntimeExecutor runtimeExecutor,
            AppliedRouteRecorder appliedRouteRecorder, InteractionEventFactory interactionEventFactory,
            InteractionRunLifecycle lifecycle, ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            Scheduler eventIoScheduler, DomainAgentQuestionnaireContinuation domainAgentContinuation) {
        this.runtimeBindingService = runtimeBindingService;
        this.runtimeExecutor = runtimeExecutor;
        this.appliedRouteRecorder = appliedRouteRecorder;
        this.interactionEventFactory = interactionEventFactory;
        this.lifecycle = lifecycle;
        this.eventPersistenceCoordinator = eventPersistenceCoordinator;
        this.eventIoScheduler = eventIoScheduler;
        this.domainAgentContinuation = domainAgentContinuation;
    }

    Flux<ChatEvent> execute(Request request) {
        ChatInteractionRequest interaction = request.claim().request();
        InteractionRunLifecycle.InheritedRunState inheritedState =
                lifecycle.inheritedRunState(request.user(), interaction);
        RelayOutputMode relayOutputMode = inheritedState.relayOutputMode();
        Map<String, Object> domainSnapshot = domainAgent(interaction)
                ? DomainAgentQuestionnaireContext.require(interaction) : Map.of();
        RouteTarget route = domainAgent(interaction)
                ? RouteTarget.domainAgent(DomainAgentQuestionnaireContext.text(domainSnapshot.get("skillId")),
                        DomainAgentQuestionnaireContext.text(domainSnapshot.get("routeSource")), 1.0,
                        "continue domain agent questionnaire")
                : relayOutputMode == RelayOutputMode.ANSWER_STREAM_ONLY
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
                domainAgent(interaction) ? IntentExpertContext.withScope(lifecycle.metadata(interaction),
                        IntentExpertContext.fromMetadata(domainSnapshot).orElse(null)) : lifecycle.metadata(interaction),
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
                domainAgent(interaction) ? DomainAgentQuestionnaireContext.strings(domainSnapshot.get("documentIds")) : List.of(),
                dispatchState);
        InteractionExecution execution = new InteractionExecution(
                request,
                interaction,
                run,
                responseEvent,
                route,
                executionClaim,
                bindingRef,
                assistant,
                context);
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
        if (domainAgent(interaction)) {
            RuntimeBinding binding = runtimeBindingService.resumeDomainAgentForInteraction(
                    interaction, request.runId(), executionClaim,
                    DomainAgentQuestionnaireContext.text(DomainAgentQuestionnaireContext.require(interaction).get("skillId")));
            return new InteractionBindingLifecycle(binding, true, dispatchState);
        }
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
        if (domainAgent(execution.interaction())) {
            if (domainAgentContinuation == null) {
                return Flux.error(new IllegalStateException("DomainAgent questionnaire continuation is not configured"));
            }
            // 首个答案事件之前初始化正文，Stop partial 也必须保留发问前已提交的内容。
            domainAgentContinuation.prepareAssistant(execution.pipeline(), execution.interaction());
        }
        appliedRouteRecorder.bindResolvedRouteRequired(
                execution.run(), execution.route(), binding, execution.executionClaim(),
                execution.assistant().persistenceState());
        execution.assistant().messageSkill().replace(execution.route().invocationSkillId());
        Sinks.One<Void> responsePersisted = Sinks.one();
        ChatEvent response = domainAgent(execution.interaction())
                ? new PersistenceAcknowledgedEvent(execution.responseEvent(), responsePersisted)
                : execution.responseEvent();
        return Flux.concat(
                Flux.just(response),
                (domainAgent(execution.interaction()) ? responsePersisted.asMono() : Mono.<Void>empty())
                        .then(eventPersistenceCoordinator.requireCurrentOwnerRunning(
                                execution.executionClaim(), "before-runtime-interaction"))
                        .then(Mono.fromRunnable(() -> appliedRouteRecorder.markRuntimeDispatchStartedRequired(
                                execution.run(),
                                execution.route(),
                                binding,
                                execution.executionClaim(),
                                execution.assistant().persistenceState())))
                        .thenMany(Flux.defer(() -> runtimeEvents(execution, bindingLifecycle))));
    }

    private Flux<ChatEvent> runtimeEvents(InteractionExecution execution, InteractionBindingLifecycle bindingLifecycle) {
        RuntimeBinding binding = bindingLifecycle.binding();
        Map<String, Object> metadata = new LinkedHashMap<>(runtimeMetadata(binding, execution.route()));
        if (domainAgent(execution.interaction())) {
            metadata.put("userMessageId", execution.interaction().userMessageId());
            metadata.put("skillId", execution.route().selectedAgentCode());
        }
        Flux<ChatEvent> source = Flux.defer(() -> runtimeExecutor
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
                                        Map.copyOf(metadata),
                                        bindingLifecycle.dispatchState())));
        return domainAgent(execution.interaction())
                ? domainAgentContinuation.execute(execution.pipeline(), execution.interaction(),
                        execution.request().claim().responsePayload(), execution.request().forwardHeaders(),
                        execution.request().traceContext(), source)
                : source;
    }

    private Map<String, Object> runtimeMetadata(RuntimeBinding binding, RouteTarget route) {
        Map<String, Object> metadata = new LinkedHashMap<>(
                RuntimeProfileMetadata.copyBindingProfileAsRunMetadata(binding.metadata()));
        if (RuntimeProfileMetadata.isPinnedDomainExpert(binding.metadata())
                || IntentExpertContext.scopedDomainExpert(binding.metadata())) {
            metadata.putAll(binding.metadata());
            metadata.put("routeSource", route.routeSource());
        }
        metadata.putAll(RelayOutputModeMetadata.runMetadataOverlay(route));
        return Map.copyOf(metadata);
    }

    private Mono<Void> cleanupUnstartedInteraction(
            ChatInteractionRequest interaction,
            String runId,
            AtomicReference<RuntimeBinding> bindingRef,
            InteractionBindingLifecycle bindingLifecycle,
            String terminationSignal) {
        if (!bindingLifecycle.restoreUnstartedQuestionnaire()
                || bindingLifecycle.dispatchState().responseDispatched()) {
            return Mono.empty();
        }
        return Mono.<Void>fromRunnable(() -> {
                    RuntimeBinding binding = bindingLifecycle.binding();
                    boolean restored = domainAgent(interaction)
                            ? runtimeBindingService.restoreUnstartedDomainAgentInteraction(binding, runId, interaction.sourceRunId())
                            : runtimeBindingService.restoreUnstartedRelayInteraction(binding, runId, interaction.sourceRunId());
                    if (restored) {
                        bindingLifecycle.dispatchState().markBindingRestored();
                        bindingRef.compareAndSet(binding, binding.withRun(interaction.sourceRunId(),
                                domainAgent(interaction) ? binding.expiresAt() : null));
                    } else {
                        bindingLifecycle.dispatchState().markBindingRestoreFailed();
                    }
                })
                .subscribeOn(eventIoScheduler)
                .onErrorResume(ex -> {
                    bindingLifecycle.dispatchState().markBindingRestoreFailed();
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_WRITE_FAILED,
                                    "Unstarted Runtime interaction binding restore failed")
                            .runId(runId)
                            .sessionId(interaction.sessionId())
                            .operation(interaction.runtimeProvider() + ".interaction.binding-restore")
                            .attribute("bindingId", bindingLifecycle.binding().id())
                            .attribute("terminationSignal", terminationSignal)
                            .build(), ex);
                    return Mono.empty();
                })
                .then();
    }

    private boolean domainAgent(ChatInteractionRequest interaction) {
        return interaction != null && "domain-agent".equals(interaction.runtimeProvider());
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
            AssistantAssembly assistant,
            RunEventPipelineContext pipeline
    ) {
    }

    private static final class InteractionBindingLifecycle {
        private final RuntimeBinding binding;
        private final boolean restoreUnstartedQuestionnaire;
        private final RuntimeInteractionDispatchState dispatchState;

        private InteractionBindingLifecycle(
                RuntimeBinding binding,
                boolean restoreUnstartedQuestionnaire,
                RuntimeInteractionDispatchState dispatchState) {
            this.binding = binding;
            this.restoreUnstartedQuestionnaire = restoreUnstartedQuestionnaire;
            this.dispatchState = dispatchState;
        }

        private RuntimeBinding binding() {
            return binding;
        }

        private RuntimeInteractionDispatchState dispatchState() {
            return dispatchState;
        }

        private boolean restoreUnstartedQuestionnaire() {
            return restoreUnstartedQuestionnaire;
        }
    }
}
