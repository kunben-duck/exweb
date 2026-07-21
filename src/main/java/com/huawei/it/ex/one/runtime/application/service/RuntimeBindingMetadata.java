package com.huawei.it.ex.one.runtime.application.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Package-local metadata construction shared by RuntimeBinding lifecycle operations. */
final class RuntimeBindingMetadata {
    private RuntimeBindingMetadata() {
    }

    static Map<String, Object> domainAgent(
            String domainAgentId,
            String routeSource,
            Map<String, Object> intentMetadata,
            Map<String, Object> previousMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (previousMetadata != null) {
            metadata.putAll(previousMetadata);
        }
        metadata.put("domainAgentId", domainAgentId);
        metadata.put("routeSource", blankToDefault(routeSource, "intent"));
        if (intentMetadata != null && !intentMetadata.isEmpty()) {
            metadata.putAll(intentMetadata);
        }
        return Map.copyOf(metadata);
    }

    static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
