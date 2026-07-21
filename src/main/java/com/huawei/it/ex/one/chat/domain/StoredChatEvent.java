package com.huawei.it.ex.one.chat.domain;

import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.common.event.ChatPayloadMaps;
import java.time.Instant;
import java.util.Map;

/**
 * 从事件事实源恢复出来的通用 ChatEvent。
 *
 * <p>运行时产生的事件有具体类型，例如 RunStartedEvent、MessageDeltaEvent。
 * 从数据库查询历史事件时，我们只需要恢复稳定协议字段，不需要反射回原始实现类。</p>
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param sequence 事件持久化后的恢复游标序号，由数据库事实源生成。
 * @param eventType 持久化事件类型。
 * @param createdAt 事件创建时间。
 * @param payload 事件载荷。
 */
public record StoredChatEvent(
        String runId,
        String sessionId,
        long sequence,
        String eventType,
        Instant createdAt,
        Map<String, Object> payload
) implements ChatEvent {
    public StoredChatEvent {
        payload = ChatPayloadMaps.immutableCopy(payload);
    }

    @Override
    public String type() {
        return eventType;
    }
}
