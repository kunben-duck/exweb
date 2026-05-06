package com.huawei.finance.front.one.domain.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SuperAgent 维护的任务状态事实。
 *
 * <p>TaskCard 描述一个可续接业务任务，而不是前端会话本身。一个聊天会话内可以先后产生多个
 * TaskCard，但同一时刻最多只有一个 active task 参与 ContinuationGuard。</p>
 *
 * @param taskId SuperAgent 生成的任务唯一标识。
 * @param tenantId 租户标识，来自应用身份上下文。
 * @param userId 用户标识，来自应用身份上下文。
 * @param chatSessionId 前端聊天会话标识。
 * @param bindingId 路由绑定标识，用于关联 AgentBinding。
 * @param taskGoal 当前任务目标，例如“创建员工报销单”。
 * @param taskDomain 任务领域，例如 employee_reimbursement。
 * @param agentCode 当前任务绑定的 SubAgent 编码。
 * @param agentSessionId 下游 SubAgent 自己的会话标识。
 * @param taskStatus 面向路由决策的任务状态。
 * @param rawNormalizedStatus 响应标准化器识别出的原始状态，UNKNOWN 仅用于诊断。
 * @param requiredInputs 等待用户补充的信息列表。
 * @param collectedSlots 已收集的任务参数。
 * @param lastAgentMessage 最近一次 SubAgent 面向用户的回复。
 * @param confirmationQuestion 状态不明时向用户展示的澄清问题。
 * @param expiresAt 当前任务可作为 active task 续接的过期时间。
 * @param createdAt 创建时间。
 * @param updatedAt 最近更新时间。
 * @param metadata 扩展元数据，保存路由来源、分数等非核心字段。
 */
public record TaskCard(
        String taskId,
        String tenantId,
        String userId,
        String chatSessionId,
        String bindingId,
        String taskGoal,
        String taskDomain,
        String agentCode,
        String agentSessionId,
        TaskStatus taskStatus,
        TaskStatus rawNormalizedStatus,
        List<RequiredInput> requiredInputs,
        Map<String, Object> collectedSlots,
        String lastAgentMessage,
        String confirmationQuestion,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        Map<String, Object> metadata
) {
    public TaskCard {
        taskStatus = taskStatus == null ? TaskStatus.ACTIVE : taskStatus;
        rawNormalizedStatus = rawNormalizedStatus == null ? taskStatus : rawNormalizedStatus;
        requiredInputs = requiredInputs == null ? List.of() : List.copyOf(requiredInputs);
        collectedSlots = collectedSlots == null ? Map.of() : Map.copyOf(collectedSlots);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 判断任务是否可作为当前 active task 参与续接判断。
     *
     * @param now 当前时间。
     * @return true 表示任务可续接。
     */
    public boolean activeAt(Instant now) {
        return taskStatus != null && taskStatus.activeRoutable() && (expiresAt == null || expiresAt.isAfter(now));
    }

    /**
     * 生成更新状态后的任务卡片。
     *
     * @param nextStatus 新的任务状态。
     * @param nextRawStatus 标准化器识别出的原始状态。
     * @param now 更新时间。
     * @return 更新后的任务卡片。
     */
    public TaskCard withStatus(TaskStatus nextStatus, TaskStatus nextRawStatus, Instant now) {
        return new TaskCard(taskId, tenantId, userId, chatSessionId, bindingId, taskGoal, taskDomain, agentCode,
                agentSessionId, nextStatus, nextRawStatus, requiredInputs, collectedSlots, lastAgentMessage,
                confirmationQuestion, expiresAt, createdAt, now, metadata);
    }

    /**
     * 生成应用 SubAgent 响应后的任务卡片。
     *
     * @param result 标准化后的 SubAgent 响应。
     * @param expiresAt 新的续接过期时间。
     * @param now 更新时间。
     * @return 更新后的任务卡片。
     */
    public TaskCard withResult(SubAgentTaskResult result, Instant expiresAt, Instant now) {
        return new TaskCard(taskId, tenantId, userId, chatSessionId, bindingId, taskGoal, taskDomain, agentCode,
                result.agentSessionId() == null || result.agentSessionId().isBlank() ? agentSessionId : result.agentSessionId(),
                result.taskStatus(), result.rawNormalizedStatus(), result.requiredInputs(), collectedSlots,
                result.message(), result.confirmationQuestion(), expiresAt, createdAt, now, metadata);
    }

    /**
     * 生成续期后的任务卡片。
     *
     * @param expiresAt 新的续接过期时间。
     * @param now 更新时间。
     * @return 更新后的任务卡片。
     */
    public TaskCard withExpiry(Instant expiresAt, Instant now) {
        return new TaskCard(taskId, tenantId, userId, chatSessionId, bindingId, taskGoal, taskDomain, agentCode,
                agentSessionId, taskStatus, rawNormalizedStatus, requiredInputs, collectedSlots, lastAgentMessage,
                confirmationQuestion, expiresAt, createdAt, now, metadata);
    }
}
