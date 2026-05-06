package com.huawei.finance.front.one.domain.task;

import java.time.Instant;
import java.util.Map;

/**
 * 任务状态变化事件。
 *
 * @param id 事件唯一标识。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param chatSessionId 前端聊天会话标识。
 * @param taskId SuperAgent 任务标识。
 * @param runId 本轮执行追踪标识。
 * @param eventType 事件类型，例如 TASK_CREATED、TASK_STATUS_CHANGED。
 * @param fromStatus 变化前状态。
 * @param toStatus 变化后状态。
 * @param payload 事件附加信息。
 * @param createdAt 事件创建时间。
 */
public record TaskEvent(
        String id,
        String tenantId,
        String userId,
        String chatSessionId,
        String taskId,
        String runId,
        String eventType,
        TaskStatus fromStatus,
        TaskStatus toStatus,
        Map<String, Object> payload,
        Instant createdAt
) {
    public TaskEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
