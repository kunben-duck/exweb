package com.huawei.finance.front.one.interfaces.tool.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public record ToolInvokeRequest(String sessionId, String runId, String idempotencyKey, JsonNode arguments, boolean confirmed, Map<String, Object> metadata) {}
