package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.Map;

public record MessageDeltaEvent(String runId, String sessionId, long sequence, Instant createdAt, String delta, Map<String, Object> payload) implements ChatEvent {
    public static MessageDeltaEvent of(String runId, String sessionId, String delta) {
        return new MessageDeltaEvent(runId, sessionId, 0, Instant.now(), delta, Map.of("delta", delta));
    }
    @Override public String type() { return "message.delta"; }
}
