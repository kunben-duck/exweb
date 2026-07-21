package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.coordinator.ChatRunCompletionCoordinator.CompletionMessageTarget;
import com.huawei.it.ex.one.chat.application.coordinator.ChatRunCompletionCoordinator.CompletionPlan;
import com.huawei.it.ex.one.chat.application.model.InteractionMessageStrategy;
import com.huawei.it.ex.one.chat.application.model.PersistenceAcknowledgedEvent;
import com.huawei.it.ex.one.chat.application.model.RunEventPipelineContext;
import com.huawei.it.ex.one.chat.application.model.ChatRunFailureMapper;
import com.huawei.it.ex.one.chat.application.service.ChatInteractionApplicationService;
import com.huawei.it.ex.one.chat.application.service.ChatRunApplicationService;
import com.huawei.it.ex.one.chat.application.service.ChatStreamApplicationService;
import com.huawei.it.ex.one.chat.application.service.AssistantMessageSaveCommand;
import com.huawei.it.ex.one.chat.application.service.AssistantMessageUpdateCommand;
import com.huawei.it.ex.one.chat.application.service.SessionApplicationService;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import com.huawei.it.ex.one.chat.domain.ChatInteractionType;
import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentRefusal;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingService;
import org.springframework.stereotype.Component;

/** Persists and post-processes one non-batched chat event in the existing order. */
@Component
public class ChatEventCommitCoordinator {
    private final SessionApplicationService sessionService;
    private final ChatRunApplicationService chatRunService;
    private final ChatStreamApplicationService chatStreamService;
    private final ChatInteractionApplicationService chatInteractionService;
    private final RuntimeBindingService runtimeBindingService;
    private final ChatRunCompletionCoordinator completionCoordinator;
    private final DomainAgentRefusalCoordinator refusalCoordinator;
    private final CommittedChatEventObserver committedEventObserver;
    private final ChatRunFailureMapper runFailureMapper = new ChatRunFailureMapper();

    public ChatEventCommitCoordinator(SessionApplicationService sessionService,
                                      ChatRunApplicationService chatRunService,
                                      ChatStreamApplicationService chatStreamService,
                                      ChatInteractionApplicationService chatInteractionService,
                                      RuntimeBindingService runtimeBindingService,
                                      ChatRunCompletionCoordinator completionCoordinator,
                                      DomainAgentRefusalCoordinator refusalCoordinator,
                                      CommittedChatEventObserver committedEventObserver) {
        this.sessionService = sessionService;
        this.chatRunService = chatRunService;
        this.chatStreamService = chatStreamService;
        this.chatInteractionService = chatInteractionService;
        this.runtimeBindingService = runtimeBindingService;
        this.completionCoordinator = completionCoordinator;
        this.refusalCoordinator = refusalCoordinator;
        this.committedEventObserver = committedEventObserver;
    }

    public ChatEvent commit(ChatEvent event, RunEventPipelineContext context) {
        CompletionPlan completionPlan = completionCoordinator.prepare(event, context);
        CompletionMessageTarget completionTarget = completionPlan.target();
        ChatInteractionRequest waitingRequest = completionPlan.waitingRequest();
        ChatEvent eventToPersist = completionPlan.eventToPersist();
        if (completionCoordinator.hasTerminalCommitService() && waitingRequest != null) {
            return completionCoordinator.commitWaitingUser(completionPlan, context);
        }
        if (completionCoordinator.hasTerminalCommitService()
                && completionCoordinator.ownerRunTerminal(eventToPersist)) {
            if ("run.completed".equals(eventToPersist.type()) && completionTarget.messageReady()) {
                return completionCoordinator.commitCompleted(completionPlan, context);
            }
            return completionCoordinator.commitTerminalOnly(eventToPersist, context);
        }
        ChatEvent stored = chatStreamService.appendWithExecutionGuard(eventToPersist, context.executionClaim());
        context.assistant().observe(stored);
        cancelPersistedAutomaticDomainAgentBinding(stored, context);
        completionCoordinator.rememberPendingInteractionRequest(stored, context);
        saveCompletedAssistant(stored, completionTarget, context);
        saveWaitingAssistant(stored, completionTarget, waitingRequest, context);
        chatRunService.observeEvent(stored);
        restoreContinuationInteractionOnFailure(stored, context);
        markRuntimeSessionUnavailable(stored, context);
        committedEventObserver.observeBindingAndPublish(stored, context, context.bindingRef().get());
        acknowledgePersistence(event);
        return stored;
    }

    private void saveCompletedAssistant(ChatEvent stored,
                                        CompletionMessageTarget completionTarget,
                                        RunEventPipelineContext context) {
        if (!"run.completed".equals(stored.type()) || !completionTarget.messageReady()) {
            return;
        }
        ChatMessage savedAssistant = context.continuationInteractionRequest() == null
                || InteractionMessageStrategy.newTurn(context.continuationInteractionRequest())
                ? sessionService.saveAssistantMessage(new AssistantMessageSaveCommand(
                        context.user().tenantId(),
                        context.user().ownerUserId(),
                        context.session(),
                        context.assistant().finalContent(),
                        context.runId(),
                        context.messagePlan().userMessage().id(),
                        context.messagePlan().regeneratedFromMessageId(),
                        context.assistant().parts(),
                        null,
                        completionTarget.assistantMessageId()))
                : sessionService.updateAssistantMessage(new AssistantMessageUpdateCommand(
                        context.user().tenantId(),
                        context.user().ownerUserId(),
                        context.session(),
                        context.continuationInteractionRequest().assistantMessageId(),
                        context.assistant().finalContent(),
                        context.runId(),
                        context.assistant().parts(),
                        null));
        sessionService.advanceLatestMessageSeq(context.user(), context.session(), stored.sequence());
        chatRunService.bindAssistantMessage(context.runId(), savedAssistant.id());
        context.bindingRef().set(runtimeBindingService.completeAfterRun(
                context.bindingRef().get(), context.runId(), savedAssistant.id()));
        if (context.continuationInteractionRequest() != null
                && !InteractionMessageStrategy.newTurn(context.continuationInteractionRequest())) {
            chatInteractionService.markAnswered(context.continuationInteractionRequest());
        }
    }

    private void saveWaitingAssistant(ChatEvent stored,
                                      CompletionMessageTarget completionTarget,
                                      ChatInteractionRequest waitingRequest,
                                      RunEventPipelineContext context) {
        if (!"run.waiting_user".equals(stored.type())
                || !completionTarget.messageReady()
                || waitingRequest == null) {
            return;
        }
        ChatMessage savedAssistant = sessionService.saveAssistantMessage(new AssistantMessageSaveCommand(
                context.user().tenantId(),
                context.user().ownerUserId(),
                context.session(),
                context.assistant().finalContent(),
                context.runId(),
                context.messagePlan().userMessage().id(),
                context.messagePlan().regeneratedFromMessageId(),
                context.assistant().parts(),
                "{\"finishReason\":\"WAITING_USER\"}",
                completionTarget.assistantMessageId(),
                waitingRequest.interactionType() != ChatInteractionType.ROUTE_SWITCH_CONFIRMATION));
        sessionService.advanceLatestMessageSeq(context.user(), context.session(), stored.sequence());
        chatRunService.bindAssistantMessage(context.runId(), savedAssistant.id());
        context.bindingRef().set(runtimeBindingService.touchAndMoveToLeaf(
                context.bindingRef().get(), context.runId(), savedAssistant.id()));
        chatInteractionService.saveInteraction(waitingRequest);
        if (context.continuationInteractionRequest() != null
                && !InteractionMessageStrategy.newTurn(context.continuationInteractionRequest())) {
            chatInteractionService.markAnswered(context.continuationInteractionRequest());
        }
    }

    private void restoreContinuationInteractionOnFailure(ChatEvent stored,
                                                         RunEventPipelineContext context) {
        if (context.continuationInteractionRequest() != null
                && !InteractionMessageStrategy.newTurn(context.continuationInteractionRequest())
                && ("run.failed".equals(stored.type()) || "run.cancelled".equals(stored.type()))) {
            chatInteractionService.markWaiting(context.continuationInteractionRequest());
        }
    }

    private void markRuntimeSessionUnavailable(ChatEvent stored,
                                               RunEventPipelineContext context) {
        if ("run.failed".equals(stored.type())
                && runFailureMapper.runtimeSessionUnavailable(stored.payload())) {
            context.bindingRef().set(runtimeBindingService.markNotRoutable(
                    context.bindingRef().get(), "RUNTIME_SESSION_UNAVAILABLE"));
        }
    }

    private void cancelPersistedAutomaticDomainAgentBinding(ChatEvent stored,
                                                            RunEventPipelineContext context) {
        DomainAgentRefusal refusal = DomainAgentRefusal.from(stored);
        if (refusal == null) {
            return;
        }
        context.bindingRef().set(refusalCoordinator.markRejectedAutomaticBindingNotRoutable(
                context.bindingRef().get(), refusal));
    }

    private void acknowledgePersistence(ChatEvent event) {
        if (event instanceof PersistenceAcknowledgedEvent acknowledged) {
            acknowledged.persisted().tryEmitEmpty();
        }
    }
}
