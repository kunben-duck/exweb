package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.Map;

/**
 * 从事件事实源恢复出来的通用 ChatEvent。
 *
 * <p>运行时产生的事件有具体类型，例如 RunStartedEvent、MessageDeltaEvent。
 * 从 openGauss 查询历史事件时，我们只需要恢复稳定协议字段，不需要反射回原始实现类。</p>
 */
public record StoredChatEvent(
        String runId,
        String sessionId,
        long sequence,
        String eventType,
        Instant createdAt,
        Map<String, Object> payload
) implements ChatEvent {
    @Override
    public String type() {
        return eventType;
    }
}
