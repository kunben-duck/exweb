/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.MessageSkillContext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** 将当前run的调用标识投影到assistant metadata_json。 */
final class MessageSkillMetadata {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    MessageSkillMetadata(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    MergeResult replace(String metadataJson, String skillId) {
        String normalized = MessageSkillContext.normalizeSkillId(skillId);
        if (normalized == null && !containsSkillKey(metadataJson)) {
            return new MergeResult(metadataJson, false, false);
        }
        Map<String, Object> metadata = parseObject(metadataJson);
        if (metadata == null) {
            return new MergeResult(metadataJson, false, true);
        }
        Object current = metadata.get(MessageSkillContext.MESSAGE_METADATA_KEY);
        if (normalized != null && normalized.equals(current)) {
            return new MergeResult(metadataJson, false, false);
        }
        if (normalized == null) {
            if (!metadata.containsKey(MessageSkillContext.MESSAGE_METADATA_KEY)) {
                return new MergeResult(metadataJson, false, false);
            }
            metadata.remove(MessageSkillContext.MESSAGE_METADATA_KEY);
        } else {
            metadata.put(MessageSkillContext.MESSAGE_METADATA_KEY, normalized);
        }
        return new MergeResult(write(metadata), true, false);
    }

    private boolean containsSkillKey(String metadataJson) {
        if (metadataJson == null) {
            return false;
        }
        return metadataJson.contains("\"" + MessageSkillContext.MESSAGE_METADATA_KEY + "\"");
    }

    private Map<String, Object> parseObject(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(metadataJson, MAP_TYPE);
            return parsed == null ? null : new LinkedHashMap<>(parsed);
        } catch (JsonProcessingException | RuntimeException ex) {
            return null;
        }
    }

    private String write(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("消息skillId metadata序列化失败", ex);
        }
    }

    record MergeResult(String metadataJson, boolean changed, boolean invalidExistingMetadata) {
    }
}
