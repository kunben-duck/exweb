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
 * @param leafMessageId 该 Runtime 内部会话对应的前端消息树叶子，避免历史编辑后复用错误上下文。
 * @param runtimeSessionId AgentRuntime 实际会话标识；Relay 首次调用前以 ChatService sessionId 兜底，
 *                         收到 session-ready 后以 Relay 返回值为准。
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
        String leafMessageId,
        String runtimeSessionId,
        RuntimeBindingStatus status,
        String lastRunId,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        Map<String, Object> metadata
) {
    /**
     * 创建根路径 RuntimeBinding。
     *
     * <p>新会话第一次提问时还没有前置消息树 leaf，因此允许 leaf 为空；run 完成后绑定会移动到
     * 新生成的 assistant leaf。</p>
     */
    public RuntimeBinding(String id, String tenantId, String userId, String chatSessionId, String provider,
                          String runtimeSessionId, RuntimeBindingStatus status, String lastRunId,
                          Instant expiresAt, Instant createdAt, Instant updatedAt, Map<String, Object> metadata) {
        this(id, tenantId, userId, chatSessionId, provider, null, runtimeSessionId, status,
                lastRunId, expiresAt, createdAt, updatedAt, metadata);
    }

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
        return new RuntimeBinding(id, tenantId, userId, chatSessionId, provider, leafMessageId, runtimeSessionId,
                RuntimeBindingStatus.ACTIVE, runId, expiresAt, createdAt, Instant.now(), metadata);
    }

    /**
     * 将绑定移动到新的前端消息树叶子。
     *
     * <p>Runtime 完整返回 assistant 消息后，下一轮普通继续提问应沿新 assistant 叶子续接，
     * 因此需要把绑定从本轮 parent leaf 移动到新 assistant leaf。</p>
     *
     * @param nextLeafMessageId 新的 active path 叶子消息。
     * @return 更新后的绑定。
     */
    public RuntimeBinding withLeafMessageId(String nextLeafMessageId) {
        return new RuntimeBinding(id, tenantId, userId, chatSessionId, provider, nextLeafMessageId,
                runtimeSessionId, status, lastRunId, expiresAt, createdAt, Instant.now(), metadata);
    }

    /**
     * 更新绑定状态。
     *
     * @param nextStatus 新状态。
     * @return 更新后的绑定。
     */
    public RuntimeBinding withStatus(RuntimeBindingStatus nextStatus) {
        return new RuntimeBinding(id, tenantId, userId, chatSessionId, provider, leafMessageId, runtimeSessionId,
                nextStatus, lastRunId, expiresAt, createdAt, Instant.now(), metadata);
    }

    /**
     * 保存 AgentRuntime 确认的实际会话标识。
     *
     * @param nextRuntimeSessionId Runtime 内部会话 ID。
     * @return 更新后的绑定。
     */
    public RuntimeBinding withRuntimeSessionId(String nextRuntimeSessionId) {
        return new RuntimeBinding(id, tenantId, userId, chatSessionId, provider, leafMessageId, nextRuntimeSessionId,
                status, lastRunId, expiresAt, createdAt, Instant.now(), metadata);
    }
}
