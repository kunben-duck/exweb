package com.huawei.finance.front.one.infrastructure.runtime.relay;

import java.util.Map;

/**
 * Relay Runtime stop/cancel 接口的出站 wire DTO。
 *
 * <p>取消请求同样不能直接复用 AgentRuntimeCancelRequest，避免把 provider、租户用户身份、
 * forwardHeaders 等 ChatService 内部字段序列化给下游。</p>
 *
 * @param runId 需要取消的 ChatService run 标识。
 * @param sessionId run 所属前端会话标识。
 * @param runtimeSessionId Relay 确认的实际会话标识，可为空。
 * @param reason 取消原因。
 * @param metadata 允许透传给 Relay 的非敏感扩展信息。
 */
public record RelayRuntimeCancelWireRequest(
        String runId,
        String sessionId,
        String runtimeSessionId,
        String reason,
        Map<String, Object> metadata
) {
    public RelayRuntimeCancelWireRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
