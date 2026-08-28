package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatRun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Encodes the private run and assistant metadata used by DomainAgent background tasks. */
final class DomainAgentAsyncTaskMetadata {
    static final String RUN_METADATA_KEY = "_domainAgentAsyncTask";
    static final String MESSAGE_METADATA_KEY = "domainAgentAsyncTask";
    static final String PHASE_RUNNING = "ASYNC_RUNNING";

    private static final String PHASE_KEY = "phase";
    private static final String EXPIRES_AT_KEY = "expiresAt";
    private static final String ASSISTANT_MESSAGE_ID_KEY = "assistantMessageId";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private DomainAgentAsyncTaskMetadata() {
    }

    static Map<String, Object> runningOverlay(String assistantMessageId, Instant expiresAt) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put(PHASE_KEY, PHASE_RUNNING);
        task.put(ASSISTANT_MESSAGE_ID_KEY, assistantMessageId);
        task.put(EXPIRES_AT_KEY, expiresAt.toString());
        return Map.of(RUN_METADATA_KEY, Map.copyOf(task));
    }

    static boolean isAsyncRunning(ChatRun run) {
        return run != null && PHASE_RUNNING.equals(text(task(run.metadata()).get(PHASE_KEY)));
    }

    static Instant expiresAt(ChatRun run) {
        String value = run == null ? null : text(task(run.metadata()).get(EXPIRES_AT_KEY));
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    static String assistantMessageId(ChatRun run) {
        return run == null ? null : text(task(run.metadata()).get(ASSISTANT_MESSAGE_ID_KEY));
    }

    static String mergeAssistantMetadata(
            ObjectMapper objectMapper,
            String metadataJson,
            String status,
            Instant expiresAt) {
        Map<String, Object> metadata = parse(objectMapper, metadataJson);
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("status", status);
        if (expiresAt != null) {
            task.put(EXPIRES_AT_KEY, expiresAt.toString());
        }
        metadata.put(MESSAGE_METADATA_KEY, Map.copyOf(task));
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("DomainAgent async assistant metadata serialization failed", ex);
        }
    }

    static Map<String, Object> clearRunMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty() || !metadata.containsKey(RUN_METADATA_KEY)) {
            return metadata == null ? Map.of() : Map.copyOf(metadata);
        }
        Map<String, Object> cleared = new LinkedHashMap<>(metadata);
        cleared.remove(RUN_METADATA_KEY);
        return cleared.isEmpty() ? Map.of() : Map.copyOf(cleared);
    }

    private static Map<?, ?> task(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get(RUN_METADATA_KEY);
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Map<String, Object> parse(ObjectMapper objectMapper, String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(metadataJson, MAP_TYPE);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (JsonProcessingException ex) {
            return new LinkedHashMap<>();
        }
    }

    private static String text(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).trim();
    }
}
