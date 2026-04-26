package com.huawei.finance.front.one.domain.tool;

import java.time.Instant;
import java.util.Map;

public record ToolInvocationStartedEvent(String invocationId, String toolCode, Instant createdAt, Map<String, Object> payload) implements ToolInvocationEvent {
    public static ToolInvocationStartedEvent of(String invocationId, String toolCode) { return new ToolInvocationStartedEvent(invocationId, toolCode, Instant.now(), Map.of("status", "STARTED")); }
    @Override public String type() { return "tool.invocation.started"; }
}
