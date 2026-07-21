package com.huawei.it.ex.one.chat.application.model;

import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import java.util.Map;

/**
 * 创建等待用户输入请求的上下文。
 */
public record ChatInteractionCreateContext(
        UserContext user,
        ChatSession session,
        String sourceRunId,
        ChatMessage userMessage,
        String assistantMessageId,
        String runtimeProvider,
        String runtimeBindingId,
        String runtimeSessionId,
        Map<String, Object> requestPayload
) {
}
