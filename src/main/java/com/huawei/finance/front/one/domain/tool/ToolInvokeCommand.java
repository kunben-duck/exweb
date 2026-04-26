package com.huawei.finance.front.one.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public record ToolInvokeCommand(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String toolCode,
        String idempotencyKey,
        JsonNode arguments,
        boolean confirmed,
        String channel,
        Map<String, Object> metadata
) {}
