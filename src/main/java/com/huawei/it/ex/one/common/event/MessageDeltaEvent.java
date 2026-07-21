package com.huawei.it.ex.one.common.event;

import java.time.Instant;
import java.util.Map;

/**
 * assistant 消息增量事件。
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param sequence 事件持久化后的恢复游标序号，由数据库事实源生成。
 * @param createdAt 事件创建时间。
 * @param delta 本次增量文本。
 * @param payload 前端事件载荷，至少包含 delta 字段。
 */
public record MessageDeltaEvent(
        String runId,
        String sessionId,
        long sequence,
        Instant createdAt,
        String delta,
        Map<String, Object> payload
) implements ChatEvent {
    public MessageDeltaEvent {
        payload = ChatPayloadMaps.immutableCopy(payload);
    }

    public static MessageDeltaEvent of(String runId, String sessionId, String delta) {
        return new MessageDeltaEvent(runId, sessionId, 0, Instant.now(), delta, Map.of("delta", delta));
    }
    @Override public String type() { return "message.delta"; }
}
