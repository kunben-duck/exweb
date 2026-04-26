package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.Map;

public record ErrorEvent(String runId, String sessionId, long sequence, Instant createdAt, String code, String message, Map<String, Object> payload) implements ChatEvent {
    public static ErrorEvent of(String runId, String sessionId, String code, String message) {
        return new ErrorEvent(runId, sessionId, 0, Instant.now(), code, message, Map.of("code", code, "message", message));
    }
    @Override public String type() { return "run.failed"; }
}
