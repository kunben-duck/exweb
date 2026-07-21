package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.model.DomainAgentRerouteState;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentRefusal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Maps the persisted refusal clarification metadata without performing side effects. */
final class DomainAgentRerouteStateMapper {
    DomainAgentRerouteState from(ChatCommand command) {
        Object value = command == null || command.metadata() == null
                ? null
                : command.metadata().get(DomainAgentRefusalEventFactory.REROUTE_CONTEXT_METADATA);
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> state = mapOrEmpty(raw);
        String currentTargetId = firstText(state.get("currentTargetId"));
        if (currentTargetId == null) {
            return null;
        }
        Set<String> rejected = new HashSet<>();
        Object rejectedValues = state.get("rejectedDomainAgentIds");
        if (rejectedValues instanceof Iterable<?> values) {
            for (Object item : values) {
                String id = firstText(item);
                if (id != null) {
                    rejected.add(id);
                }
            }
        }
        rejected.add(currentTargetId);
        int rerouteCount = intValue(state.get("rerouteCount"), 0);
        DomainAgentRefusal refusal = new DomainAgentRefusal(
                firstText(state.get("refusalCode")), firstText(state.get("refusalReasonCode")),
                booleanValue(state.get("refusalRecoverable")), firstText(state.get("refusalReason")),
                firstText(state.get("refusalAgentId")));
        return new DomainAgentRerouteState(
                currentTargetId, firstText(state.get("currentBindingId")),
                blankToDefault(firstText(state.get("currentRouteSource")), "intent-agent"),
                refusal, Set.copyOf(rejected), rerouteCount);
    }

    ChatCommand withoutRerouteContext(ChatCommand command) {
        if (command == null || command.metadata() == null
                || !command.metadata().containsKey(DomainAgentRefusalEventFactory.REROUTE_CONTEXT_METADATA)) {
            return command;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(command.metadata());
        metadata.remove(DomainAgentRefusalEventFactory.REROUTE_CONTEXT_METADATA);
        return new ChatCommand(command.commandId(), command.tenantId(), command.userId(), command.sessionId(),
                command.conversationId(), command.channel(), command.message(), command.attachments(), metadata,
                command.targetType(), command.targetId(), command.runMode(), command.parentMessageId(),
                command.editedMessageId(), command.regeneratedMessageId(), command.routeTrigger(),
                command.interactionId(), command.approved(), command.scope(), command.questionnaireAnswers(),
                command.appId(), command.appName());
    }

    private Map<String, Object> mapOrEmpty(Object value) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return Map.copyOf(copy);
        }
        return Map.of();
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
}
