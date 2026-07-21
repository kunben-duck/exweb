package com.huawei.it.ex.one.application.integration.agent;

import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.AgentModeSelection;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 模式在 RuntimeBinding 与 Interaction 私有上下文之间的稳定编解码边界。
 */
public final class AgentModeBindingContext {
    public static final String BINDING_METADATA_KEY = "agentMode";
    public static final String INTERACTION_PRIVATE_KEY = "_agentMode";
    private static final String SELECTIONS = "selections";

    private AgentModeBindingContext() {
    }

    /** null update 表示继承，空 profile 表示显式清除。 */
    public static Map<String, Object> apply(Map<String, Object> metadata, AgentModeProfile update) {
        Map<String, Object> next = mutableCopy(metadata);
        if (update == null) {
            return immutableOrEmpty(next);
        }
        next.remove(BINDING_METADATA_KEY);
        if (!update.emptyProfile()) {
            next.put(BINDING_METADATA_KEY, toPayload(update));
        }
        return immutableOrEmpty(next);
    }

    public static AgentModeProfile fromBinding(RuntimeBinding binding) {
        return binding == null ? null : fromMetadata(binding.metadata());
    }

    public static AgentModeProfile fromMetadata(Map<String, Object> metadata) {
        return parse(metadata == null ? null : metadata.get(BINDING_METADATA_KEY));
    }

    public static AgentModeProfile fromInteraction(Map<String, Object> requestPayload) {
        return parse(requestPayload == null ? null : requestPayload.get(INTERACTION_PRIVATE_KEY));
    }

    public static Map<String, Object> attachInteractionPrivate(
            Map<String, Object> requestPayload, AgentModeProfile profile) {
        Map<String, Object> next = mutableCopy(requestPayload);
        next.remove(INTERACTION_PRIVATE_KEY);
        if (profile != null) {
            next.put(INTERACTION_PRIVATE_KEY, toPayload(profile));
        }
        return immutableOrEmpty(next);
    }

    public static Map<String, Object> removeInteractionPrivate(Map<String, Object> requestPayload) {
        Map<String, Object> next = mutableCopy(requestPayload);
        next.remove(INTERACTION_PRIVATE_KEY);
        return immutableOrEmpty(next);
    }

    public static AgentModeProfile resolve(AgentModeProfile requested, RuntimeBinding... inheritedBindings) {
        if (requested != null) {
            return requested;
        }
        if (inheritedBindings == null) {
            return null;
        }
        for (RuntimeBinding binding : inheritedBindings) {
            AgentModeProfile inherited = fromBinding(binding);
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    public static AgentModeProfile resolve(AgentModeProfile requested, Iterable<RuntimeBinding> inheritedBindings) {
        if (requested != null) {
            return requested;
        }
        if (inheritedBindings == null) {
            return null;
        }
        for (RuntimeBinding binding : inheritedBindings) {
            AgentModeProfile inherited = fromBinding(binding);
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    public static Map<String, Object> toPayload(AgentModeProfile profile) {
        if (profile == null) {
            return Map.of();
        }
        List<Map<String, Object>> selections = profile.selections().stream()
                .map(AgentModeBindingContext::selectionPayload)
                .toList();
        return Map.of(SELECTIONS, selections);
    }

    private static Map<String, Object> selectionPayload(AgentModeSelection selection) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("scheme", selection.scheme());
        value.put("code", selection.code());
        if (selection.displayName() != null) {
            value.put("displayName", selection.displayName());
        }
        return Map.copyOf(value);
    }

    private static AgentModeProfile parse(Object value) {
        if (!(value instanceof Map<?, ?> profileMap)) {
            return null;
        }
        Object rawSelections = profileMap.get(SELECTIONS);
        if (!(rawSelections instanceof Iterable<?> values)) {
            return null;
        }
        List<AgentModeSelection> selections = new ArrayList<>();
        try {
            for (Object raw : values) {
                if (!(raw instanceof Map<?, ?> selection)) {
                    return null;
                }
                selections.add(new AgentModeSelection(
                        text(selection.get("scheme")),
                        text(selection.get("code")),
                        text(selection.get("displayName"))));
            }
            return new AgentModeProfile(selections);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Map<String, Object> mutableCopy(Map<String, Object> source) {
        return source == null || source.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private static Map<String, Object> immutableOrEmpty(Map<String, Object> source) {
        return source.isEmpty() ? Map.of() : Map.copyOf(source);
    }
}
