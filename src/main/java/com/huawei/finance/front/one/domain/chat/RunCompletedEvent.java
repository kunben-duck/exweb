package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.Map;

public record RunCompletedEvent(String runId, String sessionId, long sequence, Instant createdAt, Map<String, Object> payload) implements ChatEvent {
    public static RunCompletedEvent of(String runId, String sessionId) {
        return new RunCompletedEvent(runId, sessionId, 0, Instant.now(), Map.of("status", "COMPLETED"));
    }
    @Override public String type() { return "run.completed"; }
}
