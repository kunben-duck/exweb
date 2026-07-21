package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.mapper.ChatIntentMapper;
import com.huawei.it.ex.one.chat.application.mapper.ChatRuntimeMapper;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentControlPolicy;
import com.huawei.it.ex.one.chat.application.model.DomainAgentClarifiedContinuation;
import com.huawei.it.ex.one.chat.application.model.DomainAgentRefusalRunContext;
import com.huawei.it.ex.one.chat.application.model.DomainAgentRerouteState;
import com.huawei.it.ex.one.chat.application.model.PersistenceAcknowledgedEvent;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.intent.application.model.RouteType;
import com.huawei.it.ex.one.intent.application.model.IntentDecision;
import com.huawei.it.ex.one.intent.application.model.RouteSignalFrame;
import com.huawei.it.ex.one.intent.application.model.RouteSignalResult;
import com.huawei.it.ex.one.intent.application.service.IntentDecisionService;
import com.huawei.it.ex.one.intent.application.model.IntentRoutingFailedException;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentBindingCommand;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentRefusal;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBindingResolution;
import com.huawei.it.ex.one.runtime.application.model.RuntimeExecutionContext;
import com.huawei.it.ex.one.runtime.application.model.RuntimeSessionMode;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingService;
import com.huawei.it.ex.one.runtime.application.service.RuntimeExecutionService;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

/** Coordinates the existing DomainAgent refusal and reroute state machine. */
@Component
public class DomainAgentRefusalCoordinator {
    private final RuntimeExecutionService agentRuntimeExecutor;
    private final IntentDecisionService routeSignalService;
    private final RuntimeBindingService runtimeBindingService;
    private final DomainAgentControlPolicy controlPolicy;
    private final RouteDecisionRecorder routeDecisionRecorder;
    private final RouteResolutionCoordinator routeResolutionCoordinator;
    private final ChatRunExecutionGateCoordinator runExecutionGateCoordinator;
    private final Scheduler eventIoScheduler;
    private final InteractionEventFactory interactionEventFactory = new InteractionEventFactory();
    private final DomainAgentRefusalEventFactory eventFactory = new DomainAgentRefusalEventFactory();
    private final DomainAgentRerouteStateMapper rerouteStateMapper = new DomainAgentRerouteStateMapper();
    private final DomainAgentBindingPolicy bindingPolicy;

    public DomainAgentRefusalCoordinator(
            RuntimeExecutionService agentRuntimeExecutor,
            IntentDecisionService routeSignalService,
            RuntimeBindingService runtimeBindingService,
            DomainAgentControlPolicy controlPolicy,
            RouteDecisionRecorder routeDecisionRecorder,
            RouteResolutionCoordinator routeResolutionCoordinator,
            ChatRunExecutionGateCoordinator runExecutionGateCoordinator,
            @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler) {
        this.agentRuntimeExecutor = agentRuntimeExecutor;
        this.routeSignalService = routeSignalService;
        this.runtimeBindingService = runtimeBindingService;
        this.controlPolicy = controlPolicy;
        this.routeDecisionRecorder = routeDecisionRecorder;
        this.routeResolutionCoordinator = routeResolutionCoordinator;
        this.runExecutionGateCoordinator = runExecutionGateCoordinator;
        this.eventIoScheduler = eventIoScheduler;
        this.bindingPolicy = new DomainAgentBindingPolicy(runtimeBindingService);
    }

    public Flux<ChatEvent> execute(DomainAgentRefusalRunContext context) {
        if (context.route() == null || context.route().selectedAgentCode() == null
                || context.route().selectedAgentCode().isBlank()) {
            return Flux.error(new IllegalStateException("DomainAgent 路由缺少目标 ID"));
        }
        AtomicReference<DomainAgentRefusal> refusalRef = new AtomicReference<>();
        AtomicReference<Sinks.One<Void>> refusalPersistedRef = new AtomicReference<>();
        Flux<ChatEvent> current = agentRuntimeExecutor.execute(new RuntimeExecutionContext(
                        ChatRuntimeMapper.command(context.command()), context.runId(),
                        ChatRuntimeMapper.memory(context.memory()), ChatRuntimeMapper.intent(context.intentDecision()),
                        ChatRuntimeMapper.route(context.route()), context.user(), context.bindingRef().get(),
                        RuntimeSessionMode.RESUME, context.forwardHeaders(),
                        ChatRuntimeMapper.documents(context.documents()), context.traceContext()))
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

    private Flux<ChatEvent> continueAfterRefusal(
            DomainAgentRefusalRunContext context, DomainAgentRefusal refusal) {
        if (refusal == null) {
            return Flux.empty();
        }
        markRejectedAutomaticBindingNotRoutable(context, refusal);
        String currentDomainAgentId = context.route().selectedAgentCode();
        Set<String> rejected = new HashSet<>(context.rejectedDomainAgentIds());
        rejected.add(currentDomainAgentId);
        if (context.rerouteCount() >= controlPolicy.maxReroutes()) {
            routeDecisionRecorder.foldClarificationsWithoutDecision(context.user(), context.session().id());
            return Flux.just(rerouteMetadata(context, refusal, null, "MAX_REROUTES_REACHED"));
        }
        ChatCommand rerouteCommand = eventFactory.commandWithDomainRejectContext(
                context.command(), currentDomainAgentId, refusal);
        String rerouteIntentQuery = rerouteCommand.metadata().containsKey("intentClarification")
                ? blankToDefault(context.routeMemoryQuery(), rerouteCommand.message())
                : InteractionContinuationCoordinator.answerWithAttachments(
                        rerouteCommand.message(), rerouteCommand.attachments());
        RerouteContext rerouteContext = new RerouteContext(
                context, refusal, currentDomainAgentId, rejected, rerouteIntentQuery);
        return requireCurrentOwnerRunning(context.executionClaim(), "before-domain-agent-reroute")
                .thenMany(Flux.defer(() -> routeSignalService.routeInitialWithProgress(
                        ChatIntentMapper.toRouteRequest(
                                new ChatIntentMapper.RouteRequestInput(
                                        context.runId(), context.user(), context.session(), rerouteCommand,
                                        rerouteCommand.attachments(), context.memory(), rerouteIntentQuery)))))
                .concatMap(frame -> processRerouteFrame(rerouteContext, frame));
    }

    private Flux<ChatEvent> processRerouteFrame(RerouteContext reroute, RouteSignalFrame frame) {
        DomainAgentRefusalRunContext context = reroute.context();
        if (frame.eventFrame()) {
            return Flux.just(frame.event());
        }
        if (frame.progressFrame()) {
            return Flux.just(interactionEventFactory.routeProgressEvent(
                    context.runId(), context.session().id(), frame.progress()));
        }
        return requireCurrentOwnerRunning(context.executionClaim(), "after-domain-agent-reroute")
                .thenMany(Flux.defer(() -> continueAfterReroute(reroute, frame.result())));
    }

    private Flux<ChatEvent> continueAfterReroute(RerouteContext reroute, RouteSignalResult nextSignal) {
        DomainAgentRefusalRunContext context = reroute.context();
        DomainAgentRefusal refusal = reroute.refusal();
        if (nextSignal.waitingIntentClarification()) {
            return Flux.concat(
                    Flux.just(rerouteMetadata(
                            context, refusal, nextSignal.route(), "INTENT_CLARIFICATION_REQUIRED")),
                    interactionEventFactory.intentClarificationWaitingBody(
                            context.runId(), context.session().id(),
                            clarificationPayload(reroute, nextSignal.intentClarificationPayload())));
        }
        RouteTarget nextRoute = nextSignal.route();
        if (nextSignal.failRunOnIntentFailure()) {
            context.bindingRef().set(markRejectedBindingNotRoutable(context.bindingRef().get(), refusal));
            recordIntentIfPresent(context, nextSignal.intentDecision(), null);
            routeDecisionRecorder.foldClarificationsWithoutDecision(context.user(), context.session().id());
            return Flux.error(new IntentRoutingFailedException(nextSignal.intentFailureReason()));
        }
        if (nextRoute != null && nextRoute.type() == RouteType.AGENT_RUNTIME) {
            return continueWithRelay(reroute, nextSignal, nextRoute);
        }
        if (unavailableDomainAgent(reroute, nextRoute)) {
            routeDecisionRecorder.foldClarificationsWithoutDecision(context.user(), context.session().id());
            return Flux.just(rerouteMetadata(context, refusal, nextRoute, "NO_AVAILABLE_DOMAIN_AGENT"));
        }
        return continueWithDomainAgent(reroute, nextSignal, nextRoute);
    }

    private Flux<ChatEvent> continueWithRelay(RerouteContext reroute, RouteSignalResult signal,
                                              RouteTarget nextRoute) {
        DomainAgentRefusalRunContext context = reroute.context();
        recordIntentIfPresent(context, signal.intentDecision(), nextRoute);
        if (protectedRouteSource(routeSource(context.bindingRef().get()))) {
            return Flux.just(routeSwitchConfirmationRequest(reroute, signal));
        }
        context.bindingRef().set(markRejectedBindingNotRoutable(context.bindingRef().get(), reroute.refusal()));
        RuntimeBindingResolution resolution = runtimeBindingService.resolveForRun(
                context.user().tenantId(), context.user().ownerUserId(), context.session().id(),
                context.runId(), bindingPolicy.runtimeBindingLeafId(context.command()));
        context.bindingRef().set(resolution.binding());
        context.routeRef().set(nextRoute);
        routeDecisionRecorder.bindResolvedRoute(context.runId(), nextRoute, resolution.binding());
        MemoryContext runtimeMemory = recordAppliedRoute(reroute, signal, nextRoute, resolution.binding());
        ChatCommand runtimeCommand = runtimeCommand(context, nextRoute, signal.intentDecision());
        String action = signal.intentFailure() ? "RELAY_FALLBACK" : "ROUTE_TO_RELAY";
        return Flux.concat(
                Flux.just(rerouteMetadata(context, reroute.refusal(), nextRoute, action)),
                requireCurrentOwnerRunning(context.executionClaim(), "before-relay-reroute-runtime")
                        .thenMany(Flux.defer(() -> agentRuntimeExecutor.execute(new RuntimeExecutionContext(
                                ChatRuntimeMapper.command(runtimeCommand), context.runId(),
                                ChatRuntimeMapper.memory(runtimeMemory),
                                ChatRuntimeMapper.intent(signal.intentDecision()),
                                ChatRuntimeMapper.route(nextRoute), context.user(), resolution.binding(),
                                resolution.sessionMode(), context.forwardHeaders(),
                                ChatRuntimeMapper.documents(context.documents()), context.traceContext())))));
    }

    private Flux<ChatEvent> continueWithDomainAgent(RerouteContext reroute, RouteSignalResult signal,
                                                    RouteTarget nextRoute) {
        DomainAgentRefusalRunContext context = reroute.context();
        recordIntentIfPresent(context, signal.intentDecision(), nextRoute);
        String currentRouteSource = routeSource(context.bindingRef().get());
        if (protectedRouteSource(currentRouteSource)
                && !reroute.currentDomainAgentId().equals(nextRoute.selectedAgentCode())) {
            return Flux.just(routeSwitchConfirmationRequest(reroute, signal));
        }
        context.bindingRef().set(markRejectedBindingNotRoutable(context.bindingRef().get(), reroute.refusal()));
        RuntimeBinding nextBinding = runtimeBindingService.bindDomainAgentForRun(new DomainAgentBindingCommand(
                context.user().tenantId(), context.user().ownerUserId(), context.session().id(),
                context.runId(), bindingPolicy.runtimeBindingLeafId(context.command()),
                nextRoute.selectedAgentCode(), nextRoute.routeSource(),
                routeResolutionCoordinator.domainAgentBindingMetadata(nextRoute, signal.intentDecision())));
        context.bindingRef().set(nextBinding);
        context.routeRef().set(nextRoute);
        routeDecisionRecorder.bindResolvedRoute(context.runId(), nextRoute, nextBinding);
        MemoryContext runtimeMemory = recordAppliedRoute(reroute, signal, nextRoute, nextBinding);
        ChatCommand runtimeCommand = runtimeCommand(context, nextRoute, signal.intentDecision());
        DomainAgentRefusalRunContext nextContext = new DomainAgentRefusalRunContext(
                runtimeCommand, context.runId(), context.session(), runtimeMemory, nextRoute, context.user(),
                context.routeRef(), context.bindingRef(), context.executionClaim(), context.forwardHeaders(),
                context.traceContext(), signal.intentDecision(), context.documents(),
                reroute.rejectedDomainAgentIds(), context.rerouteCount() + 1, context.routeMemoryQuery());
        return Flux.concat(
                Flux.just(rerouteMetadata(context, reroute.refusal(), nextRoute, "AUTO_SWITCH")),
                requireCurrentOwnerRunning(context.executionClaim(), "before-domain-agent-reroute-runtime")
                        .thenMany(Flux.defer(() -> execute(nextContext))));
    }

    private MemoryContext recordAppliedRoute(RerouteContext reroute, RouteSignalResult signal,
                                             RouteTarget nextRoute, RuntimeBinding binding) {
        DomainAgentRefusalRunContext context = reroute.context();
        return routeDecisionRecorder.recordAppliedRouteDecision(new RouteDecisionRecorder.AppliedRouteDecision(
                context.user(), context.session().id(), context.runId(), reroute.intentQuery(),
                signal.intentDecision(), nextRoute, binding, context.memory()));
    }

    private boolean unavailableDomainAgent(RerouteContext reroute, RouteTarget route) {
        return route == null || route.type() != RouteType.DOMAIN_AGENT
                || route.selectedAgentCode() == null || route.selectedAgentCode().isBlank()
                || reroute.rejectedDomainAgentIds().contains(route.selectedAgentCode());
    }

    public DomainAgentRerouteState rerouteState(ChatCommand command) {
        return rerouteStateMapper.from(command);
    }

    public Flux<ChatEvent> continueAfterClarification(
            DomainAgentClarifiedContinuation request, DomainAgentRerouteState state) {
        RuntimeBinding currentBinding = runtimeBindingService.loadDomainAgentForReroute(
                request.user().tenantId(), request.user().ownerUserId(), request.session().id(),
                state.currentBindingId(), state.currentTargetId());
        request.bindingRef().set(currentBinding);
        RouteTarget currentRoute = RouteTarget.domainAgent(
                state.currentTargetId(), state.currentRouteSource(), 1.0,
                "domain agent refusal clarification continuation");
        request.routeRef().set(currentRoute);
        ChatCommand runtimeCommand = rerouteStateMapper.withoutRerouteContext(
                routeResolutionCoordinator.runtimeCommand(
                new RouteResolutionCoordinator.RuntimeCommandRequest(
                        request.runCommand(), request.routeMemoryQuery(), request.documents(),
                        request.routeSignal() == null ? null : request.routeSignal().route(),
                        request.routeSignal() == null ? null : request.routeSignal().intentDecision(),
                        request.runtimeMetadataOverride())));
        DomainAgentRefusalRunContext context = new DomainAgentRefusalRunContext(
                runtimeCommand, request.runId(), request.session(), request.memory(), currentRoute,
                request.user(), request.routeRef(), request.bindingRef(), request.executionClaim(),
                request.forwardHeaders(), request.traceContext(), null, request.documents(),
                state.rejectedDomainAgentIds(), state.rerouteCount(), request.routeMemoryQuery());
        RerouteContext reroute = new RerouteContext(
                context, state.refusal(), state.currentTargetId(), state.rejectedDomainAgentIds(),
                blankToDefault(request.intentRouteMemoryQuery(), request.routeMemoryQuery()));
        return continueAfterReroute(reroute, request.routeSignal());
    }

    private ChatCommand runtimeCommand(
            DomainAgentRefusalRunContext context, RouteTarget route, IntentDecision intent) {
        return routeResolutionCoordinator.runtimeCommand(new RouteResolutionCoordinator.RuntimeCommandRequest(
                context.command(), null, context.documents(), route, intent, null));
    }

    private void markRejectedAutomaticBindingNotRoutable(
            DomainAgentRefusalRunContext context, DomainAgentRefusal refusal) {
        context.bindingRef().set(bindingPolicy.markRejectedAutomatic(context.bindingRef().get(), refusal));
    }

    public RuntimeBinding markRejectedAutomaticBindingNotRoutable(RuntimeBinding binding,
                                                                  DomainAgentRefusal refusal) {
        return bindingPolicy.markRejectedAutomatic(binding, refusal);
    }

    private RuntimeBinding markRejectedBindingNotRoutable(RuntimeBinding binding,
                                                          DomainAgentRefusal refusal) {
        return bindingPolicy.markRejected(binding, refusal);
    }

    private void recordIntentIfPresent(
            DomainAgentRefusalRunContext context, IntentDecision intent, RouteTarget route) {
        routeDecisionRecorder.recordIntent(new RouteDecisionRecorder.IntentRecord(
                context.user(), context.command(), context.runId(), intent, route, 0.0, null));
    }

    public boolean protectedRouteSource(String source) {
        return bindingPolicy.protectedRouteSource(source);
    }

    public String routeSource(RuntimeBinding binding) {
        return bindingPolicy.routeSource(binding);
    }

    private ChatEvent routeSwitchConfirmationRequest(RerouteContext reroute, RouteSignalResult signal) {
        DomainAgentRefusalRunContext context = reroute.context();
        return eventFactory.routeSwitchConfirmationRequest(new DomainAgentRefusalEventFactory.SwitchConfirmation(
                context.runId(), context.session().id(), context.route().selectedAgentCode(),
                context.bindingRef().get(), reroute.refusal(), signal, context.command().message(),
                reroute.intentQuery()));
    }

    private Map<String, Object> clarificationPayload(RerouteContext reroute,
                                                     Map<String, Object> requestPayload) {
        DomainAgentRefusalRunContext context = reroute.context();
        return eventFactory.clarificationPayload(new DomainAgentRefusalEventFactory.ReroutePayloadContext(
                reroute.currentDomainAgentId(), context.bindingRef().get(), reroute.refusal(),
                context.rerouteCount(), reroute.rejectedDomainAgentIds(), context.routeMemoryQuery(),
                context.command().message()), requestPayload);
    }

    private ChatEvent rerouteMetadata(DomainAgentRefusalRunContext context, DomainAgentRefusal refusal,
                                      RouteTarget nextRoute, String action) {
        return eventFactory.rerouteMetadata(new DomainAgentRefusalEventFactory.RerouteMetadata(
                context.runId(), context.session().id(), context.route().selectedAgentCode(),
                refusal, nextRoute, action));
    }

    private Mono<Void> requireCurrentOwnerRunning(RunExecutionClaim claim, String stage) {
        return runExecutionGateCoordinator.requireCurrentOwnerRunning(claim, stage);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private record RerouteContext(
            DomainAgentRefusalRunContext context,
            DomainAgentRefusal refusal,
            String currentDomainAgentId,
            Set<String> rejectedDomainAgentIds,
            String intentQuery
    ) {
    }
}
