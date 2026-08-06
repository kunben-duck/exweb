package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceGate;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeExecutionContext;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.routing.RouteType;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Executes the existing user-confirmed route switch continuation workflow. */
final class RouteSwitchContinuationCoordinator {
    private final RouteSwitchContextResolver contextResolver;
    private final InteractionRunLifecycle lifecycle;
    private final AppliedRouteRecorder appliedRouteRecorder;
    private final InteractionEventFactory interactionEventFactory;
    private final ChatEventPersistenceCoordinator eventPersistenceCoordinator;
    private final DomainAgentRefusalCoordinator refusalCoordinator;
    private final AgentRuntimeExecutor runtimeExecutor;
    private final AgentDataPersistenceGate persistenceGate;
    private final RunMemoryContextAssembler memoryAssembler;

    RouteSwitchContinuationCoordinator(
            RuntimeBindingApplicationService runtimeBindingService,
            InteractionRunLifecycle lifecycle,
            AppliedRouteRecorder appliedRouteRecorder,
            InteractionEventFactory interactionEventFactory,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            DomainAgentRefusalCoordinator refusalCoordinator,
            AgentRuntimeExecutor runtimeExecutor) {
        this(runtimeBindingService, lifecycle, appliedRouteRecorder, interactionEventFactory,
                eventPersistenceCoordinator, refusalCoordinator, runtimeExecutor, null);
    }

    RouteSwitchContinuationCoordinator(
            RuntimeBindingApplicationService runtimeBindingService,
            InteractionRunLifecycle lifecycle,
            AppliedRouteRecorder appliedRouteRecorder,
            InteractionEventFactory interactionEventFactory,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            DomainAgentRefusalCoordinator refusalCoordinator,
            AgentRuntimeExecutor runtimeExecutor,
            AgentDataPersistenceGate persistenceGate) {
        this(runtimeBindingService, lifecycle, appliedRouteRecorder, interactionEventFactory,
                eventPersistenceCoordinator, refusalCoordinator, runtimeExecutor, persistenceGate, null);
    }

    RouteSwitchContinuationCoordinator(
            RuntimeBindingApplicationService runtimeBindingService,
            InteractionRunLifecycle lifecycle,
            AppliedRouteRecorder appliedRouteRecorder,
            InteractionEventFactory interactionEventFactory,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            DomainAgentRefusalCoordinator refusalCoordinator,
            AgentRuntimeExecutor runtimeExecutor,
            AgentDataPersistenceGate persistenceGate,
            RunMemoryContextAssembler memoryAssembler) {
        this.contextResolver = new RouteSwitchContextResolver(runtimeBindingService);
        this.lifecycle = lifecycle;
        this.appliedRouteRecorder = appliedRouteRecorder;
        this.interactionEventFactory = interactionEventFactory;
        this.eventPersistenceCoordinator = eventPersistenceCoordinator;
        this.refusalCoordinator = refusalCoordinator;
        this.runtimeExecutor = runtimeExecutor;
        this.persistenceGate = persistenceGate;
        this.memoryAssembler = memoryAssembler;
    }

    Flux<ChatEvent> execute(Request request) {
        ChatInteractionRequest interaction = request.claim().request();
        RouteSwitchInput input = contextResolver.input(
                interaction, request.claim());
        RuntimeEvent responseEvent = interactionEventFactory.routeSwitchResponseEvent(
                request.runId(),
                request.session().id(),
                interaction,
                request.claim().responsePayload());
        RouteTarget route = contextResolver.target(interaction, input);
        ChatMessage userMessage = new ChatMessage(
                interaction.userMessageId(),
                request.user().tenantId(),
                request.user().ownerUserId(),
                request.session().id(),
                "user",
                input.originalQuery(),
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
                request.startAttempt(), run, "after-domain-switch-run-create");
        RunExecutionClaim executionClaim;
        try {
            executionClaim = lifecycle.startExecution(run, interaction);
        } catch (RuntimeException ex) {
            return lifecycle.failInitialization(run, interaction, ex);
        }
        lifecycle.trackExecution(
                request.startAttempt(),
                executionClaim,
                "after-domain-switch-execution-create");
        AtomicReference<RouteTarget> routeRef = new AtomicReference<>(route);
        AtomicReference<Map<String, Object>> pendingInteractionPayloadRef = new AtomicReference<>();
        AssistantAssembly assistant = new AssistantAssembly();
        RunEventPipelineContext pipelineContext = new RunEventPipelineContext(
                request.user(),
                request.session(),
                messagePlan,
                routeRef,
                bindingRef,
                assistant,
                request.runId(),
                executionClaim,
                pendingInteractionPayloadRef,
                interaction,
                request.startAttempt(),
                List.of());
        StartedSwitchContext context = new StartedSwitchContext(
                request,
                interaction,
                input,
                route,
                responseEvent,
                executionClaim,
                routeRef,
                bindingRef,
                assistant,
                pendingInteractionPayloadRef);
        try {
            return eventPersistenceCoordinator.executeAfterRunStarted(
                    pipelineContext, () -> executeAfterStart(context));
        } catch (RuntimeException ex) {
            return lifecycle.failContinuation(pipelineContext, ex);
        }
    }

    private Flux<ChatEvent> executeAfterStart(StartedSwitchContext context) {
        Mono<?> policyResolution = !context.input().approved() || persistenceGate == null
                ? Mono.just(context.assistant().persistenceState())
                : persistenceGate.resolve(
                        context.request().user(),
                        context.route(),
                        context.assistant().persistenceState(),
                        context.request().forwardHeaders());
        return policyResolution.thenMany(Flux.defer(() -> executeAfterPolicy(context)));
    }

    private Flux<ChatEvent> executeAfterPolicy(StartedSwitchContext context) {
        Request request = context.request();
        RouteSwitchBindingSelection selection = contextResolver.selectBinding(
                context.interaction(),
                context.input(),
                new RouteSwitchBindingRequest(
                        request.user(),
                        request.session(),
                        request.runId(),
                        request.agentMode()));
        RuntimeBinding binding = selection.binding();
        context.bindingRef().set(binding);
        if (context.input().approved()) {
            appliedRouteRecorder.bindResolvedRouteRequired(
                    context.request().runId(), context.route(), binding, context.executionClaim(),
                    context.assistant().persistenceState());
        } else {
            // 拒绝切换不会启动下游，保留原有 best-effort 诊断更新语义。
            appliedRouteRecorder.bindResolvedRoute(
                    context.request().runId(), context.route(), binding);
        }
        Flux<ChatEvent> body = context.input().approved()
                ? approvedBody(context, selection)
                : declinedBody(context.request(), context.interaction());
        return Flux.concat(Flux.just(context.responseEvent()), body);
    }

    private Flux<ChatEvent> approvedBody(
            StartedSwitchContext context,
            RouteSwitchBindingSelection selection) {
        Request request = context.request();
        ChatInteractionRequest interaction = context.interaction();
        RouteSwitchInput input = context.input();
        RouteTarget route = context.route();
        RuntimeBinding binding = selection.binding();
        IntentDecision switchIntent = appliedRouteRecorder.routeSwitchIntent(interaction, route);
        ChatCommand command = runtimeCommand(request, interaction, input, route);
        MemoryContext sourceMemory = memoryAssembler == null
                ? MemoryContext.empty()
                : memoryAssembler.assemble(
                        command, request.session().currentLeafMessageId(), interaction.userMessageId());
        MemoryContext runtimeMemory = appliedRouteRecorder.recordAppliedRouteDecision(
                new AppliedRouteRecorder.AppliedRouteDecision(
                        request.user(),
                        request.session().id(),
                        request.runId(),
                        input.candidateRouteQuery(),
                        switchIntent,
                        route,
                        binding,
                        sourceMemory));
        ApprovedSwitchContext approved = new ApprovedSwitchContext(
                context, selection, command, runtimeMemory, switchIntent);
        Flux<ChatEvent> body = route.type() == RouteType.DOMAIN_AGENT
                ? executeDomainAgent(approved)
                : executeRelay(approved);
        return Flux.concat(
                Flux.just(interactionEventFactory.routeSwitchAppliedEvent(
                        request.runId(),
                        request.session().id(),
                        interaction,
                        route,
                        binding)),
                body);
    }

    private ChatCommand runtimeCommand(Request request,
                                       ChatInteractionRequest interaction,
                                       RouteSwitchInput input,
                                       RouteTarget route) {
        return new ChatCommand(
                null,
                request.user().tenantId(),
                request.user().ownerUserId(),
                request.session().id(),
                null,
                null,
                input.originalQuery(),
                List.of(),
                Map.of(),
                route.type() == RouteType.DOMAIN_AGENT ? "DOMAIN_AGENT" : null,
                route.type() == RouteType.DOMAIN_AGENT ? input.candidateTargetId() : null,
                ChatRunMode.NEXT,
                interaction.assistantMessageId(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                request.agentMode());
    }

    private Flux<ChatEvent> executeDomainAgent(ApprovedSwitchContext approved) {
        StartedSwitchContext context = approved.startedSwitch();
        Request request = context.request();
        DomainAgentRunContext domainContext = new DomainAgentRunContext(
                approved.command(),
                request.runId(),
                context.interaction().userMessageId(),
                request.session(),
                approved.runtimeMemory(),
                context.route(),
                request.user(),
                context.routeRef(),
                context.bindingRef(),
                context.executionClaim(),
                request.forwardHeaders(),
                request.traceContext(),
                approved.switchIntent(),
                List.of(),
                new HashSet<>(),
                0,
                context.input().candidateRouteQuery(),
                context.assistant().persistenceState(),
                context.pendingInteractionPayloadRef());
        return eventPersistenceCoordinator.requireCurrentOwnerRunning(
                        context.executionClaim(), "before-route-switch-domain-agent")
                .then(Mono.fromRunnable(() -> appliedRouteRecorder.markRuntimeDispatchStartedRequired(
                        request.runId(),
                        context.route(),
                        context.bindingRef().get(),
                        context.executionClaim(),
                        context.assistant().persistenceState())))
                .thenMany(Flux.defer(() -> refusalCoordinator.execute(domainContext)));
    }

    private Flux<ChatEvent> executeRelay(ApprovedSwitchContext approved) {
        StartedSwitchContext context = approved.startedSwitch();
        Request request = context.request();
        return eventPersistenceCoordinator.requireCurrentOwnerRunning(
                        context.executionClaim(), "before-route-switch-relay")
                .then(Mono.fromRunnable(() -> appliedRouteRecorder.markRuntimeDispatchStartedRequired(
                        request.runId(),
                        context.route(),
                        context.bindingRef().get(),
                        context.executionClaim(),
                        context.assistant().persistenceState())))
                .thenMany(Flux.defer(() -> runtimeExecutor.execute(
                        new RuntimeExecutionContext(
                                approved.command(),
                                request.runId(),
                                approved.runtimeMemory(),
                                approved.switchIntent(),
                                context.route(),
                                request.user(),
                                approved.selection().binding(),
                                approved.selection().sessionMode(),
                                request.forwardHeaders(),
                                List.of(),
                                request.traceContext()))));
    }

    private Flux<ChatEvent> declinedBody(
            Request request,
            ChatInteractionRequest interaction) {
        appliedRouteRecorder.completeWithoutRoute(
                request.user(), request.session().id());
        return Flux.just(interactionEventFactory.routeSwitchDeclinedEvent(
                request.runId(), request.session().id(), interaction));
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

    private record StartedSwitchContext(
            Request request,
            ChatInteractionRequest interaction,
            RouteSwitchInput input,
            RouteTarget route,
            RuntimeEvent responseEvent,
            RunExecutionClaim executionClaim,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AssistantAssembly assistant,
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef
    ) {
    }

    private record ApprovedSwitchContext(
            StartedSwitchContext startedSwitch,
            RouteSwitchBindingSelection selection,
            ChatCommand command,
            MemoryContext runtimeMemory,
            IntentDecision switchIntent
    ) {
    }
}
