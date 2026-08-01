package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.application.service.routing.IntentRoutingFailedException;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalFrame;
import com.huawei.it.ex.one.application.service.routing.RouteSignalRequest;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.DomainAgentBindingCommand;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingResolution;
import com.huawei.it.ex.one.application.service.runtime.RuntimeExecutionContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.MessageCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.routing.RouteType;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Coordinates the existing DomainAgent refusal and reroute state machine. */
final class DomainAgentRefusalCoordinator {
    private final AgentRuntimeExecutor agentRuntimeExecutor;
    private final RouteSignalApplicationService routeSignalService;
    private final RuntimeBindingApplicationService runtimeBindingService;
    private final DomainAgentProperties domainAgentProperties;
    private final AppliedRouteRecorder appliedRouteRecorder;
    private final RouteResolutionCoordinator routeResolutionCoordinator;
    private final ChatRunLeaseApplicationService chatRunLeaseService;
    private final Scheduler eventIoScheduler;
    private final DomainAgentRefusalEventFactory eventFactory = new DomainAgentRefusalEventFactory();
    private final DomainAgentRerouteStateMapper rerouteStateMapper = new DomainAgentRerouteStateMapper();
    private final DomainAgentBindingPolicy bindingPolicy;
    private final DomainAgentReplacementExecutor replacementExecutor;

    DomainAgentRefusalCoordinator(AgentRuntimeExecutor agentRuntimeExecutor,
                                  RouteSignalApplicationService routeSignalService,
                                  RuntimeBindingApplicationService runtimeBindingService,
                                  DomainAgentProperties domainAgentProperties,
                                  AppliedRouteRecorder appliedRouteRecorder,
                                  RouteResolutionCoordinator routeResolutionCoordinator,
                                  ChatRunLeaseApplicationService chatRunLeaseService,
                                  Scheduler eventIoScheduler) {
        this(agentRuntimeExecutor,
                routeSignalService,
                runtimeBindingService,
                domainAgentProperties,
                appliedRouteRecorder,
                routeResolutionCoordinator,
                chatRunLeaseService,
                eventIoScheduler,
                eventIoScheduler,
                new RuntimeBindingDispatchCompensator(
                        runtimeBindingService,
                        eventIoScheduler,
                        domainAgentProperties));
    }

    DomainAgentRefusalCoordinator(AgentRuntimeExecutor agentRuntimeExecutor,
                                  RouteSignalApplicationService routeSignalService,
                                  RuntimeBindingApplicationService runtimeBindingService,
                                  DomainAgentProperties domainAgentProperties,
                                  AppliedRouteRecorder appliedRouteRecorder,
                                  RouteResolutionCoordinator routeResolutionCoordinator,
                                  ChatRunLeaseApplicationService chatRunLeaseService,
                                  Scheduler eventIoScheduler,
                                  Scheduler controlIoScheduler) {
        this(agentRuntimeExecutor,
                routeSignalService,
                runtimeBindingService,
                domainAgentProperties,
                appliedRouteRecorder,
                routeResolutionCoordinator,
                chatRunLeaseService,
                eventIoScheduler,
                controlIoScheduler,
                new RuntimeBindingDispatchCompensator(
                        runtimeBindingService,
                        controlIoScheduler == null ? eventIoScheduler : controlIoScheduler,
                        domainAgentProperties));
    }

    DomainAgentRefusalCoordinator(AgentRuntimeExecutor agentRuntimeExecutor,
                                  RouteSignalApplicationService routeSignalService,
                                  RuntimeBindingApplicationService runtimeBindingService,
                                  DomainAgentProperties domainAgentProperties,
                                  AppliedRouteRecorder appliedRouteRecorder,
                                  RouteResolutionCoordinator routeResolutionCoordinator,
                                  ChatRunLeaseApplicationService chatRunLeaseService,
                                  Scheduler eventIoScheduler,
                                  Scheduler controlIoScheduler,
                                  RuntimeBindingDispatchCompensator bindingCompensator) {
        this.agentRuntimeExecutor = agentRuntimeExecutor;
        this.routeSignalService = routeSignalService;
        this.runtimeBindingService = runtimeBindingService;
        this.domainAgentProperties = domainAgentProperties;
        this.appliedRouteRecorder = appliedRouteRecorder;
        this.routeResolutionCoordinator = routeResolutionCoordinator;
        this.chatRunLeaseService = chatRunLeaseService;
        this.eventIoScheduler = eventIoScheduler;
        this.bindingPolicy = new DomainAgentBindingPolicy(runtimeBindingService, domainAgentProperties);
        this.replacementExecutor = new DomainAgentReplacementExecutor(
                agentRuntimeExecutor,
                runtimeBindingService,
                appliedRouteRecorder,
                routeResolutionCoordinator,
                bindingPolicy,
                eventFactory,
                chatRunLeaseService,
                eventIoScheduler,
                controlIoScheduler,
                domainAgentProperties,
                bindingCompensator);
    }

    Flux<ChatEvent> execute(DomainAgentRunContext context) {
        if (context.route() == null || context.route().selectedAgentCode() == null
                || context.route().selectedAgentCode().isBlank()) {
            return Flux.error(new IllegalStateException("DomainAgent 路由缺少目标 ID"));
        }
        AtomicReference<DomainAgentRefusal> refusalRef = new AtomicReference<>();
        AtomicReference<Sinks.One<Void>> refusalPersistedRef = new AtomicReference<>();
        Flux<ChatEvent> current = agentRuntimeExecutor.execute(runtimeExecutionContext(context))
                .map(event -> eventFactory.enrichControlEvent(event, context.route().selectedAgentCode()))
                .map(event -> acknowledgementEvent(event, refusalRef, refusalPersistedRef))
                .takeUntil(event -> refusalRef.get() != null);
        return current.concatWith(Flux.defer(() -> {
            Sinks.One<Void> persisted = refusalPersistedRef.get();
            Mono<Void> persistenceGate = persisted == null
                    ? Mono.empty()
                    : persisted.asMono().publishOn(eventIoScheduler);
            return persistenceGate.thenMany(Flux.defer(() -> continueAfterRefusal(context, refusalRef.get()))
                    .subscribeOn(eventIoScheduler));
        }));
    }

    DomainAgentRerouteState rerouteState(ChatCommand command) {
        return rerouteStateMapper.from(command);
    }

    Flux<ChatEvent> continueAfterClarification(ClarifiedContinuation request,
                                               DomainAgentRerouteState state) {
        RuntimeBinding currentBinding = runtimeBindingService.loadDomainAgentForReroute(
                request.context().user().tenantId(),
                request.context().user().ownerUserId(),
                request.context().session().id(),
                state.currentBindingId(),
                state.currentTargetId());
        request.context().bindingRef().set(currentBinding);
        RouteTarget currentRoute = RouteTarget.domainAgent(
                state.currentTargetId(),
                state.currentRouteSource(),
                1.0,
                "domain agent refusal clarification continuation");
        request.context().routeRef().set(currentRoute);
        ChatCommand runtimeCommand = routeResolutionCoordinator.withoutDomainAgentRerouteContext(
                routeResolutionCoordinator.runtimeCommand(
                        new RouteResolutionCoordinator.RuntimeCommandRequest(
                                request.context().command(),
                                request.context().routeMemoryQuery(),
                                request.context().documents(),
                                request.routeSignal() == null ? null : request.routeSignal().route(),
                                request.routeSignal() == null ? null : request.routeSignal().intentDecision(),
                                request.runtimeMetadataOverride())));
        DomainAgentRunContext context = continuationRunContext(request, state, currentRoute, runtimeCommand);
        DomainAgentRerouteContext reroute = new DomainAgentRerouteContext(
                context,
                state.refusal(),
                state.lastIntentRejectReason(),
                state.currentTargetId(),
                state.rejectedDomainAgentIds(),
                blankToDefault(request.intentRouteMemoryQuery(), request.context().routeMemoryQuery()),
                request.agentMode());
        return continueAfterReroute(reroute, request.routeSignal());
    }

    RuntimeBinding markRejectedAutomaticBindingNotRoutable(RuntimeBinding binding,
                                                           DomainAgentRefusal refusal) {
        return bindingPolicy.markRejectedAutomatic(binding, refusal);
    }

    boolean protectedRouteSource(String routeSource) {
        return bindingPolicy.protectedRouteSource(routeSource);
    }

    boolean requiresRouteSwitchConfirmation(String routeSource) {
        return bindingPolicy.requiresRouteSwitchConfirmation(routeSource);
    }

    String routeSource(RuntimeBinding binding) {
        return bindingPolicy.routeSource(binding);
    }

    private RuntimeExecutionContext runtimeExecutionContext(DomainAgentRunContext context) {
        return new RuntimeExecutionContext(
                context.command(),
                context.runId(),
                context.memory(),
                context.intentDecision(),
                context.route(),
                context.user(),
                context.bindingRef().get(),
                RuntimeSessionMode.RESUME,
                context.forwardHeaders(),
                context.documents(),
                context.traceContext());
    }

    private ChatEvent acknowledgementEvent(
            ChatEvent event,
            AtomicReference<DomainAgentRefusal> refusalRef,
            AtomicReference<Sinks.One<Void>> refusalPersistedRef) {
        DomainAgentRefusal refusal = DomainAgentRefusal.from(event);
        if (refusal == null || !refusalRef.compareAndSet(null, refusal)) {
            return event;
        }
        Sinks.One<Void> persisted = Sinks.one();
        refusalPersistedRef.set(persisted);
        return new PersistenceAcknowledgedEvent(event, persisted);
    }

    private Flux<ChatEvent> continueAfterRefusal(DomainAgentRunContext context,
                                                 DomainAgentRefusal refusal) {
        if (refusal == null) {
            return Flux.empty();
        }
        context.bindingRef().set(bindingPolicy.markRejectedAutomatic(context.bindingRef().get(), refusal));
        String currentDomainAgentId = context.route().selectedAgentCode();
        Set<String> rejected = new HashSet<>(context.rejectedDomainAgentIds());
        rejected.add(currentDomainAgentId);
        if (context.rerouteCount() >= domainAgentProperties.normalizedMaxReroutes()) {
            appliedRouteRecorder.completeWithoutRoute(context.user(), context.session().id());
            return Flux.just(eventFactory.rerouteMetadata(
                    context,
                    refusal,
                    null,
                    "MAX_REROUTES_REACHED"));
        }
        DomainAgentRejectReason rejectReason = DomainAgentRejectReason.from(
                rejectedIntentName(context),
                refusal);
        ChatCommand rerouteCommand = eventFactory.commandWithDomainRejectContext(
                context.command(),
                rejectReason);
        String rerouteIntentQuery = rerouteCommand.metadata().containsKey("intentClarification")
                ? blankToDefault(context.routeMemoryQuery(), rerouteCommand.message())
                : IntentClarificationContextAssembler.answerWithAttachments(
                        rerouteCommand.message(),
                        rerouteCommand.attachments());
        DomainAgentRerouteContext reroute = new DomainAgentRerouteContext(
                context,
                refusal,
                rejectReason,
                currentDomainAgentId,
                rejected,
                rerouteIntentQuery,
                null);
        return requireCurrentOwnerRunning(context.executionClaim(), "before-domain-agent-reroute")
                .thenMany(Flux.defer(() -> routeSignalService.routeInitialWithProgress(new RouteSignalRequest(
                        context.runId(),
                        context.user(),
                        context.session(),
                        rerouteCommand,
                        rerouteCommand.attachments(),
                        context.memory(),
                        rerouteIntentQuery))))
                .concatMap(frame -> processRerouteFrame(reroute, frame));
    }

    private Flux<ChatEvent> processRerouteFrame(DomainAgentRerouteContext reroute,
                                                RouteSignalFrame frame) {
        DomainAgentRunContext context = reroute.context();
        if (frame.eventFrame()) {
            return Flux.just(frame.event());
        }
        if (frame.progressFrame()) {
            return Flux.just(eventFactory.routeProgress(
                    context.runId(),
                    context.session().id(),
                    frame.progress()));
        }
        return requireCurrentOwnerRunning(context.executionClaim(), "after-domain-agent-reroute")
                .thenMany(Flux.defer(() -> continueAfterReroute(reroute, frame.result())));
    }

    private Flux<ChatEvent> continueAfterReroute(DomainAgentRerouteContext reroute,
                                                 RouteSignalResult nextSignal) {
        DomainAgentRunContext context = reroute.context();
        if (nextSignal.waitingIntentClarification()) {
            return clarificationRequired(reroute, nextSignal);
        }
        RouteTarget nextRoute = nextSignal.route();
        if (nextSignal.failRunOnIntentFailure()) {
            context.bindingRef().set(bindingPolicy.markRejected(context.bindingRef().get(), reroute.refusal()));
            appliedRouteRecorder.recordIntent(
                    context.user(),
                    context.command(),
                    context.runId(),
                    nextSignal.intentDecision(),
                    null,
                    0.0,
                    null);
            appliedRouteRecorder.completeWithoutRoute(context.user(), context.session().id());
            return Flux.error(new IntentRoutingFailedException(nextSignal.intentFailureReason()));
        }
        if (nextRoute != null && nextRoute.type() == RouteType.AGENT_RUNTIME) {
            return replacementExecutor.continueWithRelay(reroute, nextSignal, nextRoute);
        }
        if (invalidDomainAgentRoute(nextRoute)) {
            appliedRouteRecorder.completeWithoutRoute(context.user(), context.session().id());
            return Flux.just(eventFactory.rerouteMetadata(
                    context,
                    reroute.refusal(),
                    nextRoute,
                    "NO_AVAILABLE_DOMAIN_AGENT"));
        }
        return replacementExecutor.continueWithDomainAgent(reroute, nextSignal, nextRoute, this::execute);
    }

    private Flux<ChatEvent> clarificationRequired(DomainAgentRerouteContext reroute,
                                                  RouteSignalResult signal) {
        DomainAgentRunContext context = reroute.context();
        RuntimeBinding binding = context.bindingRef().get();
        return Flux.just(
                eventFactory.rerouteMetadata(
                        context,
                        reroute.refusal(),
                        signal.route(),
                        "INTENT_CLARIFICATION_REQUIRED"),
                eventFactory.intentClarificationRequest(
                        context.runId(),
                        context.session().id(),
                        eventFactory.clarificationPayload(
                                reroute,
                                signal.intentClarificationPayload(),
                                bindingPolicy.routeSource(binding))),
                MessageCompletedEvent.of(context.runId(), context.session().id()));
    }

    private DomainAgentRunContext continuationRunContext(ClarifiedContinuation request,
                                                         DomainAgentRerouteState state,
                                                         RouteTarget currentRoute,
                                                         ChatCommand runtimeCommand) {
        DomainAgentRunContext source = request.context();
        return new DomainAgentRunContext(
                runtimeCommand,
                source.runId(),
                source.session(),
                source.memory(),
                currentRoute,
                source.user(),
                source.routeRef(),
                source.bindingRef(),
                source.executionClaim(),
                source.forwardHeaders(),
                source.traceContext(),
                null,
                source.documents(),
                state.rejectedDomainAgentIds(),
                state.rerouteCount(),
                source.routeMemoryQuery());
    }

    private boolean invalidDomainAgentRoute(RouteTarget route) {
        return route == null
                || route.type() != RouteType.DOMAIN_AGENT
                || route.selectedAgentCode() == null
                || route.selectedAgentCode().isBlank();
    }

    private String rejectedIntentName(DomainAgentRunContext context) {
        RuntimeBinding binding = context == null || context.bindingRef() == null
                ? null
                : context.bindingRef().get();
        Object bindingIntentName = binding == null || binding.metadata() == null
                ? null
                : binding.metadata().get("intentName");
        IntentDecision intent = context == null ? null : context.intentDecision();
        String decisionIntentName = intent == null ? null : intent.intentName();
        return blankToDefault(firstText(bindingIntentName, decisionIntentName), "未知意图");
    }

    private Mono<Void> requireCurrentOwnerRunning(RunExecutionClaim claim, String stage) {
        return Mono.fromCallable(() -> {
                    if (!chatRunLeaseService.isCurrentOwnerRunning(claim)) {
                        throw new ChatEventAppendRejectedException(
                                "run execution owner 已失效: runId="
                                        + (claim == null ? null : claim.runId()) + ", stage=" + stage);
                    }
                    return true;
                })
                .subscribeOn(eventIoScheduler)
                .then();
    }

    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    record ClarifiedContinuation(
            DomainAgentRunContext context,
            RouteSignalResult routeSignal,
            String intentRouteMemoryQuery,
            java.util.Map<String, Object> runtimeMetadataOverride,
            com.huawei.it.ex.one.domain.runtime.AgentModeProfile agentMode
    ) {
    }

}
