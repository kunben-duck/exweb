package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单轮聊天 run 被用户停止后的终态事件。
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param sequence 事件在会话事件流中的持久化序号。
 * @param createdAt 事件创建时间。
 * @param payload 前端可消费的取消载荷。
 */
public record RunCancelledEvent(
        String runId,
        String sessionId,
        long sequence,
        Instant createdAt,
        Map<String, Object> payload
) implements ChatEvent {
    public static RunCancelledEvent of(String runId, String sessionId, String reason) {
        return of(runId, sessionId, reason, false, null);
    }

    public static RunCancelledEvent of(String runId, String sessionId, String reason,
                                       boolean messageReady, String assistantMessageId) {
        boolean effectiveMessageReady = messageReady && assistantMessageId != null && !assistantMessageId.isBlank();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "CANCELLED");
        payload.put("reason", reason == null ? "" : reason);
        payload.put("messageReady", effectiveMessageReady);
        if (effectiveMessageReady) {
            payload.put("assistantMessageId", assistantMessageId);
            payload.put("feedbackTargetMessageId", assistantMessageId);
        }
        return new RunCancelledEvent(runId, sessionId, 0, Instant.now(),
                java.util.Collections.unmodifiableMap(payload));
    }

    @Override
    public String type() {
        return "run.cancelled";
    }
}
