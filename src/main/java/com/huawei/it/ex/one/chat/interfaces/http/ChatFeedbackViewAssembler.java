package com.huawei.it.ex.one.chat.interfaces.http;

import com.huawei.it.ex.one.chat.domain.ChatMessageFeedback;
import com.huawei.it.ex.one.chat.interfaces.dto.MessageFeedbackDto;
import org.springframework.stereotype.Component;

/** Maps feedback domain values to the established HTTP response shape. */
@Component
public final class ChatFeedbackViewAssembler {
    public MessageFeedbackDto toDto(ChatMessageFeedback feedback) {
        return new MessageFeedbackDto(
                feedback.id(),
                feedback.messageId(),
                feedback.runId(),
                feedback.rating(),
                feedback.status(),
                feedback.reasonCode(),
                feedback.commentText(),
                feedback.createdAt(),
                feedback.updatedAt()
        );
    }
}
