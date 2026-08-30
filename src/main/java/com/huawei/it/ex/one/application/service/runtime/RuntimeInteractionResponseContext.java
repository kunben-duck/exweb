/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.RuntimeInteractionDispatchState;
import com.huawei.it.ex.one.common.trace.TraceContext;
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
        RuntimeForwardHeaders forwardHeaders,
        TraceContext traceContext,
        Map<String, Object> runtimeMetadata,
        RuntimeInteractionDispatchState dispatchState
) {
    public RuntimeInteractionResponseContext {
        responsePayload = ChatPayloadMaps.immutableCopy(responsePayload);
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
        traceContext = traceContext == null ? TraceContext.empty() : traceContext;
        runtimeMetadata = ChatPayloadMaps.immutableCopy(runtimeMetadata);
        dispatchState = dispatchState == null ? RuntimeInteractionDispatchState.untracked() : dispatchState;
    }

    public RuntimeInteractionResponseContext(UserContext user, String sessionId, String runId,
                                             String runtimeProvider, String runtimeSessionId,
                                             String interactionId, String interactionType, String approvalId,
                                             Map<String, Object> responsePayload,
                                             RuntimeForwardHeaders forwardHeaders,
                                             TraceContext traceContext) {
        this(user, sessionId, runId, runtimeProvider, runtimeSessionId, interactionId, interactionType, approvalId,
                responsePayload, forwardHeaders, traceContext, Map.of(), RuntimeInteractionDispatchState.untracked());
    }

    public RuntimeInteractionResponseContext(UserContext user, String sessionId, String runId,
                                             String runtimeProvider, String runtimeSessionId,
                                             String interactionId, String interactionType, String approvalId,
                                             Map<String, Object> responsePayload,
                                             RuntimeForwardHeaders forwardHeaders,
                                             TraceContext traceContext,
                                             RuntimeInteractionDispatchState dispatchState) {
        this(user, sessionId, runId, runtimeProvider, runtimeSessionId, interactionId, interactionType, approvalId,
                responsePayload, forwardHeaders, traceContext, Map.of(), dispatchState);
    }

    public RuntimeInteractionResponseContext(UserContext user, String sessionId, String runId,
                                             String runtimeProvider, String runtimeSessionId,
                                             String interactionId, String interactionType, String approvalId,
                                             Map<String, Object> responsePayload,
                                             RuntimeForwardHeaders forwardHeaders) {
        this(user, sessionId, runId, runtimeProvider, runtimeSessionId, interactionId, interactionType, approvalId,
                responsePayload, forwardHeaders, TraceContext.empty(), Map.of(),
                RuntimeInteractionDispatchState.untracked());
    }
}
