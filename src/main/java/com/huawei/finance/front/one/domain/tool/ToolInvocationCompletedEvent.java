package com.huawei.finance.front.one.domain.tool;

import java.time.Instant;
import java.util.Map;

public record ToolInvocationCompletedEvent(String invocationId, String toolCode, Instant createdAt, Map<String, Object> payload) implements ToolInvocationEvent {
    public static ToolInvocationCompletedEvent of(String invocationId, String toolCode, Map<String, Object> output) { return new ToolInvocationCompletedEvent(invocationId, toolCode, Instant.now(), Map.of("status", "SUCCEEDED", "output", output)); }
    @Override public String type() { return "tool.invocation.completed"; }
}
