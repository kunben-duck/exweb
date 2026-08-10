package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 执行 AMBIGUOUS_ROUTE 的候选技能选择或自动选择续接。
 */
final class AmbiguousRouteContinuationCoordinator {
    private final AmbiguousRouteSelectionResolver selectionResolver;
    private final IntentClarificationContextAssembler clarificationAssembler;
    private final InteractionEventFactory interactionEventFactory;
    private final InteractionRunLifecycle lifecycle;
    private final ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator;
    private final ChatEventPersistenceCoordinator eventPersistenceCoordinator;
    private final ChatRunAdmissionCoordinator admissionCoordinator;
    private final RunMemoryContextAssembler memoryAssembler;
    private final AssistantAssemblyFactory assistantAssemblyFactory;

    AmbiguousRouteContinuationCoordinator(
            AmbiguousRouteSelectionResolver selectionResolver,
            IntentClarificationContextAssembler clarificationAssembler,
            InteractionEventFactory interactionEventFactory,
            InteractionRunLifecycle lifecycle,
            ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            ChatRunAdmissionCoordinator admissionCoordinator,
            RunMemoryContextAssembler memoryAssembler,
            AssistantAssemblyFactory assistantAssemblyFactory) {
        this.selectionResolver = selectionResolver;
        this.clarificationAssembler = clarificationAssembler;
        this.interactionEventFactory = interactionEventFactory;
        this.lifecycle = lifecycle;
        this.runtimeDispatchCoordinator = runtimeDispatchCoordinator;
        this.eventPersistenceCoordinator = eventPersistenceCoordinator;
        this.admissionCoordinator = admissionCoordinator;
        this.memoryAssembler = memoryAssembler;
        this.assistantAssemblyFactory = assistantAssemblyFactory;
    }

    AmbiguousRouteContinuationCoordinator(
            AmbiguousRouteSelectionResolver selectionResolver,
            IntentClarificationContextAssembler clarificationAssembler,
            InteractionEventFactory interactionEventFactory,
            InteractionRunLifecycle lifecycle,
            ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            ChatRunAdmissionCoordinator admissionCoordinator,
            RunMemoryContextAssembler memoryAssembler) {
        this(selectionResolver, clarificationAssembler, interactionEventFactory, lifecycle,
                runtimeDispatchCoordinator, eventPersistenceCoordinator, admissionCoordinator,
                memoryAssembler, null);
    }

    AmbiguousRouteContinuationCoordinator(
            AmbiguousRouteSelectionResolver selectionResolver,
            IntentClarificationContextAssembler clarificationAssembler,
            InteractionEventFactory interactionEventFactory,
            InteractionRunLifecycle lifecycle,
            ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            ChatRunAdmissionCoordinator admissionCoordinator) {
        this(selectionResolver, clarificationAssembler, interactionEventFactory, lifecycle,
                runtimeDispatchCoordinator, eventPersistenceCoordinator, admissionCoordinator, null, null);
    }

    Flux<ChatEvent> execute(Request request) {
        ChatInteractionRequest interaction = request.claim().request();
        AmbiguousRouteContinuationPlan plan = request.plan();
        if (plan == null || !plan.selectedCandidate()) {
            throw new IllegalArgumentException("AMBIGUOUS_ROUTE 候选执行计划不能为空");
        }
        String routeQuery = clarificationAssembler.routeMemoryQueryForSelection(interaction);
        ChatCommand command = clarificationAssembler.selectionCommand(
                request.user(), request.session(), interaction, request.input(), routeQuery);
        MemoryContext memory = memoryAssembler == null
                ? MemoryContext.empty()
                : memoryAssembler.assemble(
                        command, request.session().currentLeafMessageId(), interaction.userMessageId());
        ChatRunAdmissionCommitService.AdmissionResult admission =
                admissionCoordinator.admitIntentClarification(
                        new ChatRunAdmissionCoordinator.IntentClarificationAdmission(
                                request.user(),
                                request.session(),
                                request.runId(),
                                interaction,
                                request.input().messageText(),
                                request.input().currentAttachments(),
                                lifecycle.metadata(interaction)));
        ChatRunMessagePlan messagePlan = admission.messagePlan();
        ChatRun run = admission.run();
        lifecycle.trackRun(
                request.startAttempt(),
                run,
                "after-ambiguous-route-run-create");
        lifecycle.synchronizeCommittedRunCache(run);
        RunExecutionClaim executionClaim;
        try {
            executionClaim = lifecycle.startExecution(run, interaction);
        } catch (RuntimeException ex) {
            return lifecycle.failInitialization(run, interaction, ex);
        }
        lifecycle.trackExecution(
                request.startAttempt(),
                executionClaim,
                "after-ambiguous-route-execution-create");
        return executeStarted(
                request,
                new StartedExecution(
                        interaction, plan, messagePlan, run, executionClaim, command, routeQuery, memory));
    }

    private Flux<ChatEvent> executeStarted(Request request, StartedExecution started) {
        AtomicReference<RouteTarget> routeRef = new AtomicReference<>();
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>();
        AtomicReference<RuntimeSessionMode> runtimeSessionModeRef =
                new AtomicReference<>(RuntimeSessionMode.RESUME);
        RuntimeReferences runtimeReferences = new RuntimeReferences(
                routeRef,
                bindingRef,
                runtimeSessionModeRef,
                assistantAssemblyFactory == null
                        ? new AssistantAssembly()
                        : assistantAssemblyFactory.create(request.runId()),
                new AtomicReference<>());
        RunEventPipelineContext context = new RunEventPipelineContext(
                request.user(),
                request.session(),
                started.messagePlan(),
                routeRef,
                bindingRef,
                runtimeReferences.assistant(),
                request.runId(),
                started.executionClaim(),
                runtimeReferences.pendingInteractionPayloadRef(),
                started.interaction(),
                request.startAttempt(),
                request.input().cumulativeDocumentIds());
        RouteSignalResult routeSignal = selectionResolver.routeSignal(
                started.plan().candidate(),
                started.plan().routeSource());
        RuntimeEvent responseEvent = interactionEventFactory.clarificationResponseEvent(
                request.runId(),
                request.session().id(),
                started.interaction(),
                request.claim().responsePayload());
        try {
            return eventPersistenceCoordinator.executeAfterRunStarted(
                    context,
                    () -> Flux.concat(
                            Flux.just(responseEvent),
                            runtimeDispatchCoordinator.executeResolved(
                                    routeRequest(
                                            request,
                                            started,
                                            runtimeReferences,
                                            started.command(),
                                            started.routeQuery()),
                                    routeSignal)));
        } catch (RuntimeException ex) {
            return lifecycle.failContinuation(context, ex);
        }
    }

    private RoutePipelineRequest routeRequest(
            Request request,
            StartedExecution started,
            RuntimeReferences runtimeReferences,
            ChatCommand command,
            String routeQuery) {
        return new RoutePipelineRequest(
                request.user(),
                request.session(),
                command,
                request.input().cumulativeAttachments(),
                request.input().cumulativeDocuments(),
                started.memory(),
                request.runId(),
                started.messagePlan().parentMessageId(),
                request.forwardHeaders(),
                request.traceContext(),
                runtimeReferences.routeRef(),
                runtimeReferences.bindingRef(),
                runtimeReferences.runtimeSessionModeRef(),
                started.executionClaim(),
                started.run(),
                routeQuery,
                "",
                routeQuery,
                request.input().runtimeMetadata(),
                request.input().agentMode(),
                new RuntimeBindingDispatchLifecycle(),
                runtimeReferences.assistant().persistenceState(),
                runtimeReferences.pendingInteractionPayloadRef());
    }

    record Request(
            UserContext user,
            ChatInteractionClaimResult claim,
            String runId,
            ChatSession session,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            RunStartAttempt startAttempt,
            IntentClarificationContextAssembler.ContinuationInput input,
            AmbiguousRouteContinuationPlan plan
    ) {
    }

    private record StartedExecution(
            ChatInteractionRequest interaction,
            AmbiguousRouteContinuationPlan plan,
            ChatRunMessagePlan messagePlan,
            ChatRun run,
            RunExecutionClaim executionClaim,
            ChatCommand command,
            String routeQuery,
            MemoryContext memory
    ) {
    }

    private record RuntimeReferences(
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AtomicReference<RuntimeSessionMode> runtimeSessionModeRef,
            AssistantAssembly assistant,
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef
    ) {
    }
}
