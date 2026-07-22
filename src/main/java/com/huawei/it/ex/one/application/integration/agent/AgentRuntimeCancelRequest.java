package com.huawei.it.ex.one.application.integration.agent;

import com.huawei.it.ex.one.common.trace.TraceContext;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;

/**
 * SuperAgent 请求 AgentRuntime 取消某个 run 的防腐层契约。
 *
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param sessionId 前端聊天会话标识。
 * @param runId 需要取消的 SuperAgent run 标识。
 * @param runtimeSessionId AgentRuntime 实际会话标识；为空时可信 adapter 可回退到 ChatService sessionId。
 * @param provider 当前 Runtime provider 编码。
 * @param runtimeTargetId 当前 Runtime 内部目标 ID，例如 DomainAgent skillId；不需要该字段的 provider 可忽略。
 * @param reason 取消原因。
 * @param metadata 扩展诊断元数据。
 * @param forwardHeaders stop 请求入口捕获的请求头快照；仅用于可信 Runtime adapter 的出站请求头。
 * @param traceContext stop 请求入口捕获的链路追踪快照。
 */
public record AgentRuntimeCancelRequest(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String runtimeSessionId,
        String provider,
        String runtimeTargetId,
        String reason,
        Map<String, Object> metadata,
        @JsonIgnore RuntimeForwardHeaders forwardHeaders,
        @JsonIgnore TraceContext traceContext
) {
    public AgentRuntimeCancelRequest(String tenantId, String userId, String sessionId, String runId,
                                     String runtimeSessionId, String provider, String reason,
                                     Map<String, Object> metadata,
                                     RuntimeForwardHeaders forwardHeaders) {
        this(tenantId, userId, sessionId, runId, runtimeSessionId, provider, null, reason, metadata, forwardHeaders,
                TraceContext.empty());
    }

    public AgentRuntimeCancelRequest(String tenantId, String userId, String sessionId, String runId,
                                     String runtimeSessionId, String provider, String runtimeTargetId, String reason,
                                     Map<String, Object> metadata, RuntimeForwardHeaders forwardHeaders) {
        this(tenantId, userId, sessionId, runId, runtimeSessionId, provider, runtimeTargetId, reason, metadata,
                forwardHeaders, TraceContext.empty());
    }

    public AgentRuntimeCancelRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
        traceContext = traceContext == null ? TraceContext.empty() : traceContext;
    }
}
