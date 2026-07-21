package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.chat.domain.ChatMessageFeedback;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.Collection;
import java.util.Map;

/** Feedback command and query boundary used by chat interfaces. */
public interface ChatFeedbackService {
    ChatMessageFeedback submit(UserContext user, MessageFeedbackCommand command);

    ChatMessageFeedback cancel(UserContext user, String messageId, String runId);

    Map<String, ChatMessageFeedback> findActiveByMessages(
            UserContext user,
            String sessionId,
            Collection<ChatMessage> messages);
}
