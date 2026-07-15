package com.huawei.it.ex.one.domain.chat;

import java.time.Instant;
import java.util.Map;

/**
 * stale run 被其他实例成功接管后的诊断事件。
 *
 * <p>当前默认 Runtime 不支持真实接管时不会产生该事件；它用于未来 {@code RUNTIME_TAKEOVER}
 * 策略成功时通知前端和审计系统本轮 run 发生过执行实例切换。</p>
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param sequence 事件持久化后的恢复游标序号。
 * @param createdAt 事件创建时间。
 * @param payload 接管诊断载荷。
 */
public record RunRecoveredEvent(
        String runId,
        String sessionId,
        long sequence,
        Instant createdAt,
        Map<String, Object> payload
) implements ChatEvent {
    public static RunRecoveredEvent of(String runId, String sessionId, Map<String, Object> payload) {
        return new RunRecoveredEvent(runId, sessionId, 0, Instant.now(), payload == null ? Map.of() : Map.copyOf(payload));
    }

    @Override
    public String type() {
        return "run.recovered";
    }
}
