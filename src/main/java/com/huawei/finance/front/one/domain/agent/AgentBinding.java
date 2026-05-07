package com.huawei.finance.front.one.domain.agent;

import java.time.Instant;
import java.util.Map;

/**
 * SuperAgent 侧维护的多轮路由绑定。
 *
 * <p>它不是下游 Agent 的完整会话模型，只保存“前端 chatSession 应继续路由到哪里”的最小事实：
 * SubAgent/AgentRuntime 类型、下游会话 ID、状态、过期时间和少量扩展元数据。</p>
 *
 * @param id 路由绑定唯一标识。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param chatSessionId 前端聊天会话标识。
 * @param bindingType 绑定目标类型，区分 SubAgent 与 AgentRuntime。
 * @param agentCode SubAgent 编码，bindingType 为 SUB_AGENT 时有效。
 * @param provider AgentRuntime provider 编码，bindingType 为 AGENT_RUNTIME 时有效。
 * @param agentSessionId 下游 SubAgent 自己的会话标识。
 * @param runtimeSessionId AgentRuntime 自己的会话标识。
 * @param status 当前路由绑定状态。
 * @param lastRunId 最近一次触发该 binding 的 SuperAgent runId。
 * @param expiresAt 该 binding 可作为 active route 的过期时间。
 * @param createdAt 创建时间。
 * @param updatedAt 最近更新时间。
 * @param metadata 扩展元数据，保存路由来源、诊断信息等非核心字段。
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
        // 只有可路由且未过期的 binding 才能进入 active 分支；SubAgent 仍需再经过 TaskCard 续接判断。
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
