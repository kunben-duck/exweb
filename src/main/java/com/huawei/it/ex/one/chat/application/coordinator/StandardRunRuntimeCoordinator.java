package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.model.AssistantAssembly;
import com.huawei.it.ex.one.chat.application.model.IntentClarificationDocuments;
import com.huawei.it.ex.one.chat.application.model.RunEventPipelineContext;
import com.huawei.it.ex.one.chat.application.service.LocalChatRunExecutionRegistry;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.chat.domain.ChatRunMessagePlan;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.model.RuntimeSessionMode;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Preserves the post-admission standard run route and Runtime execution sequence. */
@Component
public class StandardRunRuntimeCoordinator {
    private final IntentClarificationContextAssembler clarificationAssembler;
    private final RouteResolutionCoordinator routeResolutionCoordinator;
    private final IntentFlowCoordinator intentFlowCoordinator;
    private final ChatEventPersistenceCoordinator eventPersistenceCoordinator;
    private final ChatRunFailureCoordinator failureCoordinator;
    private final LocalChatRunExecutionRegistry runExecutionRegistry;

    public StandardRunRuntimeCoordinator(
            IntentClarificationContextAssembler clarificationAssembler,
            RouteResolutionCoordinator routeResolutionCoordinator,
            IntentFlowCoordinator intentFlowCoordinator,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            ChatRunFailureCoordinator failureCoordinator,
            LocalChatRunExecutionRegistry runExecutionRegistry) {
        this.clarificationAssembler = clarificationAssembler;
        this.routeResolutionCoordinator = routeResolutionCoordinator;
        this.intentFlowCoordinator = intentFlowCoordinator;
        this.eventPersistenceCoordinator = eventPersistenceCoordinator;
        this.failureCoordinator = failureCoordinator;
        this.runExecutionRegistry = runExecutionRegistry;
    }

    public RuntimePlan prepare(StandardRunInputPreparer.PreparedRun prepared,
                               StandardRunAdmissionCoordinator.Admission admission) {
        ChatCommand runCommand = commandForExecution(prepared.command(), admission.messagePlan());
        String currentRouteQuery = clarificationAssembler.routeMemoryQuery(admission.messagePlan(), null);
        String intentQuery = InteractionContinuationCoordinator.answerWithAttachments(
                runCommand.message(), prepared.attachments());
        String runtimeBindingLeafId = runtimeBindingLeafId(admission.messagePlan());
        return new RuntimePlan(
                prepared, admission, runCommand, currentRouteQuery, intentQuery, runtimeBindingLeafId,
                new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(RuntimeSessionMode.RESUME), new AtomicReference<>(),
                new AssistantAssembly());
    }

    public Flux<ChatEvent> execute(RuntimePlan plan, RunExecutionClaim executionClaim) {
        StandardRunInputPreparer.PreparedRun prepared = plan.prepared();
        try {
            RunEventPipelineContext context = pipelineContext(plan, executionClaim);
            return eventPersistenceCoordinator.executeAfterRunStarted(context, () -> {
                routeResolutionCoordinator.prepareInitial(
                        new RouteResolutionCoordinator.InitialRoutePreparation(
                                prepared.user(), prepared.session(), prepared.runId(), plan.runtimeBindingLeafId(),
                                plan.runCommand(), prepared.explicitDomainAgentId(), prepared.forceReroute(),
                                plan.routeRef(), plan.bindingRef(), plan.runtimeSessionModeRef()));
                return intentFlowCoordinator.execute(new IntentFlowCoordinator.Request(
                        prepared.user(), prepared.session(), plan.runCommand(), prepared.attachments(),
                        prepared.documents(), prepared.memory(), prepared.runId(), plan.runtimeBindingLeafId(),
                        prepared.forwardHeaders(), prepared.traceContext(), plan.routeRef(), plan.bindingRef(),
                        plan.runtimeSessionModeRef(), executionClaim, plan.admission().run(),
                        plan.currentRouteQuery(), plan.intentQuery(), plan.intentQuery(), null));
            });
        } catch (RuntimeException ex) {
            RunEventPipelineContext context = pipelineContext(plan, executionClaim);
            return eventPersistenceCoordinator.persistAndPublish(
                            Flux.just(failureCoordinator.runtimeErrorEvent(
                                    prepared.runId(), prepared.session().id(), ex)), context)
                    .doFinally(ignored -> runExecutionRegistry.complete(executionClaim));
        }
    }

    private RunEventPipelineContext pipelineContext(RuntimePlan plan,
                                                    RunExecutionClaim executionClaim) {
        StandardRunInputPreparer.PreparedRun prepared = plan.prepared();
        return new RunEventPipelineContext(
                prepared.user(), prepared.session(), plan.admission().messagePlan(),
                plan.routeRef(), plan.bindingRef(), plan.assistant(), prepared.runId(), executionClaim,
                plan.pendingInteractionPayloadRef(), null, prepared.startAttempt(),
                IntentClarificationDocuments.fromDocuments(prepared.documents()));
    }

    private ChatCommand commandForExecution(ChatCommand normalized,
                                            ChatRunMessagePlan messagePlan) {
        ChatMessage userMessage = messagePlan.userMessage();
        return new ChatCommand(
                normalized.commandId(), normalized.tenantId(), normalized.userId(), normalized.sessionId(),
                normalized.conversationId(), normalized.channel(), userMessage.content(), normalized.attachments(),
                normalized.metadata(), normalized.targetType(), normalized.targetId(), messagePlan.runMode(),
                messagePlan.parentMessageId(), normalized.editedMessageId(), normalized.regeneratedMessageId(),
                normalized.routeTrigger(), normalized.interactionId(), normalized.approved(), normalized.scope(),
                normalized.questionnaireAnswers(), normalized.appId(), normalized.appName());
    }

    private String runtimeBindingLeafId(ChatRunMessagePlan messagePlan) {
        return switch (messagePlan.runMode()) {
            case NEXT -> messagePlan.parentMessageId();
            case EDIT_USER, REGENERATE_ASSISTANT -> messagePlan.userMessage().id();
            case CONTINUE_INTERACTION ->
                    throw new IllegalArgumentException(
                            "CONTINUE_INTERACTION 不参与 RuntimeBinding leaf 计算");
        };
    }

    public record RuntimePlan(
            StandardRunInputPreparer.PreparedRun prepared,
            StandardRunAdmissionCoordinator.Admission admission,
            ChatCommand runCommand,
            String currentRouteQuery,
            String intentQuery,
            String runtimeBindingLeafId,
            AtomicReference<RuntimeBinding> bindingRef,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeSessionMode> runtimeSessionModeRef,
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef,
            AssistantAssembly assistant
    ) {
    }
}
