package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeSessionMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将 application 层 AgentRuntime port 请求映射为 Relay 下游协议请求。
 *
 * <p>该 mapper 是 Relay adapter 的防腐边界：只有经过明确 allowlist 的字段会进入下游请求体。
 * Cookie 由 adapter 设置在请求头里，不能通过本 mapper 进入 body。</p>
 */
final class RelayRuntimeWireRequestMapper {
    private RelayRuntimeWireRequestMapper() {
    }

    static RelayRuntimeQueryRequest toQueryWireRequest(AgentRuntimeRequest request) {
        return new RelayRuntimeQueryRequest(
                request.runId(),
                request.sessionId(),
                request.runtimeSessionMode() == RuntimeSessionMode.NEW ? null : request.runtimeSessionId(),
                request.message(),
                request.attachments(),
                sanitizedMetadata(request.metadata())
        );
    }

    static RelayRuntimeCancelWireRequest toCancelWireRequest(AgentRuntimeCancelRequest request) {
        return new RelayRuntimeCancelWireRequest(
                request.runId(),
                request.sessionId(),
                request.runtimeSessionId(),
                request.reason(),
                sanitizedMetadata(request.metadata())
        );
    }

    private static Map<String, Object> sanitizedMetadata(Map<String, Object> metadata) {
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
        String normalized = key.trim().toLowerCase();
        return normalized.contains("cookie")
                || normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("secret")
                || normalized.contains("password");
    }
}
