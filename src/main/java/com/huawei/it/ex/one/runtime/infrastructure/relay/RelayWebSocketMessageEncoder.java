package com.huawei.it.ex.one.runtime.infrastructure.relay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeRequest;
import com.huawei.it.ex.one.runtime.application.model.RuntimeSessionMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Encodes the existing Relay WebSocket wire messages without owning connection state. */
final class RelayWebSocketMessageEncoder {
    private final ObjectMapper objectMapper;
    private final RelayAgentProperties properties;

    RelayWebSocketMessageEncoder(ObjectMapper objectMapper, RelayAgentProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    String configMessage(AgentRuntimeRequest request) {
        String relaySessionId = relaySessionIdForQuery(request);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sessionMode", request.runtimeSessionMode() == RuntimeSessionMode.NEW ? "new" : "resume");
        config.put("sessionId", relaySessionId);
        config.put("uid", request.userId());
        putTraceId(config, request.traceContext());
        if (request.runtimeSessionMode() == RuntimeSessionMode.RESUME) {
            config.put("supports_incremental_recovery", true);
        }
        putAppMode(config);
        return toJson(Map.of("type", "config", "config", Map.copyOf(config)));
    }

    String configMessage(AgentRuntimeCancelRequest request) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sessionMode", "resume");
        config.put("sessionId", relaySessionIdForCancel(request));
        config.put("uid", request.userId());
        putTraceId(config, request.traceContext());
        config.put("supports_incremental_recovery", true);
        putAppMode(config);
        return toJson(Map.of("type", "config", "config", Map.copyOf(config)));
    }

    String configMessage(AgentRuntimeInteractionResponseRequest request) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sessionMode", "resume");
        config.put("sessionId", blank(request.runtimeSessionId()) ? request.sessionId() : request.runtimeSessionId());
        config.put("uid", request.userId());
        putTraceId(config, request.traceContext());
        config.put("supports_incremental_recovery", true);
        putAppMode(config);
        return toJson(Map.of("type", "config", "config", Map.copyOf(config)));
    }

    String userMessage(AgentRuntimeRequest request) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "user-message");
        message.put("content", request.message() == null ? "" : request.message());
        putTraceId(message, request.traceContext());
        Map<String, Object> metadata = RelayRuntimeWireRequestMapper.relayMetadata(
                request.metadata(), request.userAccount(), request.globalUserId());
        if (!metadata.isEmpty()) {
            message.put("metadata", metadata);
        }
        return toJson(message);
    }

    String approvalResponseMessage(AgentRuntimeInteractionResponseRequest request) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "approval-response");
        message.put("request_id", request.approvalId());
        message.put("approved", booleanValue(request.responsePayload().get("approved")));
        message.put("scope", stringOrDefault(request.responsePayload().get("scope"), "once"));
        Object answers = request.responsePayload().get("questionnaireAnswers");
        if (answers instanceof Map<?, ?> answerMap) {
            message.put("questionnaire_answers", answerMap);
        } else {
            message.put("questionnaire_answers", Map.of());
        }
        Map<String, Object> metadataCopy = new LinkedHashMap<>();
        Object metadataNode = request.responsePayload().get("metadata");
        if (metadataNode instanceof Map<?, ?> metadataMap && !metadataMap.isEmpty()) {
            metadataMap.forEach((key, value) -> {
                if (key != null) {
                    metadataCopy.put(String.valueOf(key), value);
                }
            });
        }
        Map<String, Object> relayMetadata = RelayRuntimeWireRequestMapper.relayMetadata(
                metadataCopy, request.userAccount(), request.globalUserId());
        if (!relayMetadata.isEmpty()) {
            message.put("metadata", relayMetadata);
        }
        message.put("timestamp", Instant.now().toString());
        return toJson(message);
    }

    String stopAllAgentsMessage() {
        return toJson(Map.of("type", "stop_all_agents"));
    }

    String heartbeatMessage() {
        return toJson(Map.of("type", "heartbeat"));
    }

    private void putAppMode(Map<String, Object> config) {
        String appMode = properties.getRelay().getWebsocket().getAppMode();
        if (appMode != null && !appMode.isBlank()) {
            config.put("appMode", appMode);
        }
    }

    private void putTraceId(Map<String, Object> target, TraceContext traceContext) {
        if (target != null && traceContext != null && traceContext.hasTraceId()) {
            target.put("traceId", traceContext.traceId());
        }
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private String stringOrDefault(Object value, String defaultValue) {
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return String.valueOf(value);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new RelayRuntimeProtocolException("Failed to serialize Relay WebSocket request: " + ex.getMessage());
        }
    }

    private String relaySessionIdForQuery(AgentRuntimeRequest request) {
        if (request.runtimeSessionMode() == RuntimeSessionMode.NEW) {
            return request.sessionId();
        }
        return blank(request.runtimeSessionId()) ? request.sessionId() : request.runtimeSessionId();
    }

    String relaySessionIdForCancel(AgentRuntimeCancelRequest request) {
        return blank(request.runtimeSessionId()) ? request.sessionId() : request.runtimeSessionId();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
