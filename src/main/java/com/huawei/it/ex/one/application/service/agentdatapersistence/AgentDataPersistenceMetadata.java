package com.huawei.it.ex.one.application.service.agentdatapersistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** 留存策略在 run metadata 与 assistant metadata 中的内部编码。 */
public final class AgentDataPersistenceMetadata {
    public static final String RUN_METADATA_KEY = "_agentDataPersistence";
    public static final String MESSAGE_METADATA_KEY = "agentDataPersistence";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final String POLICY_KEY = "policy";
    private static final String PLACEHOLDER_CONTENT_KEY = "placeholderContent";

    private AgentDataPersistenceMetadata() {
    }

    public static Map<String, Object> runMetadata(
            AgentDataPersistencePolicy policy,
            String placeholderContent) {
        Map<String, Object> policyValue = new LinkedHashMap<>();
        policyValue.put(POLICY_KEY, normalizePolicy(policy).name());
        if (normalizePolicy(policy) == AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER) {
            policyValue.put(PLACEHOLDER_CONTENT_KEY, placeholderContent);
        }
        return Map.of(RUN_METADATA_KEY, Map.copyOf(policyValue));
    }

    public static RunPolicySnapshot readRunPolicy(Map<String, Object> metadata) {
        if (metadata == null || !(metadata.get(RUN_METADATA_KEY) instanceof Map<?, ?> raw)) {
            return null;
        }
        AgentDataPersistencePolicy policy = parsePolicy(raw.get(POLICY_KEY));
        if (policy == null) {
            return null;
        }
        return new RunPolicySnapshot(policy, text(raw.get(PLACEHOLDER_CONTENT_KEY)));
    }

    /**
     * 从新建 run 的 metadata 中移除服务端保留策略，防止客户端或旧 Interaction 伪造本轮策略。
     */
    public static Map<String, Object> removeRunPolicy(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty() || !metadata.containsKey(RUN_METADATA_KEY)) {
            return metadata == null ? Map.of() : metadata;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(metadata);
        sanitized.remove(RUN_METADATA_KEY);
        return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
    }

    public static String mergeAssistantMetadata(
            String metadataJson,
            AgentDataPersistenceState state) {
        if (state == null || !state.placeholderMode()) {
            return metadataJson;
        }
        Map<String, Object> metadata = parseMetadata(metadataJson);
        metadata.put(MESSAGE_METADATA_KEY, Map.of(
                POLICY_KEY, AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER.name(),
                PLACEHOLDER_CONTENT_KEY, state.placeholderContent()));
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("assistant 留存策略 metadata 序列化失败", ex);
        }
    }

    public static boolean placeholderAssistant(String metadataJson) {
        return messagePolicy(metadataJson) == AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER;
    }

    public static String assistantPlaceholderContent(String metadataJson, String defaultContent) {
        Map<String, Object> metadata = parseMetadata(metadataJson);
        Object rawPolicy = metadata.get(MESSAGE_METADATA_KEY);
        if (!(rawPolicy instanceof Map<?, ?> policyValue)) {
            return defaultContent;
        }
        String content = text(policyValue.get(PLACEHOLDER_CONTENT_KEY));
        return content == null ? defaultContent : content;
    }

    private static AgentDataPersistencePolicy messagePolicy(String metadataJson) {
        Object rawPolicy = parseMetadata(metadataJson).get(MESSAGE_METADATA_KEY);
        if (!(rawPolicy instanceof Map<?, ?> policyValue)) {
            return null;
        }
        return parsePolicy(policyValue.get(POLICY_KEY));
    }

    private static Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(metadataJson, MAP_TYPE);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (JsonProcessingException ex) {
            return new LinkedHashMap<>();
        }
    }

    private static AgentDataPersistencePolicy parsePolicy(Object value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return AgentDataPersistencePolicy.valueOf(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static AgentDataPersistencePolicy normalizePolicy(AgentDataPersistencePolicy policy) {
        return policy == null ? AgentDataPersistencePolicy.FULL : policy;
    }

    private static String text(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    public record RunPolicySnapshot(
            AgentDataPersistencePolicy policy,
            String placeholderContent
    ) {
    }
}
