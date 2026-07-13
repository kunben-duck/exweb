package com.huawei.finance.front.one.infrastructure.runtime.relay;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Relay WebSocket metadata 防腐边界。
 *
 * <p>只有经过明确过滤的 metadata 字段会进入下游请求体。
 * Cookie 由 adapter 设置在请求头里，不能通过本 mapper 进入 body。</p>
 */
final class RelayRuntimeWireRequestMapper {
    private RelayRuntimeWireRequestMapper() {
    }

    static Map<String, Object> sanitizedMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key != null && value != null && !isSensitiveKey(key)) {
                sanitized.put(key, sanitizeValue(value));
            }
        });
        return Map.copyOf(sanitized);
    }

    static Map<String, Object> relayMetadata(Map<String, Object> metadata, String userAccount, Long globalUserId) {
        Map<String, Object> sanitized = new LinkedHashMap<>(sanitizedMetadata(metadata));
        if (userAccount != null && !userAccount.isBlank()) {
            sanitized.put("userAccount", userAccount.trim());
        }
        if (globalUserId != null) {
            sanitized.put("globalUserId", globalUserId);
        }
        return Map.copyOf(sanitized);
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                if (key != null && nestedValue != null && !isSensitiveKey(String.valueOf(key))) {
                    nested.put(String.valueOf(key), sanitizeValue(nestedValue));
                }
            });
            return Map.copyOf(nested);
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(RelayRuntimeWireRequestMapper::sanitizeValue)
                    .toList();
        }
        return value;
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("cookie")
                || normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("secret")
                || normalized.contains("password");
    }
}
