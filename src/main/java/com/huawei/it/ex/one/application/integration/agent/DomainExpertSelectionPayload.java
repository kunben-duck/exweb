package com.huawei.it.ex.one.application.integration.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** 构造前端固定选择Relay专家的稳定展示载荷。 */
public final class DomainExpertSelectionPayload {
    private DomainExpertSelectionPayload() {
    }

    public static Map<String, Object> create(String roleName, String routeSource,
                                             Map<String, Object> bindingMetadata) {
        String normalizedRoleName = blankToDefault(roleName, "");
        String normalizedRouteSource = blankToDefault(routeSource, "runtime-binding");
        String intentId = text(value(bindingMetadata, "intentCode"));
        String intentName = firstText(value(bindingMetadata, "intentName"), normalizedRoleName);

        Map<String, Object> intentResult = new LinkedHashMap<>();
        intentResult.put("accepted", true);
        intentResult.put("source", normalizedRouteSource);
        intentResult.put("resourceId", normalizedRoleName);
        intentResult.put("skillId", normalizedRoleName);
        putIfPresent(intentResult, "intentId", intentId);
        putIfPresent(intentResult, "intentName", intentName);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "selectedDomainExpert");
        payload.put("metadataType", "selected_domain_expert");
        payload.put("routeType", "AGENT_RUNTIME");
        payload.put("targetType", "DOMAIN_EXPERT");
        payload.put("targetId", normalizedRoleName);
        payload.put("roleName", normalizedRoleName);
        payload.put("routeSource", normalizedRouteSource);
        putIfPresent(payload, "intentId", intentId);
        putIfPresent(payload, "intentName", intentName);
        payload.put("intentResult", Map.copyOf(intentResult));
        return Map.copyOf(payload);
    }

    private static Object value(Map<String, Object> source, String key) {
        return source == null ? null : source.get(key);
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String firstText(Object first, String fallback) {
        String normalized = text(first);
        return normalized == null ? text(fallback) : normalized;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String blankToDefault(String value, String fallback) {
        String normalized = text(value);
        return normalized == null ? fallback : normalized;
    }
}
