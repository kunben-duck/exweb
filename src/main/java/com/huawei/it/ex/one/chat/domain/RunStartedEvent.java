package com.huawei.it.ex.one.chat.domain;

import com.huawei.it.ex.one.common.event.ChatEvent;
import java.time.Instant;
import java.util.Map;

/**
 * 单轮 SuperAgent 执行开始事件。
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param sequence 事件持久化后的恢复游标序号，由数据库事实源生成。
 * @param createdAt 事件创建时间。
 * @param payload 开始事件载荷。
 */
public record RunStartedEvent(
        String runId,
        String sessionId,
        long sequence,
        Instant createdAt,
        Map<String, Object> payload
) implements ChatEvent {
    public static RunStartedEvent of(String runId, String sessionId) {
        return new RunStartedEvent(runId, sessionId, 0, Instant.now(), Map.of("status", "STARTED"));
    }
    @Override public String type() { return "run.started"; }
}
