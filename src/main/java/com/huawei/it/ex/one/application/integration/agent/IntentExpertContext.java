/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.agent;

import com.huawei.it.ex.one.domain.chat.IntentExpertScope;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 聚合意图专家在会话、Run及Binding metadata中的内部表示。 */
public final class IntentExpertContext {
    public static final String METADATA_KEY = "_intentExpertScope";
    public static final String ROUTE_SOURCE = "intent-expert";
    public static final String INVOCATION_SKILL_ID_KEY = "invocationSkillId";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private IntentExpertContext() {
    }

    public static Optional<IntentExpertScope> fromSessionMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Optional.empty();
        }
        try {
            return fromMetadata(OBJECT_MAPPER.readValue(metadataJson, MAP_TYPE));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public static Optional<IntentExpertScope> fromMetadata(Map<String, Object> metadata) {
        Object raw = metadata == null ? null : metadata.get(METADATA_KEY);
        if (!(raw instanceof Map<?, ?> values)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new IntentExpertScope(
                    text(values.get("expertId")),
                    text(values.get("expertName")),
                    text(values.get("intentAccessName"))));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public static String replaceSessionMetadata(String metadataJson, IntentExpertScope scope) {
        Map<String, Object> metadata = parseSessionMetadata(metadataJson);
        if (scope == null) {
            metadata.remove(METADATA_KEY);
        } else {
            metadata.put(METADATA_KEY, scopePayload(scope));
        }
        try {
            return metadata.isEmpty() ? null : OBJECT_MAPPER.writeValueAsString(metadata);
        } catch (Exception ex) {
            throw new IllegalStateException("聚合意图专家会话范围序列化失败", ex);
        }
    }

    public static Map<String, Object> withScope(Map<String, Object> metadata, IntentExpertScope scope) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (metadata != null) {
            result.putAll(metadata);
        }
        if (scope == null) {
            result.remove(METADATA_KEY);
        } else {
            result.put(METADATA_KEY, scopePayload(scope));
        }
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    public static Map<String, Object> removeReserved(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty() || !metadata.containsKey(METADATA_KEY)) {
            return metadata == null ? Map.of() : metadata;
        }
        Map<String, Object> result = new LinkedHashMap<>(metadata);
        result.remove(METADATA_KEY);
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    public static boolean matches(Map<String, Object> metadata, IntentExpertScope scope) {
        Optional<IntentExpertScope> stored = fromMetadata(metadata);
        return scope == null ? stored.isEmpty() : stored.map(scope::sameIdentity).orElse(false);
    }

    public static boolean scopedDomainExpert(Map<String, Object> metadata) {
        return fromMetadata(metadata).isPresent();
    }

    public static Map<String, Object> sourceExpert(IntentExpertScope scope) {
        return scope == null ? Map.of() : scopePayload(scope);
    }

    public static Map<String, Object> sourceExpert(Map<String, Object> metadata) {
        return fromMetadata(metadata).map(IntentExpertContext::scopePayload).orElseGet(Map::of);
    }

    private static Map<String, Object> scopePayload(IntentExpertScope scope) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("expertId", scope.expertId());
        payload.put("expertName", scope.displayName());
        payload.put("intentAccessName", scope.intentAccessName());
        return Map.copyOf(payload);
    }

    private static Map<String, Object> parseSessionMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(metadataJson, MAP_TYPE);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (Exception ex) {
            throw new IllegalStateException("会话metadata不是有效JSON对象，无法更新聚合意图专家范围", ex);
        }
    }

    private static String text(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).trim();
    }
}
