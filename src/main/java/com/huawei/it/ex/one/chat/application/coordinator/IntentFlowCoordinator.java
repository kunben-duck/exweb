package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.mapper.ChatIntentMapper;
import com.huawei.it.ex.one.chat.application.mapper.ChatRuntimeMapper;
import com.huawei.it.ex.one.chat.application.model.DomainAgentClarifiedContinuation;
import com.huawei.it.ex.one.chat.application.model.DomainAgentRefusalRunContext;
import com.huawei.it.ex.one.chat.application.model.DomainAgentRerouteState;
import com.huawei.it.ex.one.chat.domain.AttachmentRef;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;
import com.huawei.it.ex.one.document.application.model.UploadedDocument;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.intent.application.model.RouteSignalFrame;
import com.huawei.it.ex.one.intent.application.model.RouteSignalResult;
import com.huawei.it.ex.one.intent.application.service.IntentDecisionService;
import com.huawei.it.ex.one.intent.application.model.IntentRoutingFailedException;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.model.RuntimeExecutionContext;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.runtime.application.model.RuntimeSessionMode;
import com.huawei.it.ex.one.runtime.application.service.RuntimeExecutionService;
import com.huawei.it.ex.one.runtime.application.service.SystemResponseService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Preserves the Intent frame ordering and dispatches the resolved Runtime. */
@Component
public class IntentFlowCoordinator {
    private final IntentDecisionService routeSignalService;
    private final ChatRunExecutionGateCoordinator runExecutionGateCoordinator;
    private final InteractionEventFactory interactionEventFactory;
    private final RouteResolutionCoordinator routeResolutionCoordinator;
    private final RouteDecisionRecorder routeDecisionRecorder;
    private final DomainAgentRefusalCoordinator domainAgentRefusalCoordinator;
    private final SystemResponseService systemResponseExecutor;
    private final RuntimeExecutionService agentRuntimeExecutor;

    public IntentFlowCoordinator(IntentDecisionService routeSignalService,
                                 ChatRunExecutionGateCoordinator runExecutionGateCoordinator,
                                 InteractionEventFactory interactionEventFactory,
                                 RouteResolutionCoordinator routeResolutionCoordinator,
                                 RouteDecisionRecorder routeDecisionRecorder,
                                 DomainAgentRefusalCoordinator domainAgentRefusalCoordinator,
                                 SystemResponseService systemResponseExecutor,
                                 RuntimeExecutionService agentRuntimeExecutor) {
        this.routeSignalService = routeSignalService;
        this.runExecutionGateCoordinator = runExecutionGateCoordinator;
        this.interactionEventFactory = interactionEventFactory;
        this.routeResolutionCoordinator = routeResolutionCoordinator;
        this.routeDecisionRecorder = routeDecisionRecorder;
        this.domainAgentRefusalCoordinator = domainAgentRefusalCoordinator;
        this.systemResponseExecutor = systemResponseExecutor;
        this.agentRuntimeExecutor = agentRuntimeExecutor;
    }

    public Flux<ChatEvent> execute(Request request) {
        Flux<RouteSignalFrame> frames = requireCurrentOwnerRunning(request.executionClaim(), "before-route")
                .thenMany(Flux.defer(() -> request.routeRef().get() == null
                        ? routeSignalService.routeInitialWithProgress(ChatIntentMapper.toRouteRequest(
                                new ChatIntentMapper.RouteRequestInput(
                                        request.runId(), request.user(), request.session(), request.runCommand(),
                                        request.attachments(), request.memory(), request.intentQuery())))
                        : Flux.just(RouteSignalFrame.result(RouteSignalResult.of(request.routeRef().get())))));
        return frames.concatMap(frame -> {
            if (frame.eventFrame()) {
                return Flux.just(frame.event());
            }
            if (frame.progressFrame()) {
                return Flux.just(interactionEventFactory.routeProgressEvent(
                        request.runId(), request.session().id(), frame.progress()));
            }
            return requireCurrentOwnerRunning(request.executionClaim(), "after-route")
                    .thenMany(Flux.defer(() -> executeResolvedRoute(request, frame.result())));
        });
    }

    private Flux<ChatEvent> executeResolvedRoute(Request request, RouteSignalResult routeSignal) {
        DomainAgentRerouteState rerouteState =
                domainAgentRefusalCoordinator.rerouteState(request.runCommand());
        if (rerouteState != null) {
            return domainAgentRefusalCoordinator.continueAfterClarification(
                    new DomainAgentClarifiedContinuation(
                            request.runCommand(), request.runId(), request.session(), request.memory(),
                            request.user(), request.routeRef(), request.bindingRef(), request.executionClaim(),
                            request.forwardHeaders(), request.traceContext(), request.documents(),
                            request.routeMemoryQuery(), request.intentRouteMemoryQuery(),
                            request.runtimeMetadataOverride(), routeSignal),
                    rerouteState);
        }
        if (routeSignal != null && routeSignal.failRunOnIntentFailure()) {
            routeDecisionRecorder.recordIntentSignal(
                    request.user(), request.runCommand(), request.run().id(), routeSignal, null);
            routeDecisionRecorder.foldClarificationsWithoutDecision(request.user(), request.session().id());
            return Flux.error(new IntentRoutingFailedException(routeSignal.intentFailureReason()));
        }
        RouteResolutionCoordinator.RouteExecutionResolution resolution = routeResolutionCoordinator.resolve(
                new RouteResolutionCoordinator.RouteResolutionRequest(
                        request.user(), request.session(), request.runCommand(), request.attachments(), request.memory(),
                        request.runId(), request.runtimeBindingLeafId(), request.routeRef().get(),
                        request.bindingRef().get(), request.runtimeSessionModeRef().get()), routeSignal);
        request.routeRef().set(resolution.route());
        request.bindingRef().set(resolution.binding());
        request.runtimeSessionModeRef().set(resolution.runtimeSessionMode());
        routeDecisionRecorder.bindResolvedRoute(request.runId(), resolution.route(), resolution.binding());
        if (resolution.intent() != null) {
            routeDecisionRecorder.recordIntent(new RouteDecisionRecorder.IntentRecord(
                    request.user(), request.runCommand(), request.run().id(), resolution.intent(),
                    resolution.route(), resolution.intentConfidenceThreshold() == null
                            ? 0.0 : resolution.intentConfidenceThreshold(),
                    resolution.intentLatencyMs()));
        }
        if (resolution.waitingIntentClarification()) {
            routeDecisionRecorder.bindIntentAgentProvider(request.runId());
            return interactionEventFactory.intentClarificationWaitingBody(
                    request.runId(), request.session().id(), resolution.intentClarificationPayload());
        }
        String appliedRouteQuery = resolution.intent() == null
                ? request.routeMemoryQuery()
                : blankToDefault(request.intentRouteMemoryQuery(), request.routeMemoryQuery());
        MemoryContext runtimeMemory = routeDecisionRecorder.recordAppliedRouteDecision(
                new RouteDecisionRecorder.AppliedRouteDecision(
                        request.user(), request.session().id(), request.runId(), appliedRouteQuery,
                        resolution.intent(), resolution.route(), resolution.binding(), request.memory()));
        ChatCommand runtimeCommand = routeResolutionCoordinator.runtimeCommand(
                new RouteResolutionCoordinator.RuntimeCommandRequest(
                        request.runCommand(), request.routeMemoryQuery(), request.documents(),
                        resolution.route(), resolution.intent(), request.runtimeMetadataOverride()));
        return requireCurrentOwnerRunning(request.executionClaim(), "before-runtime")
                .thenMany(Flux.defer(() -> switch (resolution.route().type()) {
                    case DOMAIN_AGENT -> domainAgentRefusalCoordinator.execute(
                            new DomainAgentRefusalRunContext(
                                    runtimeCommand, request.runId(), request.session(), runtimeMemory,
                                    resolution.route(), request.user(), request.routeRef(), request.bindingRef(),
                                    request.executionClaim(), request.forwardHeaders(), request.traceContext(),
                                    resolution.intent(), request.documents(), new HashSet<>(), 0,
                                    request.routeMemoryQuery()));
                    case SYSTEM_RESPONSE -> systemResponseExecutor.execute(
                            ChatRuntimeMapper.command(runtimeCommand), request.runId(),
                            ChatRuntimeMapper.intent(resolution.intent()), ChatRuntimeMapper.route(resolution.route()));
                    case AGENT_RUNTIME -> agentRuntimeExecutor.execute(new RuntimeExecutionContext(
                            ChatRuntimeMapper.command(runtimeCommand), request.runId(),
                            ChatRuntimeMapper.memory(runtimeMemory), ChatRuntimeMapper.intent(resolution.intent()),
                            ChatRuntimeMapper.route(resolution.route()), request.user(), request.bindingRef().get(),
                            resolution.runtimeSessionMode(), request.forwardHeaders(),
                            ChatRuntimeMapper.documents(request.documents()),
                            request.traceContext()));
                }));
    }

    private reactor.core.publisher.Mono<Void> requireCurrentOwnerRunning(
            RunExecutionClaim claim, String stage) {
        return runExecutionGateCoordinator.requireCurrentOwnerRunning(claim, stage);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public record Request(
            UserContext user,
            ChatSession session,
            ChatCommand runCommand,
            List<AttachmentRef> attachments,
            List<UploadedDocument> documents,
            MemoryContext memory,
            String runId,
            String runtimeBindingLeafId,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AtomicReference<RuntimeSessionMode> runtimeSessionModeRef,
            RunExecutionClaim executionClaim,
            ChatRun run,
            String routeMemoryQuery,
            String intentQuery,
            String intentRouteMemoryQuery,
            Map<String, Object> runtimeMetadataOverride
    ) {
    }
}
