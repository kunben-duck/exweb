package com.huawei.it.ex.one.application.integration.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** 服务端在run中暂存最后一次Runtime调用标识，并在终态投影到消息metadata。 */
public final class MessageSkillContext {
    public static final String RUN_METADATA_KEY = "_messageSkillId";
    public static final String LEGACY_RUN_METADATA_KEY = "_messageSkillIds";
    public static final String MESSAGE_METADATA_KEY = "skillId";
    public static final String LEGACY_MESSAGE_METADATA_KEY = "skillIds";

    private MessageSkillContext() {
    }

    public static Map<String, Object> removeReserved(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()
                || (!metadata.containsKey(RUN_METADATA_KEY)
                && !metadata.containsKey(LEGACY_RUN_METADATA_KEY))) {
            return metadata == null ? Map.of() : Map.copyOf(metadata);
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(metadata);
        sanitized.remove(RUN_METADATA_KEY);
        sanitized.remove(LEGACY_RUN_METADATA_KEY);
        return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
    }

    public static String runSkillId(Map<String, Object> metadata) {
        return metadata == null ? null : normalizeSkillId(metadata.get(RUN_METADATA_KEY));
    }

    public static Map<String, Object> replaceRunSkill(
            Map<String, Object> metadata,
            String skillId) {
        Map<String, Object> replaced = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        replaced.remove(RUN_METADATA_KEY);
        replaced.remove(LEGACY_RUN_METADATA_KEY);
        String normalized = normalizeSkillId(skillId);
        if (normalized != null) {
            replaced.put(RUN_METADATA_KEY, normalized);
        }
        return replaced.isEmpty() ? Map.of() : Map.copyOf(replaced);
    }

    public static String normalizeSkillId(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
