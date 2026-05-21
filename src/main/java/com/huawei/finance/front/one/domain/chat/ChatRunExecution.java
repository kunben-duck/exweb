package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.Map;

/**
 * ChatRun 的运行控制面快照。
 *
 * <p>该模型只保存实例占有、租约、心跳和恢复治理信息。业务 run 的状态、路由、消息树和事件游标
 * 仍保存在 {@link ChatRun} 与事件事实表中。</p>
 *
 * @param id 执行控制面记录主键。
 * @param runId 关联的业务 runId。
 * @param tenantId 租户标识，用于多租户隔离和排障。
 * @param userId 用户标识，用于用户级隔离和排障。
 * @param sessionId run 所属聊天会话 ID。
 * @param executionStatus 执行控制面状态。
 * @param ownerInstanceId 当前拥有该 run 执行权的服务实例 ID。
 * @param heartbeatAt 当前 owner 最近一次心跳时间。
 * @param leaseUntil 当前 owner 的租约过期时间。
 * @param fencingToken 写事件栅栏令牌；接管或恢复抢占时递增，用于拒绝旧实例迟到输出。
 * @param recoveryStrategy 当前或最近一次使用的 stale run 恢复策略。
 * @param recoveredByInstanceId 最近一次执行恢复动作的实例 ID。
 * @param recoveryAttempts 恢复尝试次数。
 * @param recoveryLeaseUntil RECOVERING 状态的恢复租约过期时间，避免恢复实例再次挂掉后永久卡住。
 * @param runtimeResumeToken Runtime 可靠接管续跑所需的恢复游标或 token；不支持时为空。
 * @param metadata 执行控制面扩展元数据。
 * @param createdAt 控制面记录创建时间。
 * @param updatedAt 控制面记录最后更新时间。
 */
public record ChatRunExecution(
        String id,
        String runId,
        String tenantId,
        String userId,
        String sessionId,
        ChatRunExecutionStatus executionStatus,
        String ownerInstanceId,
        Instant heartbeatAt,
        Instant leaseUntil,
        long fencingToken,
        String recoveryStrategy,
        String recoveredByInstanceId,
        int recoveryAttempts,
        Instant recoveryLeaseUntil,
        String runtimeResumeToken,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public ChatRunExecution {
        executionStatus = executionStatus == null ? ChatRunExecutionStatus.RUNNING : executionStatus;
        fencingToken = Math.max(1L, fencingToken);
        recoveryAttempts = Math.max(0, recoveryAttempts);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * @return 当前 execution 是否已经进入终态。
     */
    public boolean terminal() {
        return executionStatus.terminal();
    }
}
