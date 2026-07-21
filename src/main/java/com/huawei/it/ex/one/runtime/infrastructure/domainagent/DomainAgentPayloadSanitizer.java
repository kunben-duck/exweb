package com.huawei.it.ex.one.runtime.infrastructure.domainagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.it.ex.one.common.event.ChatPayloadMaps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DomainAgentPayloadSanitizer {
    Object sanitizeBusiness(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return sanitizeBusinessObject(node);
        }
        if (node.isArray()) {
            return sanitizeBusinessArray(node);
        }
        return scalarValue(node, false);
    }

    Object sanitizeDiagnostic(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return sanitizeDiagnosticObject(node);
        }
        if (node.isArray()) {
            return sanitizeDiagnosticArray(node);
        }
        return scalarValue(node, true);
    }

    private Map<String, Object> sanitizeBusinessObject(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            Object value = sanitizeBusiness(entry.getKey(), entry.getValue());
            if (value != null) {
                map.put(entry.getKey(), value);
            }
        });
        return ChatPayloadMaps.immutableCopy(map);
    }

    private List<Object> sanitizeBusinessArray(JsonNode node) {
        List<Object> list = new ArrayList<>();
        for (JsonNode child : node) {
            Object value = sanitizeBusiness(child);
            if (value != null) {
                list.add(value);
            }
        }
        return List.copyOf(list);
    }

    private Map<String, Object> sanitizeDiagnosticObject(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            Object value = isSensitiveField(entry.getKey())
                    ? "[REDACTED]"
                    : sanitizeDiagnostic(entry.getValue());
            if (value != null) {
                map.put(entry.getKey(), value);
            }
        });
        return ChatPayloadMaps.immutableCopy(map);
    }

    private List<Object> sanitizeDiagnosticArray(JsonNode node) {
        List<Object> list = new ArrayList<>();
        int count = 0;
        for (JsonNode child : node) {
            if (count++ >= 50) {
                list.add("[TRUNCATED]");
                break;
            }
            Object value = sanitizeDiagnostic(child);
            if (value != null) {
                list.add(value);
            }
        }
        return List.copyOf(list);
    }

    private Object scalarValue(JsonNode node, boolean truncateText) {
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        String text = node.asText("");
        return truncateText ? truncate(text) : text;
    }

    String truncate(String value) {
        if (value == null || value.length() <= 2048) {
            return value;
        }
        return value.substring(0, 2048);
    }

    private Object sanitizeBusiness(String field, JsonNode node) {
        if (isSensitiveField(field)) {
            return "[REDACTED]";
        }
        return sanitizeBusiness(node);
    }

    private boolean isSensitiveField(String field) {
        if (field == null) {
            return false;
        }
        String normalized = field.toLowerCase(Locale.ROOT);
        return normalized.contains("cookie") || normalized.contains("authorization") || normalized.contains("token")
                || normalized.contains("secret") || normalized.contains("password")
                || normalized.contains("credential") || normalized.contains("apikey")
                || normalized.contains("api_key") || normalized.contains("access_key");
    }
}
