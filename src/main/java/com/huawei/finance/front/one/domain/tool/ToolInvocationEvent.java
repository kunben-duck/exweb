package com.huawei.finance.front.one.domain.tool;

import java.time.Instant;
import java.util.Map;

public interface ToolInvocationEvent {
    String invocationId();
    String toolCode();
    String type();
    Instant createdAt();
    Map<String, Object> payload();
}
