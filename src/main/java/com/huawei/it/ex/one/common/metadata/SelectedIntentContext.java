package com.huawei.it.ex.one.common.metadata;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在 ChatCommand 内部传递前端展示意图，且在调用下游 Runtime 前将其移除。
 */
public final class SelectedIntentContext {
    private static final String COMMAND_METADATA_KEY = "__financeexSelectedIntent";
    private static final String INTENT_ID = "intentId";
    private static final String INTENT_NAME = "intentName";

    private SelectedIntentContext() {
    }

    public static Map<String, Object> attach(Map<String, Object> metadata, String intentId, String intentName) {
        Map<String, Object> next = mutableCopy(metadata);
        next.remove(COMMAND_METADATA_KEY);
        String normalizedName = normalize(intentName);
        if (normalizedName == null) {
            throw new IllegalArgumentException("selectedIntent.intentName 不能为空");
        }
        Map<String, Object> selected = new LinkedHashMap<>();
        String normalizedId = normalize(intentId);
        if (normalizedId != null) {
            selected.put(INTENT_ID, normalizedId);
        }
        selected.put(INTENT_NAME, normalizedName);
        next.put(COMMAND_METADATA_KEY, Map.copyOf(selected));
        return Map.copyOf(next);
    }

    public static Map<String, Object> removeReserved(Map<String, Object> metadata) {
        Map<String, Object> next = mutableCopy(metadata);
        next.remove(COMMAND_METADATA_KEY);
        return next.isEmpty() ? Map.of() : Map.copyOf(next);
    }

    public static String intentId(Map<String, Object> metadata) {
        return selectedText(metadata, INTENT_ID);
    }

    public static String intentName(Map<String, Object> metadata) {
        return selectedText(metadata, INTENT_NAME);
    }

    private static String selectedText(Map<String, Object> metadata, String key) {
        if (metadata == null || !(metadata.get(COMMAND_METADATA_KEY) instanceof Map<?, ?> selected)) {
            return null;
        }
        return normalize(selected.get(key));
    }

    private static Map<String, Object> mutableCopy(Map<String, Object> metadata) {
        return metadata == null || metadata.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    private static String normalize(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
