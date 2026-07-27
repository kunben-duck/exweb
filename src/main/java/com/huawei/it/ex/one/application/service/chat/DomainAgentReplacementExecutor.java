package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.DomainAgentBindingCommand;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingResolution;
import com.huawei.it.ex.one.application.service.runtime.RuntimeExecutionContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.function.Function;

/** Applies a resolved replacement Runtime after a DomainAgent refusal. */
final class DomainAgentReplacementExecutor {
    private final AgentRuntimeExecutor agentRuntimeExecutor;
    private final RuntimeBindingApplicationService runtimeBindingService;
    private final AppliedRouteRecorder appliedRouteRecorder;
    private final RouteResolutionCoordinator routeResolutionCoordinator;
    private final DomainAgentBindingPolicy bindingPolicy;
    private final DomainAgentRefusalEventFactory eventFactory;
    private final ChatRunLeaseApplicationService chatRunLeaseService;
    private final Scheduler eventIoScheduler;

    DomainAgentReplacementExecutor(AgentRuntimeExecutor agentRuntimeExecutor,
                                   RuntimeBindingApplicationService runtimeBindingService,
                                   AppliedRouteRecorder appliedRouteRecorder,
                                   RouteResolutionCoordinator routeResolutionCoordinator,
                                   DomainAgentBindingPolicy bindingPolicy,
                                   DomainAgentRefusalEventFactory eventFactory,
                                   ChatRunLeaseApplicationService chatRunLeaseService,
                                   Scheduler eventIoScheduler) {
        this.agentRuntimeExecutor = agentRuntimeExecutor;
        this.runtimeBindingService = runtimeBindingService;
        this.appliedRouteRecorder = appliedRouteRecorder;
        this.routeResolutionCoordinator = routeResolutionCoordinator;
        this.bindingPolicy = bindingPolicy;
        this.eventFactory = eventFactory;
        this.chatRunLeaseService = chatRunLeaseService;
        this.eventIoScheduler = eventIoScheduler;
    }

    Flux<ChatEvent> continueWithRelay(DomainAgentRerouteContext reroute,
                                      RouteSignalResult signal,
                                      RouteTarget nextRoute) {
        DomainAgentRunContext context = reroute.context();
        recordIntent(context, signal.intentDecision(), nextRoute);
        String currentRouteSource = bindingPolicy.routeSource(context.bindingRef().get());
        if (bindingPolicy.requiresRouteSwitchConfirmation(currentRouteSource)) {
            return Flux.just(eventFactory.routeSwitchConfirmationRequest(
                    reroute,
                    signal,
                    currentRouteSource));
        }
        context.bindingRef().set(bindingPolicy.markRejected(context.bindingRef().get(), reroute.refusal()));
        RuntimeBindingResolution resolution = runtimeBindingService.resolveForRun(
                context.user().tenantId(),
                context.user().ownerUserId(),
                context.session().id(),
                context.runId(),
                bindingPolicy.runtimeBindingLeafId(context.command()));
        context.bindingRef().set(resolution.binding());
        context.routeRef().set(nextRoute);
        appliedRouteRecorder.bindResolvedRoute(context.runId(), nextRoute, resolution.binding());
        MemoryContext runtimeMemory = recordAppliedRoute(reroute, signal, nextRoute, resolution.binding());
        ChatCommand runtimeCommand = runtimeCommand(context, nextRoute, signal.intentDecision());
        String action = signal.intentFailure() ? "RELAY_FALLBACK" : "ROUTE_TO_RELAY";
        return Flux.concat(
                Flux.just(eventFactory.rerouteMetadata(context, reroute.refusal(), nextRoute, action)),
                requireCurrentOwnerRunning(context.executionClaim(), "before-relay-reroute-runtime")
                        .thenMany(Flux.defer(() -> agentRuntimeExecutor.execute(new RuntimeExecutionContext(
                                runtimeCommand,
                                context.runId(),
                                runtimeMemory,
                                signal.intentDecision(),
                                nextRoute,
                                context.user(),
                                resolution.binding(),
                                resolution.sessionMode(),
                                context.forwardHeaders(),
                                context.documents(),
                                context.traceContext())))));
    }

    Flux<ChatEvent> continueWithDomainAgent(
            DomainAgentRerouteContext reroute,
            RouteSignalResult signal,
            RouteTarget nextRoute,
            Function<DomainAgentRunContext, Flux<ChatEvent>> continuation) {
        DomainAgentRunContext context = reroute.context();
        recordIntent(context, signal.intentDecision(), nextRoute);
        String currentRouteSource = bindingPolicy.routeSource(context.bindingRef().get());
        if (bindingPolicy.requiresRouteSwitchConfirmation(currentRouteSource)
                && !reroute.currentDomainAgentId().equals(nextRoute.selectedAgentCode())) {
            return Flux.just(eventFactory.routeSwitchConfirmationRequest(
                    reroute,
                    signal,
                    currentRouteSource));
        }
        context.bindingRef().set(bindingPolicy.markRejected(context.bindingRef().get(), reroute.refusal()));
        RuntimeBinding nextBinding = bindDomainAgent(reroute, signal, nextRoute);
        context.bindingRef().set(nextBinding);
        context.routeRef().set(nextRoute);
        appliedRouteRecorder.bindResolvedRoute(context.runId(), nextRoute, nextBinding);
        MemoryContext runtimeMemory = recordAppliedRoute(reroute, signal, nextRoute, nextBinding);
        ChatCommand runtimeCommand = runtimeCommand(context, nextRoute, signal.intentDecision());
        DomainAgentRunContext nextContext = nextRunContext(
                context,
                reroute,
                new ReplacementExecution(signal, nextRoute, runtimeMemory, runtimeCommand));
        return Flux.concat(
                Flux.just(eventFactory.rerouteMetadata(
                        context,
                        reroute.refusal(),
                        nextRoute,
                        "AUTO_SWITCH")),
                requireCurrentOwnerRunning(context.executionClaim(), "before-domain-agent-reroute-runtime")
                        .thenMany(Flux.defer(() -> continuation.apply(nextContext))));
    }

    private RuntimeBinding bindDomainAgent(DomainAgentRerouteContext reroute,
                                           RouteSignalResult signal,
                                           RouteTarget nextRoute) {
        DomainAgentRunContext context = reroute.context();
        return runtimeBindingService.bindDomainAgentForRun(new DomainAgentBindingCommand(
                context.user().tenantId(),
                context.user().ownerUserId(),
                context.session().id(),
                context.runId(),
                bindingPolicy.runtimeBindingLeafId(context.command()),
                nextRoute.selectedAgentCode(),
                nextRoute.routeSource(),
                routeResolutionCoordinator.domainAgentBindingMetadata(nextRoute, signal.intentDecision()),
                reroute.agentMode()));
    }

    private MemoryContext recordAppliedRoute(DomainAgentRerouteContext reroute,
                                             RouteSignalResult signal,
                                             RouteTarget route,
                                             RuntimeBinding binding) {
        DomainAgentRunContext context = reroute.context();
        return appliedRouteRecorder.recordAppliedRouteDecision(
                new AppliedRouteRecorder.AppliedRouteDecision(
                        context.user(),
                        context.session().id(),
                        context.runId(),
                        reroute.intentQuery(),
                        signal.intentDecision(),
                        route,
                        binding,
                        context.memory()));
    }

    private void recordIntent(DomainAgentRunContext context,
                              IntentDecision intent,
                              RouteTarget route) {
        appliedRouteRecorder.recordIntent(
                context.user(),
                context.command(),
                context.runId(),
                intent,
                route,
                0.0,
                null);
    }

    private ChatCommand runtimeCommand(DomainAgentRunContext context,
                                       RouteTarget route,
                                       IntentDecision intent) {
        return routeResolutionCoordinator.runtimeCommand(
                new RouteResolutionCoordinator.RuntimeCommandRequest(
                        context.command(),
                        null,
                        context.documents(),
                        route,
                        intent,
                        null));
    }

    private DomainAgentRunContext nextRunContext(DomainAgentRunContext context,
                                                 DomainAgentRerouteContext reroute,
                                                 ReplacementExecution replacement) {
        return new DomainAgentRunContext(
                replacement.runtimeCommand(),
                context.runId(),
                context.session(),
                replacement.runtimeMemory(),
                replacement.nextRoute(),
                context.user(),
                context.routeRef(),
                context.bindingRef(),
                context.executionClaim(),
                context.forwardHeaders(),
                context.traceContext(),
                replacement.signal().intentDecision(),
                context.documents(),
                reroute.rejectedDomainAgentIds(),
                context.rerouteCount() + 1,
                context.routeMemoryQuery());
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

    private record ReplacementExecution(
            RouteSignalResult signal,
            RouteTarget nextRoute,
            MemoryContext runtimeMemory,
            ChatCommand runtimeCommand
    ) {
    }
}
