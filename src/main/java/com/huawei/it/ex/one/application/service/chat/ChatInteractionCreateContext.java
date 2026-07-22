package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatSession;

import java.util.Map;

/**
 * 创建等待用户输入请求的上下文。
 */
record ChatInteractionCreateContext(
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
