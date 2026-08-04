package com.huawei.it.ex.one.application.integration.agent;

import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;

/**
 * AgentRuntime 等待用户输入后的续接请求。
 *
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param userAccount 用户账号。
 * @param globalUserId 全局用户 ID。
 * @param sessionId ChatService 会话标识。
 * @param runId 本次续接 run 标识。
 * @param runtimeSessionId AgentRuntime 实际会话标识。
 * @param provider Runtime provider 编码。
 * @param interactionId ChatService Interaction 请求 ID。
 * @param interactionType 等待类型，例如 CLARIFICATION。
 * @param approvalId 下游问卷请求 ID；Relay 从 approval-request.approval_id 读取，响应时映射为 request_id。
 * @param responsePayload 用户提交的回答 payload。
 * @param forwardHeaders 入口请求头快照，仅在内存中传递给可信 adapter。
 * @param traceContext 当前 Interaction HTTP 入口捕获的链路追踪快照。
 * @param runtimeMetadata RuntimeBinding 中的服务端私有调用档案。
 * @param dispatchState Runtime Interaction 回答的请求内发送状态。
 */
public record AgentRuntimeInteractionResponseRequest(
        String tenantId,
        String userId,
        String userAccount,
        Long globalUserId,
        String sessionId,
        String runId,
        String runtimeSessionId,
        String provider,
        String interactionId,
        String interactionType,
        String approvalId,
        Map<String, Object> responsePayload,
        @JsonIgnore RuntimeForwardHeaders forwardHeaders,
        @JsonIgnore TraceContext traceContext,
        @JsonIgnore Map<String, Object> runtimeMetadata,
        @JsonIgnore RuntimeInteractionDispatchState dispatchState
) {
    public AgentRuntimeInteractionResponseRequest {
        responsePayload = ChatPayloadMaps.immutableCopy(responsePayload);
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
        traceContext = traceContext == null ? TraceContext.empty() : traceContext;
        runtimeMetadata = ChatPayloadMaps.immutableCopy(runtimeMetadata);
        dispatchState = dispatchState == null ? RuntimeInteractionDispatchState.untracked() : dispatchState;
    }

    public AgentRuntimeInteractionResponseRequest(String tenantId, String userId, String userAccount,
                                                  Long globalUserId, String sessionId, String runId,
                                                  String runtimeSessionId, String provider, String interactionId,
                                                  String interactionType, String approvalId,
                                                  Map<String, Object> responsePayload,
                                                  RuntimeForwardHeaders forwardHeaders,
                                                  TraceContext traceContext) {
        this(tenantId, userId, userAccount, globalUserId, sessionId, runId, runtimeSessionId, provider,
                interactionId, interactionType, approvalId, responsePayload, forwardHeaders, traceContext,
                Map.of(), RuntimeInteractionDispatchState.untracked());
    }

    public AgentRuntimeInteractionResponseRequest(String tenantId, String userId, String userAccount,
                                                  Long globalUserId, String sessionId, String runId,
                                                  String runtimeSessionId, String provider, String interactionId,
                                                  String interactionType, String approvalId,
                                                  Map<String, Object> responsePayload,
                                                  RuntimeForwardHeaders forwardHeaders,
                                                  TraceContext traceContext,
                                                  RuntimeInteractionDispatchState dispatchState) {
        this(tenantId, userId, userAccount, globalUserId, sessionId, runId, runtimeSessionId, provider,
                interactionId, interactionType, approvalId, responsePayload, forwardHeaders, traceContext,
                Map.of(), dispatchState);
    }

    public AgentRuntimeInteractionResponseRequest(String tenantId, String userId, String userAccount,
                                                  Long globalUserId, String sessionId, String runId,
                                                  String runtimeSessionId, String provider, String interactionId,
                                                  String interactionType, String approvalId,
                                                  Map<String, Object> responsePayload,
                                                  RuntimeForwardHeaders forwardHeaders) {
        this(tenantId, userId, userAccount, globalUserId, sessionId, runId, runtimeSessionId, provider,
                interactionId, interactionType, approvalId, responsePayload, forwardHeaders, TraceContext.empty(),
                Map.of(), RuntimeInteractionDispatchState.untracked());
    }
}
