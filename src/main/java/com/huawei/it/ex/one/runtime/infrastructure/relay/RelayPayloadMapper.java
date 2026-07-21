package com.huawei.it.ex.one.runtime.infrastructure.relay;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Copies Relay payloads while preserving nullable values and redacting sensitive fields. */
final class RelayPayloadMapper {
    private static final String REDACTED = "[REDACTED]";
    private static final String[] RUNTIME_SESSION_FIELDS = {
            "runtimeSessionId", "runtime_session_id", "session_id", "session-id", "session—id", "sessionId"
    };

    Map<String, Object> relayPayload(JsonNode root, String sourceType) {
        Map<String, Object> payload = new LinkedHashMap<>(sourcePayload(root));
        payload.putIfAbsent("source", "relay");
        payload.putIfAbsent("sourceType", blankToDefault(sourceType, "unknown"));
        String runtimeSessionId = firstText(root, RUNTIME_SESSION_FIELDS);
        if (runtimeSessionId != null) {
            payload.putIfAbsent("runtimeSessionId", runtimeSessionId);
        }
        return payload;
    }

    private Map<String, Object> sourcePayload(JsonNode root) {
        Object sanitized = sanitizeJson(root, "");
        if (sanitized instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return Collections.unmodifiableMap(result);
        }
        return Map.of("value", sanitized);
    }

    private Object sanitizeJson(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (isSensitiveField(fieldName)) {
            return REDACTED;
        }
        if (node.isObject()) {
            return sanitizeObject(node);
        }
        if (node.isArray()) {
            return sanitizeArray(node, fieldName);
        }
        return scalarValue(node);
    }

    private Map<String, Object> sanitizeObject(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            map.put(entry.getKey(), sanitizeJson(entry.getValue(), entry.getKey()));
        }
        return Collections.unmodifiableMap(map);
    }

    private List<Object> sanitizeArray(JsonNode node, String fieldName) {
        List<Object> values = new ArrayList<>();
        for (JsonNode child : node) {
            values.add(sanitizeJson(child, fieldName));
        }
        return Collections.unmodifiableList(values);
    }

    private Object scalarValue(JsonNode node) {
        if (node.isTextual()) {
            return node.asText("");
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText("");
    }

    private boolean isSensitiveField(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        String normalized = fieldName.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("cookie")
                || normalized.contains("authorization")
                || normalized.equals("auth")
                || normalized.endsWith("_auth")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("credential")
                || normalized.contains("api_key")
                || normalized.contains("apikey")
                || normalized.contains("access_key");
    }

    private String firstText(JsonNode root, String... fieldNames) {
        if (root == null || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = root.get(fieldName);
            if (value != null && !value.isNull()) {
                String text = value.asText(null);
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
