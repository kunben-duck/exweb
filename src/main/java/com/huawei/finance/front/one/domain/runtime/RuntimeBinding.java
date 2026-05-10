package com.huawei.finance.front.one.domain.runtime;

import java.time.Instant;
import java.util.Map;

/**
 * SuperAgent 侧维护的 AgentRuntime 会话绑定。
 *
 * <p>RuntimeBinding 只服务复杂任务多轮会话。简单任务命中 SubAgent 后只执行一轮，不创建绑定、
 * 不续接下游会话，也不进入任务状态机。</p>
 *
 * @param id 绑定唯一标识。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param chatSessionId 前端聊天会话标识。
 * @param provider 当前装配的 AgentRuntime provider 编码。
 * @param runtimeSessionId AgentRuntime 返回的内部会话标识，首次调用可为空。
 * @param status 绑定状态，只用于判断是否继续路由到当前 AgentRuntime。
 * @param lastRunId 最近一次触发该绑定的 SuperAgent runId。
 * @param expiresAt 绑定可作为 active runtime route 的过期时间。
 * @param createdAt 创建时间。
 * @param updatedAt 最近更新时间。
 * @param metadata 扩展诊断信息。
 */
public record RuntimeBinding(
        String id,
        String tenantId,
        String userId,
        String chatSessionId,
        String provider,
        String runtimeSessionId,
        RuntimeBindingStatus status,
        String lastRunId,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        Map<String, Object> metadata
) {
    public RuntimeBinding {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 判断该绑定当前是否仍可用于多轮续接。
     *
     * @param now 当前时间。
     * @return true 表示本轮请求应继续进入当前 AgentRuntime。
     */
    public boolean routableAt(Instant now) {
        return status != null && status.routable() && (expiresAt == null || expiresAt.isAfter(now));
    }

    /**
     * 刷新本轮运行标识和过期时间。
     *
     * @param runId 本轮 SuperAgent runId。
     * @param expiresAt 新过期时间。
     * @return 更新后的绑定。
     */
    public RuntimeBinding withRun(String runId, Instant expiresAt) {
        return new RuntimeBinding(id, tenantId, userId, chatSessionId, provider, runtimeSessionId,
                RuntimeBindingStatus.ACTIVE, runId, expiresAt, createdAt, Instant.now(), metadata);
    }

    /**
     * 更新绑定状态。
     *
     * @param nextStatus 新状态。
     * @return 更新后的绑定。
     */
    public RuntimeBinding withStatus(RuntimeBindingStatus nextStatus) {
        return new RuntimeBinding(id, tenantId, userId, chatSessionId, provider, runtimeSessionId,
                nextStatus, lastRunId, expiresAt, createdAt, Instant.now(), metadata);
    }

    /**
     * 保存 AgentRuntime 返回的内部会话标识。
     *
     * @param nextRuntimeSessionId Runtime 内部会话 ID。
     * @return 更新后的绑定。
     */
    public RuntimeBinding withRuntimeSessionId(String nextRuntimeSessionId) {
        return new RuntimeBinding(id, tenantId, userId, chatSessionId, provider, nextRuntimeSessionId,
                status, lastRunId, expiresAt, createdAt, Instant.now(), metadata);
    }
}
