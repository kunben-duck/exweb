package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
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

import java.util.concurrent.atomic.AtomicReference;

/** Executes the existing intent-clarification continuation workflow. */
final class IntentClarificationRunCoordinator {
    private final IntentClarificationContextAssembler clarificationAssembler;
    private final InteractionEventFactory interactionEventFactory;
    private final InteractionRunLifecycle lifecycle;
    private final ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator;
    private final ChatEventPersistenceCoordinator eventPersistenceCoordinator;
    private final ChatRunAdmissionCoordinator admissionCoordinator;
    private final RunMemoryContextAssembler memoryAssembler;

    IntentClarificationRunCoordinator(
            IntentClarificationContextAssembler clarificationAssembler,
            InteractionEventFactory interactionEventFactory,
            InteractionRunLifecycle lifecycle,
            ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            ChatRunAdmissionCoordinator admissionCoordinator,
            RunMemoryContextAssembler memoryAssembler) {
        this.clarificationAssembler = clarificationAssembler;
        this.interactionEventFactory = interactionEventFactory;
        this.lifecycle = lifecycle;
        this.runtimeDispatchCoordinator = runtimeDispatchCoordinator;
        this.eventPersistenceCoordinator = eventPersistenceCoordinator;
        this.admissionCoordinator = admissionCoordinator;
        this.memoryAssembler = memoryAssembler;
    }

    IntentClarificationRunCoordinator(
            IntentClarificationContextAssembler clarificationAssembler,
            InteractionEventFactory interactionEventFactory,
            InteractionRunLifecycle lifecycle,
            ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            ChatRunAdmissionCoordinator admissionCoordinator) {
        this(clarificationAssembler, interactionEventFactory, lifecycle, runtimeDispatchCoordinator,
                eventPersistenceCoordinator, admissionCoordinator, null);
    }

    Flux<ChatEvent> execute(Request request) {
        ChatInteractionRequest interaction = request.claim().request();
        ChatCommand command = clarificationAssembler.command(
                request.user(),
                request.session(),
                interaction,
                request.claim().responsePayload(),
                request.input());
        RuntimeEvent responseEvent = interactionEventFactory.clarificationResponseEvent(
                request.runId(),
                request.session().id(),
                interaction,
                request.claim().responsePayload());
        MemoryContext memory = memoryAssembler == null
                ? MemoryContext.empty()
                : memoryAssembler.assemble(
                        command, request.session().currentLeafMessageId(), interaction.userMessageId());
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>();
        AtomicReference<RouteTarget> routeRef = new AtomicReference<>();
        AtomicReference<RuntimeSessionMode> runtimeSessionModeRef =
                new AtomicReference<>(RuntimeSessionMode.RESUME);
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
                "after-intent-interaction-run-create");
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
                "after-intent-interaction-execution-create");
        AssistantAssembly assistant = new AssistantAssembly();
        AtomicReference<java.util.Map<String, Object>> pendingInteractionPayloadRef = new AtomicReference<>();
        RunEventPipelineContext context = new RunEventPipelineContext(
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
                request.input().cumulativeDocumentIds());
        String foldedRouteQuery = clarificationAssembler.routeMemoryQuery(
                messagePlan, interaction, request.input().intentQuery());
        try {
            return eventPersistenceCoordinator.executeAfterRunStarted(
                    context,
                    () -> Flux.concat(
                            Flux.just(responseEvent),
                            runtimeDispatchCoordinator.execute(new RoutePipelineRequest(
                                    request.user(),
                                    request.session(),
                                    command,
                                    request.input().cumulativeAttachments(),
                                    request.input().cumulativeDocuments(),
                                    memory,
                                    request.runId(),
                                    messagePlan.parentMessageId(),
                                    request.forwardHeaders(),
                                    request.traceContext(),
                                    routeRef,
                                    bindingRef,
                                    runtimeSessionModeRef,
                                    executionClaim,
                                    run,
                                    foldedRouteQuery,
                                    request.input().intentQuery(),
                                    foldedRouteQuery,
                                    request.input().runtimeMetadata(),
                                    request.input().agentMode(),
                                    new RuntimeBindingDispatchLifecycle(),
                                    assistant.persistenceState(),
                                    pendingInteractionPayloadRef))));
        } catch (RuntimeException ex) {
            return lifecycle.failContinuation(context, ex);
        }
    }

    record Request(
            UserContext user,
            ChatInteractionClaimResult claim,
            String runId,
            ChatSession session,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            RunStartAttempt startAttempt,
            IntentClarificationContextAssembler.ContinuationInput input
    ) {
    }
}
