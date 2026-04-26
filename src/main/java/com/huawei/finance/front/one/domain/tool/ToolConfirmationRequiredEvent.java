package com.huawei.finance.front.one.domain.tool;

import java.time.Instant;
import java.util.Map;

public record ToolConfirmationRequiredEvent(String invocationId, String toolCode, Instant createdAt, Map<String, Object> payload) implements ToolInvocationEvent {
    public static ToolConfirmationRequiredEvent of(String invocationId, String toolCode, String message) { return new ToolConfirmationRequiredEvent(invocationId, toolCode, Instant.now(), Map.of("status", "CONFIRMATION_REQUIRED", "message", message)); }
    @Override public String type() { return "tool.confirmation.required"; }
}
