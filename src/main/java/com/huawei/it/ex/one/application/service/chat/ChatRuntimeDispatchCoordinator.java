package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.service.routing.IntentRoutingFailedException;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalFrame;
import com.huawei.it.ex.one.application.service.routing.RouteSignalRequest;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeExecutionContext;
import com.huawei.it.ex.one.application.service.runtime.SystemResponseExecutor;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteType;

import reactor.core.publisher.Flux;

import java.util.HashSet;

/** Executes the existing Intent routing and selected Runtime dispatch workflow. */
final class ChatRuntimeDispatchCoordinator {
    private final RouteSignalApplicationService routeSignalService;
    private final ChatEventPersistenceCoordinator eventPersistenceCoordinator;
    private final InteractionEventFactory interactionEventFactory;
    private final AppliedRouteRecorder appliedRouteRecorder;
    private final RouteResolutionCoordinator routeResolutionCoordinator;
    private final DomainAgentRefusalCoordinator domainAgentRefusalCoordinator;
    private final SystemResponseExecutor systemResponseExecutor;
    private final AgentRuntimeExecutor agentRuntimeExecutor;

    ChatRuntimeDispatchCoordinator(RouteSignalApplicationService routeSignalService,
                                   ChatEventPersistenceCoordinator eventPersistenceCoordinator,
                                   InteractionEventFactory interactionEventFactory,
                                   AppliedRouteRecorder appliedRouteRecorder,
                                   RouteResolutionCoordinator routeResolutionCoordinator,
                                   DomainAgentRefusalCoordinator domainAgentRefusalCoordinator,
                                   SystemResponseExecutor systemResponseExecutor,
                                   AgentRuntimeExecutor agentRuntimeExecutor) {
        this.routeSignalService = routeSignalService;
        this.eventPersistenceCoordinator = eventPersistenceCoordinator;
        this.interactionEventFactory = interactionEventFactory;
        this.appliedRouteRecorder = appliedRouteRecorder;
        this.routeResolutionCoordinator = routeResolutionCoordinator;
        this.domainAgentRefusalCoordinator = domainAgentRefusalCoordinator;
        this.systemResponseExecutor = systemResponseExecutor;
        this.agentRuntimeExecutor = agentRuntimeExecutor;
    }

    Flux<ChatEvent> execute(RoutePipelineRequest request) {
        Flux<RouteSignalFrame> frames = eventPersistenceCoordinator.requireCurrentOwnerRunning(
                        request.executionClaim(), "before-route")
                .thenMany(Flux.defer(() -> request.routeRef().get() == null
                        ? routeSignalService.routeInitialWithProgress(new RouteSignalRequest(
                                request.runId(),
                                request.user(),
                                request.session(),
                                request.runCommand(),
                                request.attachments(),
                                request.memory(),
                                request.intentQuery()))
                        : Flux.just(RouteSignalFrame.result(
                                RouteSignalResult.of(request.routeRef().get())))));
        return frames.concatMap(frame -> executeFrame(request, frame));
    }

    private Flux<ChatEvent> executeFrame(RoutePipelineRequest request,
                                         RouteSignalFrame frame) {
        if (frame.eventFrame()) {
            return Flux.just(frame.event());
        }
        if (frame.progressFrame()) {
            return Flux.just(interactionEventFactory.routeProgressEvent(
                    request.runId(), request.session().id(), frame.progress()));
        }
        return eventPersistenceCoordinator.requireCurrentOwnerRunning(
                        request.executionClaim(), "after-route")
                .thenMany(Flux.defer(() -> executeResolvedRoute(request, frame.result())));
    }

    private Flux<ChatEvent> executeResolvedRoute(RoutePipelineRequest request,
                                                 RouteSignalResult routeSignal) {
        DomainAgentRerouteState rerouteState =
                domainAgentRefusalCoordinator.rerouteState(request.runCommand());
        if (rerouteState != null) {
            return continueAfterClarifiedDomainAgentRefusal(request, routeSignal, rerouteState);
        }
        if (routeSignal != null && routeSignal.failRunOnIntentFailure()) {
            appliedRouteRecorder.recordIntentSignal(
                    request.user(), request.runCommand(), request.run().id(), routeSignal, null);
            appliedRouteRecorder.completeWithoutRoute(request.user(), request.session().id());
            return Flux.error(new IntentRoutingFailedException(routeSignal.intentFailureReason()));
        }
        RouteResolutionCoordinator.RouteExecutionResolution resolution =
                resolveRoute(request, routeSignal);
        if (resolution.waitingIntentClarification()) {
            appliedRouteRecorder.bindIntentAgentProvider(request.runId());
            return interactionEventFactory.intentClarificationWaitingBody(
                    request.runId(),
                    request.session().id(),
                    resolution.intentClarificationPayload());
        }
        return dispatchResolvedRuntime(request, resolution);
    }

    private RouteResolutionCoordinator.RouteExecutionResolution resolveRoute(
            RoutePipelineRequest request,
            RouteSignalResult routeSignal) {
        RouteResolutionCoordinator.RouteExecutionResolution resolution =
                routeResolutionCoordinator.resolve(
                        new RouteResolutionCoordinator.RouteResolutionRequest(
                                request.user(),
                                request.session(),
                                request.runCommand(),
                                request.attachments(),
                                request.memory(),
                                request.runId(),
                                request.runtimeBindingLeafId(),
                                request.routeRef().get(),
                                request.bindingRef().get(),
                                request.runtimeSessionModeRef().get(),
                                request.agentMode()),
                        routeSignal);
        request.routeRef().set(resolution.route());
        request.bindingRef().set(resolution.binding());
        request.runtimeSessionModeRef().set(resolution.runtimeSessionMode());
        appliedRouteRecorder.bindResolvedRoute(
                request.runId(), resolution.route(), resolution.binding());
        if (resolution.intent() != null) {
            appliedRouteRecorder.recordIntent(
                    request.user(),
                    request.runCommand(),
                    request.run().id(),
                    resolution.intent(),
                    resolution.route(),
                    resolution.intentConfidenceThreshold() == null
                            ? 0.0
                            : resolution.intentConfidenceThreshold(),
                    resolution.intentLatencyMs());
        }
        return resolution;
    }

    private Flux<ChatEvent> dispatchResolvedRuntime(
            RoutePipelineRequest request,
            RouteResolutionCoordinator.RouteExecutionResolution resolution) {
        String appliedRouteQuery = resolution.intent() == null
                ? request.routeMemoryQuery()
                : blankToDefault(
                        request.intentRouteMemoryQuery(), request.routeMemoryQuery());
        MemoryContext runtimeMemory = appliedRouteRecorder.recordAppliedRouteDecision(
                new AppliedRouteRecorder.AppliedRouteDecision(
                        request.user(),
                        request.session().id(),
                        request.runId(),
                        appliedRouteQuery,
                        resolution.intent(),
                        resolution.route(),
                        resolution.binding(),
                        request.memory()));
        ChatCommand runtimeCommand = routeResolutionCoordinator.runtimeCommand(
                new RouteResolutionCoordinator.RuntimeCommandRequest(
                        request.runCommand(),
                        request.routeMemoryQuery(),
                        request.documents(),
                        resolution.route(),
                        resolution.intent(),
                        request.runtimeMetadataOverride()));
        return eventPersistenceCoordinator.requireCurrentOwnerRunning(
                        request.executionClaim(), "before-runtime")
                .thenMany(Flux.defer(() -> executeRuntime(
                        request, resolution, runtimeCommand, runtimeMemory)));
    }

    private Flux<ChatEvent> executeRuntime(
            RoutePipelineRequest request,
            RouteResolutionCoordinator.RouteExecutionResolution resolution,
            ChatCommand runtimeCommand,
            MemoryContext runtimeMemory) {
        return switch (resolution.route().type()) {
            case DOMAIN_AGENT -> domainAgentRefusalCoordinator.execute(new DomainAgentRunContext(
                    runtimeCommand,
                    request.runId(),
                    request.session(),
                    runtimeMemory,
                    resolution.route(),
                    request.user(),
                    request.routeRef(),
                    request.bindingRef(),
                    request.executionClaim(),
                    request.forwardHeaders(),
                    request.traceContext(),
                    resolution.intent(),
                    request.documents(),
                    new HashSet<>(),
                    0,
                    request.routeMemoryQuery()));
            case SYSTEM_RESPONSE -> systemResponseExecutor.execute(
                    runtimeCommand, request.runId(), resolution.intent(), resolution.route());
            case AGENT_RUNTIME -> agentRuntimeExecutor.execute(new RuntimeExecutionContext(
                    runtimeCommand,
                    request.runId(),
                    runtimeMemory,
                    resolution.intent(),
                    resolution.route(),
                    request.user(),
                    request.bindingRef().get(),
                    resolution.runtimeSessionMode(),
                    request.forwardHeaders(),
                    request.documents(),
                    request.traceContext()));
        };
    }

    private Flux<ChatEvent> continueAfterClarifiedDomainAgentRefusal(
            RoutePipelineRequest request,
            RouteSignalResult routeSignal,
            DomainAgentRerouteState state) {
        DomainAgentRunContext context = new DomainAgentRunContext(
                request.runCommand(),
                request.runId(),
                request.session(),
                request.memory(),
                null,
                request.user(),
                request.routeRef(),
                request.bindingRef(),
                request.executionClaim(),
                request.forwardHeaders(),
                request.traceContext(),
                null,
                request.documents(),
                state.rejectedDomainAgentIds(),
                state.rerouteCount(),
                request.routeMemoryQuery());
        return domainAgentRefusalCoordinator.continueAfterClarification(
                new DomainAgentRefusalCoordinator.ClarifiedContinuation(
                        context,
                        routeSignal,
                        request.intentRouteMemoryQuery(),
                        request.runtimeMetadataOverride(),
                        request.agentMode()),
                state);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
