package com.huawei.it.ex.one.infrastructure.runtime.domainagent;

import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;

import com.fasterxml.jackson.databind.JsonNode;

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
    public static final String REFUSAL_TYPE = "agent.refusal";
    public static final String OUT_OF_DOMAIN_CODE = "FN-EX-CAHT-BIZ-DAG-001";
    public static final String METADATA_TYPE = "domain_agent_control";
    public static final String ACTION_REROUTE = "REROUTE";
    public static final String ACTION_UNHANDLED = "UNHANDLED";

    private static final List<String> EXPOSED_FIELDS = List.of(
            "type", "code", "agentId", "agentName", "eventId", "traceId", "timestamp", "schemaVersion",
            "reasonCode", "recoverable", "reason", "userMessage", "metadata"
    );

    public Optional<ControlEvent> map(JsonNode root) {
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
        return Optional.of(new ControlEvent(
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

    public static Optional<ControlEvent> fromNormalizedPayload(Map<String, Object> payload) {
        if (payload == null
                || !REFUSAL_TYPE.equals(stringValue(payload.get("type")))
                || !REFUSAL_TYPE.equals(stringValue(payload.get("sourceType")))
                || !METADATA_TYPE.equals(stringValue(payload.get("metadataType")))) {
            return Optional.empty();
        }
        String action = stringValue(payload.get("supervisorAction"));
        return Optional.of(new ControlEvent(
                REFUSAL_TYPE,
                stringValue(payload.get("code")),
                stringValue(payload.get("reasonCode")),
                booleanValue(payload.get("recoverable")),
                firstText(payload, "reason", "userMessage"),
                stringValue(payload.get("agentId")),
                action,
                ChatPayloadMaps.immutableCopy(payload)
        ));
    }

    private Object safeValue(String field, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (sensitive(field)) {
            return "[REDACTED]";
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> values.put(
                    entry.getKey(), safeValue(entry.getKey(), entry.getValue())));
            return ChatPayloadMaps.immutableCopy(values);
        }
        if (node.isArray()) {
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
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        String value = node.asText();
        return value.length() <= 2048 ? value : value.substring(0, 2048);
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

    private static String firstText(Map<String, Object> payload, String... fields) {
        for (String field : fields) {
            String value = stringValue(payload.get(field));
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

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record ControlEvent(
            String type,
            String code,
            String reasonCode,
            Boolean recoverable,
            String message,
            String agentId,
            String supervisorAction,
            Map<String, Object> payload
    ) {
        public boolean reroute() {
            return REFUSAL_TYPE.equals(type)
                    && OUT_OF_DOMAIN_CODE.equals(code)
                    && ACTION_REROUTE.equals(supervisorAction);
        }
    }
}
