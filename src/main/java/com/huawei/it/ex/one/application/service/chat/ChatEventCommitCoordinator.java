/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatMessage;

/** Persists and post-processes one non-batched chat event in the existing order. */
final class ChatEventCommitCoordinator {
    private final SessionApplicationService sessionService;
    private final ChatRunApplicationService chatRunService;
    private final ChatStreamApplicationService chatStreamService;
    private final ChatInteractionApplicationService chatInteractionService;
    private final RuntimeBindingApplicationService runtimeBindingService;
    private final ChatRunCompletionCoordinator completionCoordinator;
    private final DomainAgentRefusalCoordinator refusalCoordinator;
    private final CommittedChatEventObserver committedEventObserver;
    private final AmbiguousRouteWaitPolicy ambiguousRouteWaitPolicy;
    private final RouteSwitchConfirmationWaitPolicy routeSwitchConfirmationWaitPolicy;
    private final RelayQuestionnaireWaitPolicy relayQuestionnaireWaitPolicy;
    private final DomainAgentAsyncTaskApplicationService asyncTaskService;
    private final ChatRunFailureMapper runFailureMapper = new ChatRunFailureMapper();

    ChatEventCommitCoordinator(SessionApplicationService sessionService,
                               ChatRunApplicationService chatRunService,
                               ChatStreamApplicationService chatStreamService,
                               ChatInteractionApplicationService chatInteractionService,
                               RuntimeBindingApplicationService runtimeBindingService,
                               ChatRunCompletionCoordinator completionCoordinator,
                               DomainAgentRefusalCoordinator refusalCoordinator,
                               CommittedChatEventObserver committedEventObserver,
                               AmbiguousRouteWaitPolicy ambiguousRouteWaitPolicy,
                               RouteSwitchConfirmationWaitPolicy routeSwitchConfirmationWaitPolicy,
                               RelayQuestionnaireWaitPolicy relayQuestionnaireWaitPolicy,
                               DomainAgentAsyncTaskApplicationService asyncTaskService) {
        this.sessionService = sessionService;
        this.chatRunService = chatRunService;
        this.chatStreamService = chatStreamService;
        this.chatInteractionService = chatInteractionService;
        this.runtimeBindingService = runtimeBindingService;
        this.completionCoordinator = completionCoordinator;
        this.refusalCoordinator = refusalCoordinator;
        this.committedEventObserver = committedEventObserver;
        this.ambiguousRouteWaitPolicy = ambiguousRouteWaitPolicy;
        this.routeSwitchConfirmationWaitPolicy = routeSwitchConfirmationWaitPolicy;
        this.relayQuestionnaireWaitPolicy = relayQuestionnaireWaitPolicy;
        this.asyncTaskService = asyncTaskService;
    }

    ChatEventCommitCoordinator(SessionApplicationService sessionService,
                               ChatRunApplicationService chatRunService,
                               ChatStreamApplicationService chatStreamService,
                               ChatInteractionApplicationService chatInteractionService,
                               RuntimeBindingApplicationService runtimeBindingService,
                               ChatRunCompletionCoordinator completionCoordinator,
                               DomainAgentRefusalCoordinator refusalCoordinator,
                               CommittedChatEventObserver committedEventObserver,
                               AmbiguousRouteWaitPolicy ambiguousRouteWaitPolicy,
                               RouteSwitchConfirmationWaitPolicy routeSwitchConfirmationWaitPolicy,
                               RelayQuestionnaireWaitPolicy relayQuestionnaireWaitPolicy) {
        this(sessionService, chatRunService, chatStreamService, chatInteractionService,
                runtimeBindingService, completionCoordinator, refusalCoordinator,
                committedEventObserver, ambiguousRouteWaitPolicy, routeSwitchConfirmationWaitPolicy,
                relayQuestionnaireWaitPolicy, null);
    }

    ChatEventCommitCoordinator(SessionApplicationService sessionService,
                               ChatRunApplicationService chatRunService,
                               ChatStreamApplicationService chatStreamService,
                               ChatInteractionApplicationService chatInteractionService,
                               RuntimeBindingApplicationService runtimeBindingService,
                               ChatRunCompletionCoordinator completionCoordinator,
                               DomainAgentRefusalCoordinator refusalCoordinator,
                               CommittedChatEventObserver committedEventObserver,
                               AmbiguousRouteWaitPolicy ambiguousRouteWaitPolicy,
                               RelayQuestionnaireWaitPolicy relayQuestionnaireWaitPolicy) {
        this(sessionService, chatRunService, chatStreamService, chatInteractionService,
                runtimeBindingService, completionCoordinator, refusalCoordinator,
                committedEventObserver, ambiguousRouteWaitPolicy, null, relayQuestionnaireWaitPolicy, null);
    }

    ChatEventCommitCoordinator(SessionApplicationService sessionService,
                               ChatRunApplicationService chatRunService,
                               ChatStreamApplicationService chatStreamService,
                               ChatInteractionApplicationService chatInteractionService,
                               RuntimeBindingApplicationService runtimeBindingService,
                               ChatRunCompletionCoordinator completionCoordinator,
                               DomainAgentRefusalCoordinator refusalCoordinator,
                               CommittedChatEventObserver committedEventObserver,
                               AmbiguousRouteWaitPolicy ambiguousRouteWaitPolicy) {
        this(sessionService, chatRunService, chatStreamService, chatInteractionService,
                runtimeBindingService, completionCoordinator, refusalCoordinator,
                committedEventObserver, ambiguousRouteWaitPolicy, null, null, null);
    }

    ChatEventCommitCoordinator(SessionApplicationService sessionService,
                               ChatRunApplicationService chatRunService,
                               ChatStreamApplicationService chatStreamService,
                               ChatInteractionApplicationService chatInteractionService,
                               RuntimeBindingApplicationService runtimeBindingService,
                               ChatRunCompletionCoordinator completionCoordinator,
                               DomainAgentRefusalCoordinator refusalCoordinator,
                               CommittedChatEventObserver committedEventObserver) {
        this(sessionService, chatRunService, chatStreamService, chatInteractionService,
                runtimeBindingService, completionCoordinator, refusalCoordinator,
                committedEventObserver, null, null, null, null);
    }

    ChatEvent commit(ChatEvent event, RunEventPipelineContext context) {
        if (event instanceof com.huawei.it.ex.one.domain.chat.RunAsyncRunningEvent asyncEvent) {
            if (asyncTaskService == null) {
                throw new IllegalStateException("DomainAgent async task service is unavailable");
            }
            DomainAgentAsyncTaskApplicationService.StartResult result =
                    asyncTaskService.commitStarted(asyncEvent, context);
            context.asyncRunningObserved().set(true);
            chatRunService.synchronizeCommittedRunCache(result.run());
            chatStreamService.publishPersisted(result.event());
            return result.event();
        }
        ChatEvent preparedEvent = ambiguousRouteWaitPolicy == null
                ? event
                : ambiguousRouteWaitPolicy.decorate(event);
        preparedEvent = routeSwitchConfirmationWaitPolicy == null
                ? preparedEvent
                : routeSwitchConfirmationWaitPolicy.decorate(preparedEvent);
        preparedEvent = relayQuestionnaireWaitPolicy == null
                ? preparedEvent
                : relayQuestionnaireWaitPolicy.decorate(preparedEvent);
        ChatRunCompletionCoordinator.CompletionPlan completion =
                completionCoordinator.prepare(preparedEvent, context);
        ChatRunCompletionCoordinator.CompletionMessageTarget target = completion.target();
        ChatInteractionRequest waitingRequest = completion.waitingRequest();
        ChatEvent eventToPersist = completion.eventToPersist();
        if (completionCoordinator.hasTerminalCommitService() && waitingRequest != null) {
            return completionCoordinator.commitWaitingUser(completion, context);
        }
        if (completionCoordinator.hasTerminalCommitService()
                && completionCoordinator.ownerRunTerminal(eventToPersist)) {
            if ("run.completed".equals(eventToPersist.type()) && target.messageReady()) {
                return completionCoordinator.commitCompleted(completion, context);
            }
            return completionCoordinator.commitTerminalOnly(eventToPersist, context);
        }
        ChatEvent stored = chatStreamService.appendWithExecutionGuard(
                eventToPersist, context.executionClaim());
        context.assistant().observe(stored);
        cancelPersistedAutomaticDomainAgentBinding(stored, context);
        completionCoordinator.rememberPendingInteractionRequest(stored, context);
        saveCompletedAssistant(stored, target, context);
        saveWaitingAssistant(stored, target, waitingRequest, context);
        chatRunService.observeEvent(stored);
        restoreContinuationInteractionOnFailure(stored, context);
        markRuntimeSessionUnavailable(stored, context);
        committedEventObserver.observeBindingAndPublish(stored, context, context.bindingRef().get());
        acknowledgePersistence(event);
        return stored;
    }

    private void saveCompletedAssistant(
            ChatEvent stored,
            ChatRunCompletionCoordinator.CompletionMessageTarget target,
            RunEventPipelineContext context) {
        if (!"run.completed".equals(stored.type()) || !target.messageReady()) {
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
                        context.assistant().assistantMetadata(null),
                        target.assistantMessageId(),
                        context.assistant().appendAnswerPart()))
                : sessionService.updateAssistantMessage(new AssistantMessageUpdateCommand(
                        context.user().tenantId(),
                        context.user().ownerUserId(),
                        context.session(),
                        context.continuationInteractionRequest().assistantMessageId(),
                        context.assistant().finalContent(),
                        context.runId(),
                        context.assistant().parts(),
                        context.assistant().assistantMetadata(null),
                        context.assistant().appendAnswerPart()));
        sessionService.advanceLatestMessageSeq(context.user(), context.session(), stored.sequence());
        chatRunService.bindAssistantMessage(context.runId(), savedAssistant.id());
        context.bindingRef().set(runtimeBindingService.completeAfterRun(
                context.bindingRef().get(), context.runId(), savedAssistant.id()));
        markContinuationAnswered(context);
    }

    private void saveWaitingAssistant(
            ChatEvent stored,
            ChatRunCompletionCoordinator.CompletionMessageTarget target,
            ChatInteractionRequest waitingRequest,
            RunEventPipelineContext context) {
        if (!"run.waiting_user".equals(stored.type())
                || !target.messageReady()
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
                context.assistant().assistantMetadata("{\"finishReason\":\"WAITING_USER\"}"),
                target.assistantMessageId(),
                context.assistant().appendAnswerPart()
                        && waitingRequest.interactionType() != ChatInteractionType.ROUTE_SWITCH_CONFIRMATION));
        sessionService.advanceLatestMessageSeq(context.user(), context.session(), stored.sequence());
        chatRunService.bindAssistantMessage(context.runId(), savedAssistant.id());
        context.bindingRef().set(runtimeBindingService.touchAndMoveToLeaf(
                context.bindingRef().get(), context.runId(), savedAssistant.id()));
        chatInteractionService.saveInteraction(waitingRequest);
        markContinuationAnswered(context);
    }

    private void markContinuationAnswered(RunEventPipelineContext context) {
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
        if (refusal != null) {
            context.bindingRef().set(refusalCoordinator.markRejectedAutomaticBindingNotRoutable(
                    context.bindingRef().get(), refusal));
        }
    }

    private void acknowledgePersistence(ChatEvent event) {
        if (event instanceof PersistenceAcknowledgedEvent acknowledged) {
            acknowledged.persisted().tryEmitEmpty();
        }
    }
}
