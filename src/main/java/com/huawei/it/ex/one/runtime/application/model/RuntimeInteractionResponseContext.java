package com.huawei.it.ex.one.runtime.application.model;

import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.common.event.ChatPayloadMaps;
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
        RuntimeForwardHeaders forwardHeaders,
        TraceContext traceContext
) {
    public RuntimeInteractionResponseContext {
        responsePayload = ChatPayloadMaps.immutableCopy(responsePayload);
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
        traceContext = traceContext == null ? TraceContext.empty() : traceContext;
    }

    public RuntimeInteractionResponseContext(UserContext user, String sessionId, String runId,
                                             String runtimeProvider, String runtimeSessionId,
                                             String interactionId, String interactionType, String approvalId,
                                             Map<String, Object> responsePayload,
                                             RuntimeForwardHeaders forwardHeaders) {
        this(user, sessionId, runId, runtimeProvider, runtimeSessionId, interactionId, interactionType, approvalId,
                responsePayload, forwardHeaders, TraceContext.empty());
    }
}
