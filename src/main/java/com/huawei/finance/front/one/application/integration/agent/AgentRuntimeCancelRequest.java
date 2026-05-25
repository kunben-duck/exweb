package com.huawei.finance.front.one.application.integration.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Map;

/**
 * SuperAgent 请求 AgentRuntime 取消某个 run 的防腐层契约。
 *
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param sessionId 前端聊天会话标识。
 * @param runId 需要取消的 SuperAgent run 标识。
 * @param runtimeSessionId AgentRuntime 自己的会话标识，可为空。
 * @param provider 当前 Runtime provider 编码。
 * @param reason 取消原因。
 * @param metadata 扩展诊断元数据。
 * @param forwardHeaders stop 请求入口捕获的请求头快照；仅用于可信 Runtime adapter 的出站请求头。
 */
public record AgentRuntimeCancelRequest(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String runtimeSessionId,
        String provider,
        String reason,
        Map<String, Object> metadata,
        @JsonIgnore RuntimeForwardHeaders forwardHeaders
) {
    public AgentRuntimeCancelRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }
}
