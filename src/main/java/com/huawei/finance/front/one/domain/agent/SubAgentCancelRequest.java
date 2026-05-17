package com.huawei.finance.front.one.domain.agent;

import java.util.Map;

/**
 * SuperAgent 请求第三方 SubAgent 取消某个 run 的防腐层契约。
 *
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param sessionId 前端聊天会话标识。
 * @param runId 需要取消的 SuperAgent run 标识。
 * @param agentCode 目标 SubAgent 编码。
 * @param reason 取消原因。
 * @param metadata 扩展诊断元数据。
 */
public record SubAgentCancelRequest(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String agentCode,
        String reason,
        Map<String, Object> metadata
) {
    public SubAgentCancelRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
