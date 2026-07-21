package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.runtime.application.model.RuntimeProviders;

import com.huawei.it.ex.one.chat.application.mapper.ChatRuntimeMapper;

import com.huawei.it.ex.one.chat.application.model.AssistantAssembly;
import com.huawei.it.ex.one.chat.application.model.DomainAgentRefusalRunContext;
import com.huawei.it.ex.one.chat.application.model.RunEventPipelineContext;
import com.huawei.it.ex.one.chat.application.model.RunStartAttempt;
import com.huawei.it.ex.one.chat.application.service.ChatInteractionClaimResult;
import com.huawei.it.ex.one.chat.application.service.CreateChatRunContext;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
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
import com.huawei.it.ex.one.intent.application.model.MemoryContext;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.intent.application.model.RouteType;
import com.huawei.it.ex.one.intent.application.model.IntentDecision;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentBindingCommand;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBindingResolution;
import com.huawei.it.ex.one.runtime.application.model.RuntimeExecutionContext;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.runtime.application.model.RuntimeSessionMode;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingService;
import com.huawei.it.ex.one.runtime.application.service.RuntimeExecutionService;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Executes the existing user-confirmed route switch continuation workflow. */
@Component
public class RouteSwitchContinuationCoordinator {
    private final RuntimeBindingService runtimeBindingService;
    private final InteractionRunLifecycle lifecycle;
    private final RouteDecisionRecorder routeDecisionRecorder;
    private final InteractionEventFactory interactionEventFactory;
    private final ChatEventPersistenceCoordinator eventPersistenceCoordinator;
    private final ChatRunExecutionGateCoordinator executionGateCoordinator;
    private final DomainAgentRefusalCoordinator refusalCoordinator;
    private final RuntimeExecutionService runtimeExecutionService;

    public RouteSwitchContinuationCoordinator(
            RuntimeBindingService runtimeBindingService,
            InteractionRunLifecycle lifecycle,
            RouteDecisionRecorder routeDecisionRecorder,
            InteractionEventFactory interactionEventFactory,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            ChatRunExecutionGateCoordinator executionGateCoordinator,
            DomainAgentRefusalCoordinator refusalCoordinator,
            RuntimeExecutionService runtimeExecutionService) {
        this.runtimeBindingService = runtimeBindingService;
        this.lifecycle = lifecycle;
        this.routeDecisionRecorder = routeDecisionRecorder;
        this.interactionEventFactory = interactionEventFactory;
        this.eventPersistenceCoordinator = eventPersistenceCoordinator;
        this.executionGateCoordinator = executionGateCoordinator;
        this.refusalCoordinator = refusalCoordinator;
        this.runtimeExecutionService = runtimeExecutionService;
    }

    public Flux<ChatEvent> execute(Request request) {
        ChatInteractionRequest interaction = request.claim().request();
        SwitchInput input = switchInput(interaction, request.claim());
        RuntimeEvent responseEvent = interactionEventFactory.routeSwitchResponseEvent(
                request.runId(), request.session().id(), interaction, request.claim().responsePayload());
        RouteTarget route = input.approved()
                ? routeSwitchTarget(input.candidateProvider(), input.candidateTargetId(), "user-confirmed")
                : RouteTarget.domainAgent(input.currentTargetId(), routeSource(interaction), 1.0,
                        "declined route switch");
        ChatMessage userMessage = new ChatMessage(
                interaction.userMessageId(), request.user().tenantId(), request.user().ownerUserId(),
                request.session().id(), "user", input.originalQuery(), null, Instant.now());
        ChatRunMessagePlan messagePlan = new ChatRunMessagePlan(
                ChatRunMode.NEXT, interaction.userMessageId(), userMessage, null);
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>();
        ChatRun run = lifecycle.create(new CreateChatRunContext(
                request.runId(), request.user(), request.session().id(), route, null,
                lifecycle.metadata(interaction), ChatRunMode.NEXT,
                interaction.userMessageId(), interaction.userMessageId()), interaction);
        lifecycle.trackRun(request.startAttempt(), run, "after-domain-switch-run-create");
        RunExecutionClaim executionClaim;
        try {
            executionClaim = lifecycle.startExecution(run, interaction);
        } catch (RuntimeException ex) {
            return lifecycle.failInitialization(run, interaction, ex);
        }
        lifecycle.trackExecution(request.startAttempt(), executionClaim,
                "after-domain-switch-execution-create");
        AtomicReference<RouteTarget> routeRef = new AtomicReference<>(route);
        RunEventPipelineContext context = new RunEventPipelineContext(
                request.user(), request.session(), messagePlan, routeRef, bindingRef,
                new AssistantAssembly(), request.runId(), executionClaim, new AtomicReference<>(),
                interaction, request.startAttempt(), List.of());
        StartedSwitchContext startedSwitch = new StartedSwitchContext(
                request, interaction, input, route, responseEvent, executionClaim, routeRef, bindingRef);
        try {
            return eventPersistenceCoordinator.executeAfterRunStarted(
                    context, () -> executeAfterStart(startedSwitch));
        } catch (RuntimeException ex) {
            return lifecycle.failContinuation(context, ex);
        }
    }

    private Flux<ChatEvent> executeAfterStart(StartedSwitchContext context) {
        BindingSelection selection = selectBinding(
                context.request(), context.interaction(), context.input());
        RuntimeBinding binding = selection.binding();
        context.bindingRef().set(binding);
        routeDecisionRecorder.bindResolvedRoute(context.request().runId(), context.route(), binding);
        Flux<ChatEvent> body = context.input().approved()
                ? approvedBody(context, selection)
                : declinedBody(context.request(), context.interaction());
        return Flux.concat(Flux.just(context.responseEvent()), body);
    }

    private BindingSelection selectBinding(Request request,
                                            ChatInteractionRequest interaction,
                                            SwitchInput input) {
        RuntimeSessionMode runtimeSessionMode = RuntimeSessionMode.RESUME;
        RuntimeBinding binding;
        if (!input.approved()) {
            binding = runtimeBindingService.resumeForInteraction(
                    ChatRuntimeMapper.interaction(interaction), request.runId());
        } else if (RuntimeProviders.DOMAIN_AGENT.equals(input.candidateProvider())) {
            cancelCurrentBinding(interaction, request.runId());
            binding = runtimeBindingService.bindDomainAgentForRun(new DomainAgentBindingCommand(
                    request.user().tenantId(), request.user().ownerUserId(), request.session().id(), request.runId(),
                    interaction.assistantMessageId(), input.candidateTargetId(),
                    "user-confirmed", routeSwitchBindingMetadata(interaction)));
        } else if (RuntimeProviders.RELAY.equals(input.candidateProvider())) {
            cancelCurrentBinding(interaction, request.runId());
            RuntimeBindingResolution resolution = runtimeBindingService.resolveForRun(
                    request.user().tenantId(), request.user().ownerUserId(), request.session().id(), request.runId(),
                    interaction.assistantMessageId());
            binding = resolution.binding();
            runtimeSessionMode = resolution.sessionMode();
        } else {
            throw new IllegalArgumentException("不支持的候选 Runtime provider: " + input.candidateProvider());
        }
        return new BindingSelection(binding, runtimeSessionMode);
    }

    private void cancelCurrentBinding(ChatInteractionRequest interaction, String runId) {
        runtimeBindingService.markNotRoutable(
                runtimeBindingService.resumeForInteraction(ChatRuntimeMapper.interaction(interaction), runId),
                firstText(interaction.requestPayload().get("refusalCode")));
    }

    private Flux<ChatEvent> approvedBody(StartedSwitchContext context,
                                         BindingSelection selection) {
        Request request = context.request();
        ChatInteractionRequest interaction = context.interaction();
        SwitchInput input = context.input();
        RouteTarget route = context.route();
        RuntimeBinding binding = selection.binding();
        IntentDecision switchIntent = routeDecisionRecorder.routeSwitchIntent(interaction, route);
        MemoryContext runtimeMemory = routeDecisionRecorder.recordAppliedRouteDecision(
                new RouteDecisionRecorder.AppliedRouteDecision(
                        request.user(), request.session().id(), request.runId(), input.candidateRouteQuery(),
                        switchIntent, route, binding, MemoryContext.empty()));
        ChatCommand command = new ChatCommand(
                null, request.user().tenantId(), request.user().ownerUserId(), request.session().id(), null,
                null, input.originalQuery(), List.of(), Map.of(),
                route.type() == RouteType.DOMAIN_AGENT ? "DOMAIN_AGENT" : null,
                route.type() == RouteType.DOMAIN_AGENT ? input.candidateTargetId() : null,
                ChatRunMode.NEXT, interaction.assistantMessageId(), null, null);
        ApprovedSwitchContext approved = new ApprovedSwitchContext(
                context, selection, command, runtimeMemory, switchIntent);
        Flux<ChatEvent> body = route.type() == RouteType.DOMAIN_AGENT
                ? executeDomainAgent(approved)
                : executeRelay(approved);
        return Flux.concat(Flux.just(interactionEventFactory.routeSwitchAppliedEvent(
                request.runId(), request.session().id(), interaction, route, binding)), body);
    }

    private Flux<ChatEvent> executeDomainAgent(ApprovedSwitchContext approved) {
        StartedSwitchContext context = approved.startedSwitch();
        Request request = context.request();
        DomainAgentRefusalRunContext domainContext =
                new DomainAgentRefusalRunContext(
                        approved.command(), request.runId(), request.session(), approved.runtimeMemory(),
                        context.route(), request.user(), context.routeRef(), context.bindingRef(),
                        context.executionClaim(), request.forwardHeaders(), request.traceContext(),
                        approved.switchIntent(), List.of(), new HashSet<>(), 0,
                        context.input().candidateRouteQuery());
        return executionGateCoordinator.requireCurrentOwnerRunning(
                        context.executionClaim(), "before-route-switch-domain-agent")
                .thenMany(Flux.defer(() -> refusalCoordinator.execute(domainContext)));
    }

    private Flux<ChatEvent> executeRelay(ApprovedSwitchContext approved) {
        StartedSwitchContext context = approved.startedSwitch();
        Request request = context.request();
        return executionGateCoordinator.requireCurrentOwnerRunning(
                        context.executionClaim(), "before-route-switch-relay")
                .thenMany(Flux.defer(() -> runtimeExecutionService.execute(new RuntimeExecutionContext(
                        ChatRuntimeMapper.command(approved.command()), request.runId(),
                        ChatRuntimeMapper.memory(approved.runtimeMemory()),
                        ChatRuntimeMapper.intent(approved.switchIntent()),
                        ChatRuntimeMapper.route(context.route()), request.user(), approved.selection().binding(),
                        approved.selection().sessionMode(), request.forwardHeaders(), List.of(),
                        request.traceContext()))));
    }

    private Flux<ChatEvent> declinedBody(Request request, ChatInteractionRequest interaction) {
        routeDecisionRecorder.foldClarificationsWithoutDecision(request.user(), request.session().id());
        return Flux.just(interactionEventFactory.routeSwitchDeclinedEvent(
                request.runId(), request.session().id(), interaction));
    }

    private SwitchInput switchInput(ChatInteractionRequest interaction,
                                    ChatInteractionClaimResult claim) {
        boolean approved = Boolean.TRUE.equals(claim.responsePayload().get("approved"));
        String candidateProvider = blankToDefault(
                firstText(interaction.requestPayload().get("candidateProvider")),
                RuntimeProviders.DOMAIN_AGENT);
        String candidateTargetId = firstText(interaction.requestPayload().get("candidateTargetId"));
        String currentProvider = blankToDefault(
                firstText(interaction.requestPayload().get("currentProvider")),
                RuntimeProviders.DOMAIN_AGENT);
        String currentTargetId = firstText(interaction.requestPayload().get("currentTargetId"));
        if (!RuntimeProviders.DOMAIN_AGENT.equals(currentProvider)
                || currentTargetId == null || currentTargetId.isBlank()) {
            throw new IllegalStateException("路由切换 Interaction 缺少当前 DomainAgent 上下文");
        }
        String originalQuery = firstText(interaction.requestPayload().get("originalQuery"));
        String normalizedOriginalQuery = originalQuery == null ? "" : originalQuery;
        String candidateRouteQuery = blankToDefault(
                firstText(interaction.requestPayload().get("routeMemoryQuery")), normalizedOriginalQuery);
        return new SwitchInput(approved, candidateProvider, candidateTargetId,
                currentTargetId, normalizedOriginalQuery, candidateRouteQuery);
    }

    private RouteTarget routeSwitchTarget(String provider, String targetId, String routeSource) {
        if (RuntimeProviders.DOMAIN_AGENT.equals(provider)) {
            if (targetId == null || targetId.isBlank()) {
                throw new IllegalArgumentException("切换到 DomainAgent 时 candidateTargetId 不能为空");
            }
            return RouteTarget.domainAgent(targetId, routeSource, 1.0, "confirmed route switch");
        }
        if (RuntimeProviders.RELAY.equals(provider)) {
            return RouteTarget.agentRuntime(routeSource, 1.0, "confirmed route switch to relay");
        }
        throw new IllegalArgumentException("不支持的候选 Runtime provider: " + provider);
    }

    private String routeSource(ChatInteractionRequest interaction) {
        return blankToDefault(firstText(interaction.requestPayload().get("currentRouteSource")), "front-selected");
    }

    private Map<String, Object> routeSwitchBindingMetadata(ChatInteractionRequest interaction) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfNotNull(metadata, "domainAgentId", interaction.requestPayload().get("candidateTargetId"));
        metadata.put("routeSource", "user-confirmed");
        putIfNotNull(metadata, "intentCode", interaction.requestPayload().get("candidateIntentCode"));
        putIfNotNull(metadata, "intentName", interaction.requestPayload().get("candidateIntentName"));
        putIfNotNull(metadata, "confirmedFromDomainAgentId", interaction.requestPayload().get("currentTargetId"));
        metadata.put("confirmedInteractionId", interaction.id());
        return Map.copyOf(metadata);
    }

    private void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private String firstText(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
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

    private record SwitchInput(
            boolean approved,
            String candidateProvider,
            String candidateTargetId,
            String currentTargetId,
            String originalQuery,
            String candidateRouteQuery
    ) {
    }

    private record BindingSelection(RuntimeBinding binding, RuntimeSessionMode sessionMode) {
    }

    private record StartedSwitchContext(
            Request request,
            ChatInteractionRequest interaction,
            SwitchInput input,
            RouteTarget route,
            RuntimeEvent responseEvent,
            RunExecutionClaim executionClaim,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef
    ) {
    }

    private record ApprovedSwitchContext(
            StartedSwitchContext startedSwitch,
            BindingSelection selection,
            ChatCommand command,
            MemoryContext runtimeMemory,
            IntentDecision switchIntent
    ) {
    }
}
