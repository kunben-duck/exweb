package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.model.InteractionMessageStrategy;
import com.huawei.it.ex.one.chat.application.repository.ChatRunRepository;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import com.huawei.it.ex.one.chat.domain.ChatInteractionType;
import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.security.domain.UserContext;

final class ChatRunTerminalMessageWriter {
    private static final String WAITING_ASSISTANT_METADATA = "{\"finishReason\":\"WAITING_USER\"}";
    private static final String INTERACTION_ID_METADATA = "interactionId";
    private static final String INTERACTION_ASSISTANT_MESSAGE_ID_METADATA = "interactionAssistantMessageId";

    private final SessionApplicationService sessionService;
    private final ChatRunRepository runRepository;

    ChatRunTerminalMessageWriter(SessionApplicationService sessionService, ChatRunRepository runRepository) {
        this.sessionService = sessionService;
        this.runRepository = runRepository;
    }

    void lockOwnerTerminalSession(ChatRunTerminalCommitService.TerminalCommitContext context) {
        validateOwnerTerminalContext(context);
        sessionService.lockForMessageMutation(
                context.user().tenantId(), context.user().ownerUserId(), context.session());
    }

    void lockExternalPartialAssistantSession(
            ChatRunTerminalCommitService.ExternalTerminalCommitCommand command) {
        AssistantMessageSaveCommand partialAssistant = command.partialAssistant();
        if (partialAssistant == null) {
            return;
        }
        ChatRun run = command.run();
        ChatSession session = partialAssistant.session();
        if (session == null
                || !run.tenantId().equals(partialAssistant.tenantId())
                || !run.userId().equals(partialAssistant.userId())
                || !run.sessionId().equals(session.id())) {
            throw new IllegalArgumentException("stop partial assistant 与 run 归属不一致");
        }
        sessionService.lockForMessageMutation(
                partialAssistant.tenantId(), partialAssistant.userId(), session);
    }

    ChatMessage saveCompletedAssistant(ChatRunTerminalCommitService.CompletedCommitCommand command) {
        ChatRunTerminalCommitService.TerminalCommitContext context = command.context();
        UserContext user = context.user();
        if (context.continuationInteractionRequest() == null || newTurnInteraction(context)) {
            return sessionService.saveAssistantMessage(new AssistantMessageSaveCommand(
                    user.tenantId(),
                    user.ownerUserId(),
                    context.session(),
                    context.assistant().finalContent(),
                    context.runId(),
                    context.messagePlan().userMessage().id(),
                    context.messagePlan().regeneratedFromMessageId(),
                    context.assistant().parts(),
                    null,
                    command.target().assistantMessageId()
            ));
        }
        return sessionService.updateAssistantMessage(new AssistantMessageUpdateCommand(
                user.tenantId(),
                user.ownerUserId(),
                context.session(),
                context.continuationInteractionRequest().assistantMessageId(),
                context.assistant().finalContent(),
                context.runId(),
                context.assistant().parts(),
                null
        ));
    }

    ChatMessage saveWaitingAssistant(ChatRunTerminalCommitService.WaitingUserCommitCommand command) {
        ChatRunTerminalCommitService.TerminalCommitContext context = command.context();
        UserContext user = context.user();
        ChatInteractionRequest continuation = context.continuationInteractionRequest();
        if (continuation == null || newTurnInteraction(context)) {
            if (continuation != null) {
                validateNewTurnWaitingRequest(context, command);
            }
            return sessionService.saveAssistantMessage(new AssistantMessageSaveCommand(
                    user.tenantId(),
                    user.ownerUserId(),
                    context.session(),
                    context.assistant().finalContent(),
                    context.runId(),
                    context.messagePlan().userMessage().id(),
                    context.messagePlan().regeneratedFromMessageId(),
                    context.assistant().parts(),
                    WAITING_ASSISTANT_METADATA,
                    command.target().assistantMessageId(),
                    appendWaitingAnswer(command.waitingRequest())
            ));
        }
        String existingAssistantId = continuation.assistantMessageId();
        String nextAssistantId = command.waitingRequest() == null
                ? null
                : command.waitingRequest().assistantMessageId();
        if (existingAssistantId == null || existingAssistantId.isBlank()
                || !existingAssistantId.equals(command.target().assistantMessageId())
                || !existingAssistantId.equals(nextAssistantId)) {
            throw new IllegalStateException("多轮 Interaction 必须复用同一 assistantMessageId");
        }
        return sessionService.updateAssistantMessage(new AssistantMessageUpdateCommand(
                user.tenantId(),
                user.ownerUserId(),
                context.session(),
                existingAssistantId,
                context.assistant().finalContent(),
                context.runId(),
                context.assistant().parts(),
                WAITING_ASSISTANT_METADATA,
                appendWaitingAnswer(command.waitingRequest())
        ));
    }

    void persistExternalPartialAssistant(
            ChatRunTerminalCommitService.ExternalTerminalCommitCommand command) {
        AssistantMessageSaveCommand partialAssistant = command.partialAssistant();
        if (partialAssistant == null) {
            return;
        }
        String expectedId = partialAssistant.normalizedMessageId();
        ChatMessage saved;
        if (interactionContinuation(command.run()) && !InteractionMessageStrategy.newTurn(command.run())) {
            String assistantMessageId = interactionAssistantMessageId(command.run());
            if (assistantMessageId == null || !assistantMessageId.equals(expectedId)) {
                throw new IllegalStateException("Interaction stop partial assistant 必须复用原 assistantMessageId");
            }
            saved = sessionService.updateAssistantMessage(new AssistantMessageUpdateCommand(
                    partialAssistant.tenantId(),
                    partialAssistant.userId(),
                    partialAssistant.session(),
                    assistantMessageId,
                    partialAssistant.content(),
                    partialAssistant.runId(),
                    partialAssistant.safePartDrafts(),
                    partialAssistant.metadataJson()
            ));
        } else {
            saved = sessionService.saveAssistantMessage(partialAssistant);
        }
        if (expectedId == null || !expectedId.equals(saved.id())) {
            throw new IllegalStateException("stop partial assistant ID 与预分配 ID 不一致");
        }
        bindAssistantMessage(command.run().id(), saved.id());
    }

    void bindAssistantMessage(String runId, String assistantMessageId) {
        runRepository.findById(runId)
                .ifPresent(run -> runRepository.save(run.withAssistantMessageId(assistantMessageId)));
    }

    void advanceLatestMessageSeq(ChatRunTerminalCommitService.TerminalCommitContext context, ChatEvent stored) {
        sessionService.advanceLatestMessageSeq(context.user(), context.session(), stored.sequence());
    }

    static void validateOwnerTerminalContext(ChatRunTerminalCommitService.TerminalCommitContext context) {
        if (context == null || context.user() == null || context.session() == null
                || context.runId() == null || context.runId().isBlank() || context.executionClaim() == null) {
            throw new IllegalArgumentException("owner 终态提交上下文不完整");
        }
    }

    private boolean interactionContinuation(ChatRun run) {
        return metadataText(run, INTERACTION_ID_METADATA) != null;
    }

    private String interactionAssistantMessageId(ChatRun run) {
        return metadataText(run, INTERACTION_ASSISTANT_MESSAGE_ID_METADATA);
    }

    private String metadataText(ChatRun run, String key) {
        if (run == null || run.metadata() == null || key == null) {
            return null;
        }
        Object value = run.metadata().get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private boolean appendWaitingAnswer(ChatInteractionRequest waitingRequest) {
        return waitingRequest == null
                || waitingRequest.interactionType() != ChatInteractionType.ROUTE_SWITCH_CONFIRMATION;
    }

    private void validateNewTurnWaitingRequest(
            ChatRunTerminalCommitService.TerminalCommitContext context,
            ChatRunTerminalCommitService.WaitingUserCommitCommand command) {
        ChatInteractionRequest waiting = command.waitingRequest();
        String expectedUserId = context.messagePlan() == null || context.messagePlan().userMessage() == null
                ? null
                : context.messagePlan().userMessage().id();
        String expectedAssistantId = command.target() == null ? null : command.target().assistantMessageId();
        if (waiting == null || expectedUserId == null || expectedAssistantId == null
                || !expectedUserId.equals(waiting.userMessageId())
                || !expectedAssistantId.equals(waiting.assistantMessageId())) {
            throw new IllegalStateException("意图澄清下一轮 Interaction 必须关联本轮新 user/assistant 消息");
        }
    }

    private boolean newTurnInteraction(ChatRunTerminalCommitService.TerminalCommitContext context) {
        return context != null && InteractionMessageStrategy.newTurn(context.continuationInteractionRequest());
    }
}
