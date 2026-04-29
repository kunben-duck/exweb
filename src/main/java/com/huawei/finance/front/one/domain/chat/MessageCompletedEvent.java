package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.Map;

public record MessageCompletedEvent(String runId, String sessionId, long sequence, Instant createdAt, Map<String, Object> payload) implements ChatEvent {
    public static MessageCompletedEvent of(String runId, String sessionId) {
        return new MessageCompletedEvent(runId, sessionId, 0, Instant.now(), Map.of("status", "MESSAGE_COMPLETED"));
    }

    public static MessageCompletedEvent of(String runId, String sessionId, String taskStatus) {
        return new MessageCompletedEvent(runId, sessionId, 0, Instant.now(), Map.of("status", "MESSAGE_COMPLETED", "taskStatus", taskStatus));
    }

    @Override public String type() { return "message.completed"; }
}
