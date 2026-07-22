package com.huawei.it.ex.one.application.integration.agent;

import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.AgentModeSelection;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Agent 模式在 RuntimeBinding metadata 中的稳定编解码边界。 */
public final class AgentModeBindingContext {
    public static final String BINDING_METADATA_KEY = "agentMode";
    private static final String SELECTIONS = "selections";

    private AgentModeBindingContext() {
    }

    /** null 表示不更新，空 profile 表示显式清除，非空 profile 表示完整替换。 */
    public static Map<String, Object> apply(Map<String, Object> metadata, AgentModeProfile update) {
        Map<String, Object> next = mutableCopy(metadata);
        if (update == null) {
            return immutableOrEmpty(next);
        }
        next.remove(BINDING_METADATA_KEY);
        if (!update.emptyProfile()) {
            next.put(BINDING_METADATA_KEY, encode(update));
        }
        return immutableOrEmpty(next);
    }

    public static AgentModeProfile fromBinding(RuntimeBinding binding) {
        return binding == null ? null : fromMetadata(binding.metadata());
    }

    public static AgentModeProfile fromMetadata(Map<String, Object> metadata) {
        return parse(metadata == null ? null : metadata.get(BINDING_METADATA_KEY));
    }

    private static Map<String, Object> encode(AgentModeProfile profile) {
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
