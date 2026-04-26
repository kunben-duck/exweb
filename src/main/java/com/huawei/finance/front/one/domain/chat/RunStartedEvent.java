package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.Map;

public record RunStartedEvent(String runId, String sessionId, long sequence, Instant createdAt, Map<String, Object> payload) implements ChatEvent {
    public static RunStartedEvent of(String runId, String sessionId) {
        return new RunStartedEvent(runId, sessionId, 0, Instant.now(), Map.of("status", "STARTED"));
    }
    @Override public String type() { return "run.started"; }
}
