package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.Map;

/**
 * 单轮 run 等待用户澄清、审批或确认输入的终态事件。
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param sequence 事件持久化后的恢复游标序号，由数据库事实源生成。
 * @param createdAt 事件创建时间。
 * @param payload 等待用户输入载荷，包含 interactionId、interactionType 和 assistantMessageId。
 */
public record RunWaitingUserEvent(
        String runId,
        String sessionId,
        long sequence,
        Instant createdAt,
        Map<String, Object> payload
) implements ChatEvent {
    public static RunWaitingUserEvent of(String runId, String sessionId, Map<String, Object> payload) {
        return new RunWaitingUserEvent(runId, sessionId, 0, Instant.now(),
                payload == null ? Map.of("status", "WAITING_USER") : Map.copyOf(payload));
    }

    @Override
    public String type() {
        return "run.waiting_user";
    }
}
