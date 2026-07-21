package com.huawei.it.ex.one.runtime.infrastructure.relay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;

/** Classifies Relay control and terminal frames without owning connection state. */
final class RelayWebSocketFrameClassifier {
    private final ObjectMapper objectMapper;

    RelayWebSocketFrameClassifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    boolean configHandshakeCompleteFrame(String frame) {
        if (frame == null || frame.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(frame);
            String type = RelayRuntimeResponseNormalizer.normalizeTypeName(text(root.path("type")));
            return "session-ready".equals(type);
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    RelayRuntimeProtocolException configHandshakeFailure(String frame) {
        if (frame == null || frame.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(frame);
            String type = RelayRuntimeResponseNormalizer.normalizeTypeName(text(root.path("type")));
            if (!"error".equals(type) && !"clear-session".equals(type) && !"session-mismatch".equals(type)
                    && !hasErrorPayload(root)) {
                return null;
            }
            String failureMessage = configFailureMessage(root, type);
            if (runtimeSessionUnavailable(type, failureMessage)) {
                return new RelayRuntimeSessionUnavailableException(
                        "Relay WebSocket config handshake failed: " + failureMessage);
            }
            return new RelayRuntimeProtocolException("Relay WebSocket config handshake failed: " + failureMessage);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    boolean lateConfigFrame(String frame) {
        return hasType(frame, "config");
    }

    boolean heartbeatFrame(String frame) {
        if (frame == null || frame.isBlank()) {
            return false;
        }
        try {
            String type = normalizedType(objectMapper.readTree(frame));
            return "heartbeat".equals(type) || "heartbeat-response".equals(type);
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    boolean ordinaryTerminalFrame(String frame) {
        if (frame == null || frame.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(frame);
            if (!"session-state".equals(normalizedType(root))) {
                return false;
            }
            String state = text(root.path("state"));
            return state != null && ordinaryTerminalState(state);
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    boolean userTurnTerminalFrame(String frame) {
        return ordinaryTerminalFrame(frame) || questionnaireApprovalRequestFrame(frame);
    }

    boolean terminalTextFrame(String frame) {
        if (frame == null) {
            return false;
        }
        String normalized = frame.trim().toLowerCase(Locale.ROOT);
        return "[done]".equals(normalized)
                || "done".equals(normalized)
                || "message.completed".equals(normalized)
                || "steam-complete".equals(normalized)
                || "stream-complete".equals(normalized)
                || "stream.complete".equals(normalized)
                || "stream-completed".equals(normalized);
    }

    boolean interruptPausedAckFrame(String frame) {
        if (frame == null || frame.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(frame);
            if (!"session-state".equals(normalizedType(root))) {
                return false;
            }
            return "paused".equals(RelayRuntimeResponseNormalizer.normalizeTypeName(text(root.path("state"))));
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    boolean userResponseStartFrame(String frame) {
        if (frame == null || frame.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(frame);
            String type = normalizedType(root);
            if ("session-state".equals(type)) {
                return responseStartingSessionState(text(root.path("state")));
            }
            return switch (type) {
                case "relay-start",
                        "agent",
                        "agent-call",
                        "agent-reasoning",
                        "tool-structured-result",
                        "generate-response",
                        "approval-request",
                        "approval-result",
                        "approval-response" -> true;
                default -> type.startsWith("thinking-") || type.startsWith("tool-");
            };
        } catch (JsonProcessingException ex) {
            return true;
        }
    }

    private boolean questionnaireApprovalRequestFrame(String frame) {
        if (frame == null || frame.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(frame);
            if (!"approval-request".equals(normalizedType(root))) {
                return false;
            }
            return "questionnaire".equalsIgnoreCase(text(root.path("operation_type")));
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    private boolean runtimeSessionUnavailable(String type, String failureMessage) {
        if ("clear-session".equals(type)) {
            return true;
        }
        String normalized = failureMessage == null ? "" : failureMessage.toLowerCase(Locale.ROOT);
        return normalized.contains("session")
                && (normalized.contains("not found") || normalized.contains("corrupt"));
    }

    private boolean hasErrorPayload(JsonNode root) {
        JsonNode error = root == null ? null : root.get("error");
        return error != null && !error.isNull() && !(error.isTextual() && error.asText("").isBlank());
    }

    private String configFailureMessage(JsonNode root, String type) {
        String message = firstText(root, "error_message", "message", "reason", "error_code");
        if (message == null) {
            message = errorText(root.get("error"));
        }
        return (type == null || type.isBlank() ? "unknown" : type) + (message == null ? "" : ": " + message);
    }

    private String errorText(JsonNode error) {
        if (error != null && error.isObject()) {
            return firstText(error, "message", "reason", "code");
        }
        return error == null || error.isNull() ? null : error.asText(null);
    }

    private String firstText(JsonNode root, String... fields) {
        for (String field : fields) {
            String value = text(root.path(field));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean ordinaryTerminalState(String state) {
        String normalizedState = RelayRuntimeResponseNormalizer.normalizeTypeName(state);
        return "idle".equals(normalizedState)
                || "completed".equals(normalizedState)
                || "waiting-user-input".equals(normalizedState)
                || "paused".equals(normalizedState);
    }

    private boolean responseStartingSessionState(String state) {
        String normalizedState = RelayRuntimeResponseNormalizer.normalizeTypeName(state);
        return "waiting-user-input".equals(normalizedState) || "paused".equals(normalizedState);
    }

    private boolean hasType(String frame, String expectedType) {
        if (frame == null || frame.isBlank()) {
            return false;
        }
        try {
            return expectedType.equals(normalizedType(objectMapper.readTree(frame)));
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    private String normalizedType(JsonNode root) {
        return RelayRuntimeResponseNormalizer.normalizeTypeName(text(root.path("type")));
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value;
    }
}
