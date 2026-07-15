package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;
import java.util.Map;

/**
 * Runtime 等待用户输入后的续接上下文。
 */
public record RuntimeInteractionResponseContext(
        UserContext user,
        String sessionId,
        String runId,
        String runtimeProvider,
        String runtimeSessionId,
        String interactionId,
        String interactionType,
        String approvalId,
        Map<String, Object> responsePayload,
        RuntimeForwardHeaders forwardHeaders
) {
    public RuntimeInteractionResponseContext {
        responsePayload = ChatPayloadMaps.immutableCopy(responsePayload);
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }
}
