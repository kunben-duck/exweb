package com.huawei.finance.front.one.domain.tool;

import java.time.Instant;
import java.util.Map;

public record ToolInvocationFailedEvent(String invocationId, String toolCode, Instant createdAt, Map<String, Object> payload) implements ToolInvocationEvent {
    public static ToolInvocationFailedEvent of(String invocationId, String toolCode, String message) { return new ToolInvocationFailedEvent(invocationId, toolCode, Instant.now(), Map.of("status", "FAILED", "message", message)); }
    @Override public String type() { return "tool.invocation.failed"; }
}
