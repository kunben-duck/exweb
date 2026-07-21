package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.model.InteractionMessageStrategy;
import com.huawei.it.ex.one.chat.domain.ChatRun;

final class ChatRunTerminalInteractionSupport {
    private final ChatInteractionApplicationService chatInteractionService;

    ChatRunTerminalInteractionSupport(ChatInteractionApplicationService chatInteractionService) {
        this.chatInteractionService = chatInteractionService;
    }

    boolean reusable(ChatRunTerminalCommitService.TerminalCommitContext context) {
        return context != null && context.continuationInteractionRequest() != null
                && !newTurn(context);
    }

    void markAnswered(ChatRunTerminalCommitService.TerminalCommitContext context) {
        chatInteractionService.markAnswered(context.continuationInteractionRequest());
    }

    void saveInteraction(ChatRunTerminalCommitService.WaitingUserCommitCommand command) {
        chatInteractionService.saveInteraction(command.waitingRequest());
    }

    void markWaiting(ChatRunTerminalCommitService.TerminalCommitContext context) {
        chatInteractionService.markWaiting(context.continuationInteractionRequest());
    }

    int releaseContinuationClaim(ChatRun run) {
        return releaseContinuationClaim(run, null);
    }

    int releaseContinuationClaim(ChatRun run, String explicitInteractionId) {
        if (chatInteractionService == null || run == null || InteractionMessageStrategy.newTurn(run)) {
            return 0;
        }
        Object value = run.metadata() == null ? null : run.metadata().get("interactionId");
        String interactionId = explicitInteractionId == null || explicitInteractionId.isBlank()
                ? (value == null ? null : String.valueOf(value).trim())
                : explicitInteractionId.trim();
        if (interactionId == null || interactionId.isBlank()) {
            return 0;
        }
        return chatInteractionService.markWaitingForRun(
                run.tenantId(), run.userId(), interactionId, run.id());
    }

    private boolean newTurn(ChatRunTerminalCommitService.TerminalCommitContext context) {
        return context != null && InteractionMessageStrategy.newTurn(context.continuationInteractionRequest());
    }
}
