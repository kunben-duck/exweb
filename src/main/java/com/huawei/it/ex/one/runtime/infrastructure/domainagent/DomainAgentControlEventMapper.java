package com.huawei.it.ex.one.runtime.infrastructure.domainagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.it.ex.one.common.event.ChatPayloadMaps;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentControlEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * DomainAgent 控制事件防腐层。
 *
 * <p>下游控制协议只能在这里转换为 ChatService 的稳定语义。应用编排层不直接猜测
 * {@code reasonCode}、正文或旧错误码，新增控制编码时只扩展本映射器。</p>
 */
public final class DomainAgentControlEventMapper {
    public static final String REFUSAL_TYPE = DomainAgentControlEvent.REFUSAL_TYPE;
    public static final String OUT_OF_DOMAIN_CODE = DomainAgentControlEvent.OUT_OF_DOMAIN_CODE;
    public static final String METADATA_TYPE = DomainAgentControlEvent.METADATA_TYPE;
    public static final String ACTION_REROUTE = DomainAgentControlEvent.ACTION_REROUTE;
    public static final String ACTION_UNHANDLED = DomainAgentControlEvent.ACTION_UNHANDLED;

    private static final List<String> EXPOSED_FIELDS = List.of(
            "type", "code", "agentId", "agentName", "eventId", "traceId", "timestamp", "schemaVersion",
            "reasonCode", "recoverable", "reason", "userMessage", "metadata"
    );

    public Optional<DomainAgentControlEvent> map(JsonNode root) {
        if (root == null || !root.isObject() || !REFUSAL_TYPE.equals(text(root.get("type")))) {
            return Optional.empty();
        }
        String code = text(root.get("code"));
        String supervisorAction = OUT_OF_DOMAIN_CODE.equals(code) ? ACTION_REROUTE : ACTION_UNHANDLED;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", REFUSAL_TYPE);
        payload.put("metadataType", METADATA_TYPE);
        payload.put("supervisorAction", supervisorAction);
        for (String field : EXPOSED_FIELDS) {
            if (root.has(field)) {
                payload.put(field, safeValue(field, root.get(field)));
            }
        }
        payload.putIfAbsent("type", REFUSAL_TYPE);
        return Optional.of(new DomainAgentControlEvent(
                REFUSAL_TYPE,
                code,
                text(root.get("reasonCode")),
                booleanValue(root.get("recoverable")),
                firstText(root, "reason", "userMessage"),
                text(root.get("agentId")),
                supervisorAction,
                ChatPayloadMaps.immutableCopy(payload)
        ));
    }

    public static Optional<DomainAgentControlEvent> fromNormalizedPayload(Map<String, Object> payload) {
        return DomainAgentControlEvent.fromNormalizedPayload(payload);
    }

    private Object safeValue(String field, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (sensitive(field)) {
            return "[REDACTED]";
        }
        if (node.isObject()) {
            return safeObject(node);
        }
        if (node.isArray()) {
            return safeArray(node);
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        String value = node.asText();
        return value.length() <= 2048 ? value : value.substring(0, 2048);
    }

    private Map<String, Object> safeObject(JsonNode node) {
        Map<String, Object> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> values.put(
                entry.getKey(), safeValue(entry.getKey(), entry.getValue())));
        return ChatPayloadMaps.immutableCopy(values);
    }

    private List<Object> safeArray(JsonNode node) {
        List<Object> values = new ArrayList<>();
        int count = 0;
        for (JsonNode item : node) {
            if (count++ >= 50) {
                values.add("[TRUNCATED]");
                break;
            }
            values.add(safeValue("item", item));
        }
        return Collections.unmodifiableList(values);
    }

    private boolean sensitive(String field) {
        String normalized = field == null ? "" : field.toLowerCase(Locale.ROOT);
        return normalized.contains("cookie") || normalized.contains("authorization")
                || normalized.contains("token") || normalized.contains("secret")
                || normalized.contains("password") || normalized.contains("credential")
                || normalized.contains("apikey") || normalized.contains("api_key")
                || normalized.contains("access_key");
    }

    private static String firstText(JsonNode root, String... fields) {
        for (String field : fields) {
            String value = text(root.get(field));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }

    private static Boolean booleanValue(JsonNode node) {
        return node == null || node.isNull() ? null : node.asBoolean();
    }

}
