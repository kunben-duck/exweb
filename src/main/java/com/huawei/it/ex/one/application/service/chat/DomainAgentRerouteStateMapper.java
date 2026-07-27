package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatCommand;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Reads the private DomainAgent reroute state stored across Intent clarification turns. */
final class DomainAgentRerouteStateMapper {
    DomainAgentRerouteState from(ChatCommand command) {
        Object value = command == null || command.metadata() == null
                ? null
                : command.metadata().get(RouteResolutionCoordinator.DOMAIN_AGENT_REROUTE_CONTEXT_METADATA);
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> state = mapOrEmpty(raw);
        String currentTargetId = firstText(state.get("currentTargetId"));
        if (currentTargetId == null) {
            return null;
        }
        Set<String> rejected = rejectedDomainAgentIds(state.get("rejectedDomainAgentIds"));
        rejected.add(currentTargetId);
        DomainAgentRefusal refusal = new DomainAgentRefusal(
                firstText(state.get("refusalCode")),
                firstText(state.get("refusalReasonCode")),
                booleanValue(state.get("refusalRecoverable")),
                firstText(state.get("refusalReason")),
                firstText(state.get("refusalAgentId")));
        return new DomainAgentRerouteState(
                currentTargetId,
                firstText(state.get("currentBindingId")),
                blankToDefault(firstText(state.get("currentRouteSource")), "intent-agent"),
                refusal,
                Set.copyOf(rejected),
                intValue(state.get("rerouteCount"), 0));
    }

    private Set<String> rejectedDomainAgentIds(Object value) {
        Set<String> rejected = new HashSet<>();
        if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                String id = firstText(item);
                if (id != null) {
                    rejected.add(id);
                }
            }
        }
        return rejected;
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private Map<String, Object> mapOrEmpty(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return Map.copyOf(copy);
    }
}
