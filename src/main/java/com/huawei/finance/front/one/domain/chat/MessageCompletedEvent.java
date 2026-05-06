package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record MessageCompletedEvent(String runId, String sessionId, long sequence, Instant createdAt, Map<String, Object> payload) implements ChatEvent {
    public static MessageCompletedEvent of(String runId, String sessionId) {
        return new MessageCompletedEvent(runId, sessionId, 0, Instant.now(), Map.of("status", "MESSAGE_COMPLETED"));
    }

    public static MessageCompletedEvent of(String runId, String sessionId, String taskStatus) {
        return new MessageCompletedEvent(runId, sessionId, 0, Instant.now(), Map.of("status", "MESSAGE_COMPLETED", "taskStatus", taskStatus));
    }

    public static MessageCompletedEvent of(String runId, String sessionId, Map<String, Object> payload) {
        Map<String, Object> nextPayload = new LinkedHashMap<>();
        nextPayload.put("status", "MESSAGE_COMPLETED");
        if (payload != null) {
            nextPayload.putAll(payload);
        }
        return new MessageCompletedEvent(runId, sessionId, 0, Instant.now(), Map.copyOf(nextPayload));
    }

    @Override public String type() { return "message.completed"; }
}
