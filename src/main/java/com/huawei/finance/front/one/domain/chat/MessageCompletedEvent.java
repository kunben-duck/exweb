package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.Map;

public record MessageCompletedEvent(String runId, String sessionId, long sequence, Instant createdAt, Map<String, Object> payload) implements ChatEvent {
    public static MessageCompletedEvent of(String runId, String sessionId) {
        return new MessageCompletedEvent(runId, sessionId, 0, Instant.now(), Map.of("status", "MESSAGE_COMPLETED"));
    }
    @Override public String type() { return "message.completed"; }
}
