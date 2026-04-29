package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.Map;

public record RunCompletedEvent(String runId, String sessionId, long sequence, Instant createdAt, Map<String, Object> payload) implements ChatEvent {
    public static RunCompletedEvent of(String runId, String sessionId) {
        return new RunCompletedEvent(runId, sessionId, 0, Instant.now(), Map.of("status", "COMPLETED"));
    }

    public static RunCompletedEvent of(String runId, String sessionId, Map<String, Object> payload) {
        return new RunCompletedEvent(runId, sessionId, 0, Instant.now(), payload == null ? Map.of("status", "COMPLETED") : Map.copyOf(payload));
    }

    @Override public String type() { return "run.completed"; }
}
