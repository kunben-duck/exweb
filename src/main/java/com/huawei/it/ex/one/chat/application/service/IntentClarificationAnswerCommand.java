package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.domain.AttachmentRef;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.List;

/** Message mutation inputs for one accepted intent clarification answer. */
public record IntentClarificationAnswerCommand(
        UserContext user,
        ChatSession session,
        String runId,
        String parentAssistantMessageId,
        String answerText,
        List<AttachmentRef> attachments) {
    public IntentClarificationAnswerCommand {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
