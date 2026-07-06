package com.huawei.finance.front.one.application.integration.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Map;

/**
 * AgentRuntime 等待用户输入后的续接请求。
 *
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param sessionId ChatService 会话标识。
 * @param runId 本次续接 run 标识。
 * @param runtimeSessionId AgentRuntime 实际会话标识。
 * @param provider Runtime provider 编码。
 * @param hitlRequestId ChatService HITL 请求 ID。
 * @param waitingType 等待类型，例如 CLARIFICATION。
 * @param approvalId 下游协议级请求 ID，例如 Relay approval_id。
 * @param responsePayload 用户提交的回答 payload。
 * @param forwardHeaders 入口请求头快照，仅在内存中传递给可信 adapter。
 */
public record AgentRuntimeHitlResponseRequest(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String runtimeSessionId,
        String provider,
        String hitlRequestId,
        String waitingType,
        String approvalId,
        Map<String, Object> responsePayload,
        @JsonIgnore RuntimeForwardHeaders forwardHeaders
) {
    public AgentRuntimeHitlResponseRequest {
        responsePayload = responsePayload == null ? Map.of() : Map.copyOf(responsePayload);
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }
}
