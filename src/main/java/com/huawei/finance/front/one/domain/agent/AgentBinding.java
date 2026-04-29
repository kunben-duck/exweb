package com.huawei.finance.front.one.domain.agent;

import java.time.Instant;
import java.util.Map;

/**
 * SuperAgent 侧维护的多轮路由绑定。
 *
 * <p>它不是下游 Agent 的完整会话模型，只保存“前端 chatSession 应继续路由到哪里”的最小事实：
 * SubAgent/AgentRuntime 类型、下游会话 ID、状态、过期时间和少量扩展元数据。</p>
 */
public record AgentBinding(
        String id,
        String tenantId,
        String userId,
        String chatSessionId,
        AgentBindingType bindingType,
        String agentCode,
        String provider,
        String agentSessionId,
        String runtimeSessionId,
        AgentBindingStatus status,
        String lastRunId,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        Map<String, Object> metadata
) {
    public AgentBinding {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean routableAt(Instant now) {
        // 只有非终态且未过期的 binding 才能截断正常路由流程，直接续接下游 Agent。
        return status != null && status.routable() && (expiresAt == null || expiresAt.isAfter(now));
    }

    public AgentBinding withRun(String runId, Instant expiresAt) {
        // 新一轮用户输入到来时刷新 lastRunId 和 expiresAt，表示该 binding 仍处于活跃窗口。
        return new AgentBinding(id, tenantId, userId, chatSessionId, bindingType, agentCode, provider, agentSessionId,
                runtimeSessionId, AgentBindingStatus.ACTIVE, runId, expiresAt, createdAt, Instant.now(), metadata);
    }

    public AgentBinding withStatus(AgentBindingStatus nextStatus) {
        return new AgentBinding(id, tenantId, userId, chatSessionId, bindingType, agentCode, provider, agentSessionId,
                runtimeSessionId, nextStatus, lastRunId, expiresAt, createdAt, Instant.now(), metadata);
    }

    public AgentBinding withAgentSessionId(String nextAgentSessionId) {
        return new AgentBinding(id, tenantId, userId, chatSessionId, bindingType, agentCode, provider, nextAgentSessionId,
                runtimeSessionId, status, lastRunId, expiresAt, createdAt, Instant.now(), metadata);
    }

    public AgentBinding withRuntimeSessionId(String nextRuntimeSessionId) {
        return new AgentBinding(id, tenantId, userId, chatSessionId, bindingType, agentCode, provider, agentSessionId,
                nextRuntimeSessionId, status, lastRunId, expiresAt, createdAt, Instant.now(), metadata);
    }
}
