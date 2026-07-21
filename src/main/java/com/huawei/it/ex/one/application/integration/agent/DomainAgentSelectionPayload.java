package com.huawei.it.ex.one.application.integration.agent;

import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 构造 DomainAgent 已选中事件的稳定展示载荷。
 */
public final class DomainAgentSelectionPayload {
    private DomainAgentSelectionPayload() {
    }

    public static Map<String, Object> create(String domainAgentId, String routeSource, String runtimeSessionId,
                                             IntentDecision intent, Map<String, Object> bindingMetadata) {
        String intentId = firstText(
                intent == null || intent.slots() == null ? null : intent.slots().get("intentId"),
                intent == null ? null : intent.intentCode(),
                value(bindingMetadata, "intentCode"));
        String intentName = firstText(
                intent == null ? null : intent.intentName(),
                value(bindingMetadata, "intentName"));

        Map<String, Object> intentResult = new LinkedHashMap<>();
        intentResult.put("accepted", true);
        intentResult.put("source", blankToDefault(routeSource, "runtime-binding"));
        intentResult.put("resourceId", blankToDefault(domainAgentId, ""));
        intentResult.put("skillId", blankToDefault(domainAgentId, ""));
        putIfPresent(intentResult, "intentId", intentId);
        putIfPresent(intentResult, "intentName", intentName);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "selectedDomainAgent");
        payload.put("metadataType", "selected_domain_agent");
        payload.put("routeType", "DOMAIN_AGENT");
        payload.put("targetType", "DOMAIN_AGENT");
        payload.put("targetId", blankToDefault(domainAgentId, ""));
        payload.put("domainAgentId", blankToDefault(domainAgentId, ""));
        payload.put("routeSource", blankToDefault(routeSource, "runtime-binding"));
        payload.put("runtimeSessionId", blankToDefault(runtimeSessionId, ""));
        putIfPresent(payload, "intentId", intentId);
        putIfPresent(payload, "intentName", intentName);
        AgentModeProfile agentMode = AgentModeBindingContext.fromMetadata(bindingMetadata);
        if (agentMode != null) {
            payload.put("agentMode", AgentModeBindingContext.toPayload(agentMode));
        }
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

    private static String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
