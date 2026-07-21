package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.model.AssistantAssembly;
import com.huawei.it.ex.one.chat.application.model.IntentClarificationContinuationInput;
import com.huawei.it.ex.one.chat.application.model.RunEventPipelineContext;
import com.huawei.it.ex.one.chat.application.model.RunStartAttempt;
import com.huawei.it.ex.one.chat.application.service.ChatInteractionClaimResult;
import com.huawei.it.ex.one.chat.application.service.ChatRunAdmissionCommitService;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatRunMessagePlan;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import com.huawei.it.ex.one.common.event.RuntimeEvent;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.runtime.application.model.RuntimeSessionMode;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Executes the existing intent-clarification continuation workflow. */
@Component
public class IntentClarificationRunCoordinator {
    private final IntentClarificationContextAssembler clarificationAssembler;
    private final InteractionEventFactory interactionEventFactory;
    private final InteractionRunLifecycle lifecycle;
    private final IntentFlowCoordinator intentFlowCoordinator;
    private final ChatEventPersistenceCoordinator eventPersistenceCoordinator;
    private final ChatRunAdmissionCommitService runAdmissionCommitService;

    public IntentClarificationRunCoordinator(
            IntentClarificationContextAssembler clarificationAssembler,
            InteractionEventFactory interactionEventFactory,
            InteractionRunLifecycle lifecycle,
            IntentFlowCoordinator intentFlowCoordinator,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            ChatRunAdmissionCommitService runAdmissionCommitService) {
        this.clarificationAssembler = clarificationAssembler;
        this.interactionEventFactory = interactionEventFactory;
        this.lifecycle = lifecycle;
        this.intentFlowCoordinator = intentFlowCoordinator;
        this.eventPersistenceCoordinator = eventPersistenceCoordinator;
        this.runAdmissionCommitService = runAdmissionCommitService;
    }

    public Flux<ChatEvent> execute(Request request) {
        ChatInteractionRequest interaction = request.claim().request();
        ChatCommand command = clarificationAssembler.command(
                request.user(), request.session(), interaction,
                request.claim().responsePayload(), request.input());
        RuntimeEvent responseEvent = interactionEventFactory.clarificationResponseEvent(
                request.runId(), request.session().id(), interaction, request.claim().responsePayload());
        MemoryContext memory = MemoryContext.empty();
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>();
        AtomicReference<RouteTarget> routeRef = new AtomicReference<>();
        AtomicReference<RuntimeSessionMode> runtimeSessionModeRef =
                new AtomicReference<>(RuntimeSessionMode.RESUME);
        ChatRunAdmissionCommitService.AdmissionResult admission =
                runAdmissionCommitService.commitIntentClarification(
                new ChatRunAdmissionCommitService.IntentClarificationAdmissionCommand(
                        request.user(), request.session(), request.runId(), interaction,
                        request.input().messageText(), request.input().currentAttachments(),
                        lifecycle.metadata(interaction)));
        ChatRunMessagePlan messagePlan = admission.messagePlan();
        ChatRun run = admission.run();
        lifecycle.trackRun(request.startAttempt(), run, "after-intent-interaction-run-create");
        lifecycle.synchronizeCommittedRunCache(run);
        RunExecutionClaim executionClaim;
        try {
            executionClaim = lifecycle.startExecution(run, interaction);
        } catch (RuntimeException ex) {
            return lifecycle.failInitialization(run, interaction, ex);
        }
        lifecycle.trackExecution(request.startAttempt(), executionClaim,
                "after-intent-interaction-execution-create");
        RunEventPipelineContext context = new RunEventPipelineContext(
                request.user(), request.session(), messagePlan, routeRef, bindingRef,
                new AssistantAssembly(), request.runId(), executionClaim, new AtomicReference<>(),
                interaction, request.startAttempt(), request.input().cumulativeDocumentIds());
        String foldedRouteQuery = clarificationAssembler.routeMemoryQuery(
                messagePlan, interaction, request.input().intentQuery());
        try {
            return eventPersistenceCoordinator.executeAfterRunStarted(context, () -> Flux.concat(
                    Flux.just(responseEvent),
                    intentFlowCoordinator.execute(new IntentFlowCoordinator.Request(
                            request.user(), request.session(), command,
                            request.input().cumulativeAttachments(), request.input().cumulativeDocuments(),
                            memory, request.runId(), messagePlan.parentMessageId(), request.forwardHeaders(),
                            request.traceContext(), routeRef, bindingRef, runtimeSessionModeRef, executionClaim, run,
                            foldedRouteQuery, request.input().intentQuery(), foldedRouteQuery,
                            request.input().runtimeMetadata()))));
        } catch (RuntimeException ex) {
            return lifecycle.failContinuation(context, ex);
        }
    }

    public record Request(
            UserContext user,
            ChatInteractionClaimResult claim,
            String runId,
            ChatSession session,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            RunStartAttempt startAttempt,
            IntentClarificationContinuationInput input
    ) {
    }
}
