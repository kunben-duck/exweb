package com.huawei.finance.front.one.domain.task;

import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import java.util.List;
import java.util.Map;

/**
 * SuperAgent 下发给自然语言式 SubAgent 的增强任务请求。
 *
 * @param taskId SuperAgent 任务标识。
 * @param runId 本轮执行追踪标识。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param chatSessionId 前端聊天会话标识。
 * @param agentCode 目标 SubAgent 编码。
 * @param agentSessionId 下游 SubAgent 会话标识。
 * @param taskGoal 当前任务目标。
 * @param taskDomain 当前任务领域。
 * @param userQuery 本轮用户输入。
 * @param taskCard 当前任务卡片快照。
 * @param taskPrompt 发给自然语言 Agent 的增强任务说明。
 * @param outputContract 要求 SubAgent 返回的 JSON 契约。
 * @param attachments 用户本轮关联附件。
 * @param memoryContext SuperAgent 装配的上下文快照。
 * @param metadata 扩展元数据。
 */
public record SubAgentTaskRequest(
        String taskId,
        String runId,
        String tenantId,
        String userId,
        String chatSessionId,
        String agentCode,
        String agentSessionId,
        String taskGoal,
        String taskDomain,
        String userQuery,
        TaskCard taskCard,
        String taskPrompt,
        String outputContract,
        List<AttachmentRef> attachments,
        MemoryContext memoryContext,
        Map<String, Object> metadata
) {
    public SubAgentTaskRequest {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
