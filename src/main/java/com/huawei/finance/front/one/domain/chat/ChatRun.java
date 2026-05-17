package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.Map;

/**
 * 单轮聊天 run 的事实源快照。
 *
 * <p>ChatRun 描述一次用户提问从开始、流式输出、取消/完成/失败到结束的生命周期。
 * 它不是多轮会话；多轮上下文仍由 ChatSession 与 RuntimeBinding 管理。</p>
 *
 * @param id 本轮 run 标识。
 * @param tenantId 租户标识，来自服务端身份上下文。
 * @param userId 用户标识，来自服务端身份上下文。
 * @param sessionId 前端聊天会话标识。
 * @param status run 生命周期状态。
 * @param routeType 本轮路由类型，例如 SUB_AGENT、AGENT_RUNTIME、SYSTEM_RESPONSE。
 * @param agentCode 本轮命中的 SubAgent 编码，可为空。
 * @param runtimeProvider 本轮使用的 AgentRuntime provider，可为空。
 * @param runtimeSessionId AgentRuntime 自己的会话标识，可为空。
 * @param firstSeq run.started 持久化后的事件序号。
 * @param lastSeq 该 run 当前最后一个已持久化事件序号。
 * @param cancelReason stop 接口传入或系统生成的取消原因。
 * @param startedAt run 开始执行时间。
 * @param finishedAt run 进入终态时间。
 * @param metadata run 扩展诊断元数据。
 * @param createdAt 记录创建时间。
 * @param updatedAt 记录最后更新时间。
 */
public record ChatRun(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        ChatRunStatus status,
        String routeType,
        String agentCode,
        String runtimeProvider,
        String runtimeSessionId,
        Long firstSeq,
        Long lastSeq,
        String cancelReason,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public ChatRun {
        status = status == null ? ChatRunStatus.RUNNING : status;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * @return 当前 run 是否仍可被 stop 接口取消。
     */
    public boolean cancellable() {
        return status.cancellable();
    }

    /**
     * 记录 run.started 的持久化序号。
     */
    public ChatRun withFirstSeq(long sequence) {
        return new ChatRun(id, tenantId, userId, sessionId, status, routeType, agentCode, runtimeProvider,
                runtimeSessionId, firstSeq == null ? sequence : firstSeq, sequence, cancelReason, startedAt,
                finishedAt, metadata, createdAt, Instant.now());
    }

    /**
     * 记录非终态事件的最后序号。
     */
    public ChatRun withLastSeq(long sequence) {
        return new ChatRun(id, tenantId, userId, sessionId, status, routeType, agentCode, runtimeProvider,
                runtimeSessionId, firstSeq, sequence, cancelReason, startedAt, finishedAt, metadata, createdAt,
                Instant.now());
    }

    /**
     * 保存 Runtime 返回的内部会话标识。
     */
    public ChatRun withRuntimeSessionId(String nextRuntimeSessionId) {
        return new ChatRun(id, tenantId, userId, sessionId, status, routeType, agentCode, runtimeProvider,
                nextRuntimeSessionId, firstSeq, lastSeq, cancelReason, startedAt, finishedAt, metadata, createdAt,
                Instant.now());
    }

    /**
     * 标记 stop 已被接受。
     */
    public ChatRun cancelling(String reason) {
        return new ChatRun(id, tenantId, userId, sessionId, ChatRunStatus.CANCELLING, routeType, agentCode,
                runtimeProvider, runtimeSessionId, firstSeq, lastSeq, reason, startedAt, finishedAt, metadata,
                createdAt, Instant.now());
    }

    /**
     * 标记 run 已取消。
     */
    public ChatRun cancelled(long sequence) {
        Instant now = Instant.now();
        return new ChatRun(id, tenantId, userId, sessionId, ChatRunStatus.CANCELLED, routeType, agentCode,
                runtimeProvider, runtimeSessionId, firstSeq, sequence, cancelReason, startedAt, now, metadata,
                createdAt, now);
    }

    /**
     * 标记 run 已完成。
     */
    public ChatRun completed(long sequence) {
        Instant now = Instant.now();
        return new ChatRun(id, tenantId, userId, sessionId, ChatRunStatus.COMPLETED, routeType, agentCode,
                runtimeProvider, runtimeSessionId, firstSeq, sequence, cancelReason, startedAt, now, metadata,
                createdAt, now);
    }

    /**
     * 标记 run 已失败。
     */
    public ChatRun failed(long sequence) {
        Instant now = Instant.now();
        return new ChatRun(id, tenantId, userId, sessionId, ChatRunStatus.FAILED, routeType, agentCode,
                runtimeProvider, runtimeSessionId, firstSeq, sequence, cancelReason, startedAt, now, metadata,
                createdAt, now);
    }
}
