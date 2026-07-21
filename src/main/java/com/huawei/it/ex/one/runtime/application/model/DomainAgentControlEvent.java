package com.huawei.it.ex.one.runtime.application.model;

import com.huawei.it.ex.one.common.event.ChatPayloadMaps;
import java.util.Map;
import java.util.Optional;

/** Stable application representation of a normalized DomainAgent control event. */
public record DomainAgentControlEvent(
        String type,
        String code,
        String reasonCode,
        Boolean recoverable,
        String message,
        String agentId,
        String supervisorAction,
        Map<String, Object> payload
) {
    public static final String REFUSAL_TYPE = "agent.refusal";
    public static final String OUT_OF_DOMAIN_CODE = "FN-EX-CAHT-BIZ-DAG-001";
    public static final String METADATA_TYPE = "domain_agent_control";
    public static final String ACTION_REROUTE = "REROUTE";
    public static final String ACTION_UNHANDLED = "UNHANDLED";

    public DomainAgentControlEvent {
        payload = ChatPayloadMaps.immutableCopy(payload);
    }

    public static Optional<DomainAgentControlEvent> fromNormalizedPayload(Map<String, Object> payload) {
        if (payload == null
                || !REFUSAL_TYPE.equals(stringValue(payload.get("type")))
                || !REFUSAL_TYPE.equals(stringValue(payload.get("sourceType")))
                || !METADATA_TYPE.equals(stringValue(payload.get("metadataType")))) {
            return Optional.empty();
        }
        return Optional.of(new DomainAgentControlEvent(
                REFUSAL_TYPE,
                stringValue(payload.get("code")),
                stringValue(payload.get("reasonCode")),
                booleanValue(payload.get("recoverable")),
                firstText(payload, "reason", "userMessage"),
                stringValue(payload.get("agentId")),
                stringValue(payload.get("supervisorAction")),
                payload
        ));
    }

    public boolean reroute() {
        return REFUSAL_TYPE.equals(type)
                && OUT_OF_DOMAIN_CODE.equals(code)
                && ACTION_REROUTE.equals(supervisorAction);
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

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
