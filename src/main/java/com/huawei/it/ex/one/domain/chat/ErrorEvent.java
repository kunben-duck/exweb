package com.huawei.it.ex.one.domain.chat;

import java.time.Instant;
import java.util.Map;

/**
 * 聊天运行失败事件。
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param sequence 事件持久化后的恢复游标序号，由数据库事实源生成。
 * @param createdAt 事件创建时间。
 * @param code 错误码。
 * @param message 错误说明。
 * @param payload 前端可消费的错误载荷。
 */
public record ErrorEvent(
        String runId,
        String sessionId,
        long sequence,
        Instant createdAt,
        String code,
        String message,
        Map<String, Object> payload
) implements ChatEvent {
    public static ErrorEvent of(String runId, String sessionId, String code, String message) {
        return new ErrorEvent(runId, sessionId, 0, Instant.now(), code, message, Map.of("code", code, "message", message));
    }

    /**
     * 创建带自定义 payload 的 run.failed 事件。
     *
     * @param runId 本轮执行追踪标识。
     * @param sessionId 前端聊天会话标识。
     * @param code 错误码。
     * @param message 错误说明。
     * @param payload 前端可消费和审计可检索的失败载荷。
     * @return run.failed 事件。
     */
    public static ErrorEvent of(String runId, String sessionId, String code, String message, Map<String, Object> payload) {
        Map<String, Object> normalized = payload == null || payload.isEmpty()
                ? Map.of("code", code, "message", message)
                : Map.copyOf(payload);
        return new ErrorEvent(runId, sessionId, 0, Instant.now(), code, message, normalized);
    }

    @Override public String type() { return "run.failed"; }
}
