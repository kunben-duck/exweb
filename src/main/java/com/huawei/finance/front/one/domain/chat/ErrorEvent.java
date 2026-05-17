package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.Map;

/**
 * 聊天运行失败事件。
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param sequence 事件持久化后的恢复游标序号，由 openGauss 事实源生成。
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
    @Override public String type() { return "run.failed"; }
}
