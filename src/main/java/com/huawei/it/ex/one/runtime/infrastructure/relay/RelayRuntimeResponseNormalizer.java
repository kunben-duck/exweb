package com.huawei.it.ex.one.runtime.infrastructure.relay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.common.event.MessageCompletedEvent;
import com.huawei.it.ex.one.common.event.MessageDeltaEvent;
import com.huawei.it.ex.one.common.event.MessageSnapshotEvent;
import com.huawei.it.ex.one.common.event.RuntimeEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Relay Runtime 响应归一化器。
 *
 * <p>Relay 下游可能返回纯文本、JSON chunk 或 SSE-like {@code data: ...} 片段。本组件只收敛
 * ChatService 顶层事件类型，payload 保留 Relay 原始字段、命名和嵌套结构，并仅补充
 * {@code source/sourceType/runtimeSessionId} 等少量辅助字段。</p>
 */
@Component
public class RelayRuntimeResponseNormalizer {
    private final ObjectMapper objectMapper;
    private final RelayRuntimeMappingProperties mappingProperties;
    private final RelayFrameSplitter frameSplitter;
    private final RelayPayloadMapper payloadMapper;
    private final RelayRuntimeEventFactory runtimeEventFactory;

    public RelayRuntimeResponseNormalizer(ObjectMapper objectMapper) {
        this(objectMapper, new RelayRuntimeMappingProperties());
    }

    @Autowired
    public RelayRuntimeResponseNormalizer(ObjectMapper objectMapper,
                                          ObjectProvider<RelayRuntimeMappingProperties> mappingPropertiesProvider) {
        this(objectMapper, mappingPropertiesProvider == null
                ? new RelayRuntimeMappingProperties()
                : mappingPropertiesProvider.getIfAvailable(RelayRuntimeMappingProperties::new));
    }

    RelayRuntimeResponseNormalizer(ObjectMapper objectMapper, RelayRuntimeMappingProperties mappingProperties) {
        this.objectMapper = objectMapper;
        this.mappingProperties = mappingProperties == null ? new RelayRuntimeMappingProperties() : mappingProperties;
        this.frameSplitter = new RelayFrameSplitter();
        this.payloadMapper = new RelayPayloadMapper();
        this.runtimeEventFactory = new RelayRuntimeEventFactory(payloadMapper);
    }

    /**
     * 将一个 HTTP chunk 或 WebSocket frame 归一化为标准 ChatEvent 列表。
     *
     * @param runId 当前 ChatService run 标识。
     * @param sessionId 当前前端会话标识。
     * @param chunk 下游返回的原始文本片段。
     * @return 标准 ChatEvent 列表；heartbeat/空片段返回空列表。
     */
    public List<ChatEvent> normalize(String runId, String sessionId, String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return List.of();
        }
        String text = chunk;
        List<String> frames = frameSplitter.split(text);
        List<ChatEvent> events = new ArrayList<>();
        for (String frame : frames) {
            events.addAll(normalizeFrame(runId, sessionId, frame));
        }
        return List.copyOf(events);
    }

    private List<ChatEvent> normalizeFrame(String runId, String sessionId, String frame) {
        if (frame == null || frame.isBlank() || isTerminalText(frame)) {
            return isTerminalText(frame) ? List.of(MessageCompletedEvent.of(runId, sessionId)) : List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(frame);
            return normalizeJson(runId, sessionId, root);
        } catch (JsonProcessingException ex) {
            return List.of(MessageDeltaEvent.of(runId, sessionId, frame));
        }
    }

    private List<ChatEvent> normalizeJson(String runId, String sessionId, JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return List.of();
        }
        if (root.isTextual()) {
            String text = root.asText("");
            return text.isBlank() ? List.of() : List.of(MessageDeltaEvent.of(runId, sessionId, text));
        }
        if (!root.isObject()) {
            throw new RelayRuntimeProtocolException("Unsupported Relay runtime frame shape");
        }
        return normalizeObjectFrame(runId, sessionId, root);
    }

    private List<ChatEvent> normalizeObjectFrame(String runId, String sessionId, JsonNode root) {
        String type = firstText(root, "type", "event", "status");
        String normalizedType = normalizeTypeName(type);
        if (isHeartbeat(type, root)) {
            return List.of();
        }
        if (hasError(root) || isError(type)) {
            throw new RelayRuntimeProtocolException(errorMessage(root));
        }
        if ("generate-response".equals(normalizedType)) {
            return generateResponseEvents(runId, sessionId, root, type);
        }
        ChatEvent runtimeEvent = runtimeEventFactory.mappedRuntimeEvent(
                runId, sessionId, root, type, normalizedType);
        if (runtimeEvent != null) {
            return List.of(runtimeEvent);
        }
        if (isCompleted(type) || hasFinishReason(root)) {
            return completedEvents(runId, sessionId, root, type);
        }
        return answerEvents(runId, sessionId, root, type);
    }

    private List<ChatEvent> generateResponseEvents(String runId, String sessionId, JsonNode root, String type) {
        String content = firstText(root, "content");
        if (content != null && !content.isBlank()) {
            return List.of(snapshotEvent(runId, sessionId, content, root));
        }
        return List.of(RuntimeEvent.progress(runId, sessionId, payloadMapper.relayPayload(root, type)));
    }

    private List<ChatEvent> completedEvents(String runId, String sessionId, JsonNode root, String type) {
        String delta = extractAnswerDelta(root, type);
        if (delta == null || delta.isBlank()) {
            return List.of(MessageCompletedEvent.of(runId, sessionId, completionPayload(root)));
        }
        return List.of(deltaEvent(runId, sessionId, delta, root),
                MessageCompletedEvent.of(runId, sessionId, completionPayload(root)));
    }

    private List<ChatEvent> answerEvents(String runId, String sessionId, JsonNode root, String type) {
        String snapshot = extractAnswerSnapshot(root, type);
        if (snapshot != null) {
            return List.of(snapshotEvent(runId, sessionId, snapshot, root));
        }
        String delta = extractAnswerDelta(root, type);
        if (delta != null && !delta.isBlank()) {
            return List.of(deltaEvent(runId, sessionId, delta, root));
        }
        if (isMetadataOnlyDelta(root, type)) {
            return List.of();
        }
        /* Unknown legal objects remain observable as runtime.event rather than failing the run. */
        return List.of(runtimeEventFactory.fallbackRuntimeEvent(runId, sessionId, root, type));
    }

    private boolean isMetadataOnlyDelta(JsonNode root, String type) {
        if (type != null && "message.delta".equals(type.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        JsonNode choice = firstChoice(root);
        JsonNode deltaNode = choice == null ? null : choice.get("delta");
        return deltaNode != null && deltaNode.isObject();
    }

    private ChatEvent deltaEvent(String runId, String sessionId, String delta, JsonNode root) {
        Map<String, Object> payload = payloadMapper.relayPayload(
                root, firstText(root, "type", "event", "status"));
        payload.put("delta", delta);
        return new MessageDeltaEvent(runId, sessionId, 0, Instant.now(), delta, payload);
    }

    private ChatEvent snapshotEvent(String runId, String sessionId, String content, JsonNode root) {
        Map<String, Object> payload = payloadMapper.relayPayload(
                root, firstText(root, "type", "event", "status"));
        payload.put("content", content);
        return new MessageSnapshotEvent(runId, sessionId, 0, Instant.now(), content, payload);
    }

    private Map<String, Object> completionPayload(JsonNode root) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "MESSAGE_COMPLETED");
        putText(payload, "runtimeSessionId", firstText(root, RUNTIME_SESSION_FIELDS));
        putText(payload, "agentSessionId", firstText(root, "agentSessionId", "agent_session_id"));
        putText(payload, "finishReason", firstText(root, "finishReason", "finish_reason"));
        JsonNode choice = firstChoice(root);
        if (choice != null) {
            putText(payload, "finishReason", firstText(choice, "finishReason", "finish_reason"));
        }
        return Map.copyOf(payload);
    }

    private void putText(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }

    private String extractAnswerDelta(JsonNode root, String type) {
        JsonNode choice = firstChoice(root);
        if (choice != null) {
            String choiceDelta = extractChoiceDelta(choice);
            if (choiceDelta != null) {
                return choiceDelta;
            }
        }
        if (!isAnswerDeltaCandidate(type)) {
            return null;
        }
        String direct = firstConfiguredAnswerText(root, type);
        if (direct != null) {
            return direct;
        }
        JsonNode data = root.get("data");
        if (data != null && data.isObject()) {
            String nestedType = firstText(data, "type", "event", "status");
            if (isAnswerDeltaCandidate(nestedType)) {
                return extractAnswerDelta(data, nestedType);
            }
        }
        return null;
    }

    private String extractAnswerSnapshot(JsonNode root, String type) {
        if (!isExplicitStreamingFalse(root) || !isAnswerDeltaCandidate(type)) {
            return null;
        }
        return firstConfiguredAnswerText(root, type);
    }

    private boolean isExplicitStreamingFalse(JsonNode root) {
        JsonNode streaming = firstNode(root, "is_streaming", "isStreaming", "streaming");
        if (streaming == null || streaming.isNull()) {
            return false;
        }
        if (streaming.isBoolean()) {
            return !streaming.booleanValue();
        }
        if (streaming.isTextual()) {
            String value = streaming.asText("").trim();
            return "false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value);
        }
        if (streaming.isNumber()) {
            return streaming.asInt(1) == 0;
        }
        return false;
    }

    private String extractChoiceDelta(JsonNode choice) {
        String choiceText = firstText(choice, "text", "content", "message");
        if (choiceText != null) {
            return choiceText;
        }
        JsonNode deltaNode = choice.get("delta");
        if (deltaNode != null && deltaNode.isObject()) {
            String delta = firstText(deltaNode, "content", "text", "message");
            if (delta != null) {
                return delta;
            }
        }
        JsonNode messageNode = choice.get("message");
        if (messageNode != null && messageNode.isObject()) {
            return firstText(messageNode, "content", "text");
        }
        return null;
    }

    private boolean isAnswerDeltaCandidate(String type) {
        if (type == null || type.isBlank()) {
            return true;
        }
        String normalized = normalizeTypeName(type);
        Set<String> configured = mappingProperties.normalizedAnswerEventTypes();
        return "message.delta".equals(normalized)
                || "delta".equals(normalized)
                || "answer".equals(normalized)
                || "answer.delta".equals(normalized)
                || "assistant.delta".equals(normalized)
                || "output".equals(normalized)
                || "output.delta".equals(normalized)
                || "output_text".equals(normalized)
                || "text".equals(normalized)
                || "output-text".equals(normalized)
                || configured.contains(normalized);
    }

    private boolean hasFinishReason(JsonNode root) {
        if (firstText(root, "finishReason", "finish_reason") != null) {
            return true;
        }
        JsonNode choice = firstChoice(root);
        return choice != null && firstText(choice, "finishReason", "finish_reason") != null;
    }

    private JsonNode firstChoice(JsonNode root) {
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            return choices.get(0);
        }
        return null;
    }

    private boolean isTerminalText(String frame) {
        String normalized = normalizeTypeName(frame);
        return "[done]".equalsIgnoreCase(frame == null ? "" : frame.trim())
                || "steam-complete".equals(normalized)
                || "stream-complete".equals(normalized)
                || "stream.complete".equals(normalized)
                || "stream-completed".equals(normalized);
    }

    private boolean hasError(JsonNode root) {
        JsonNode error = root.get("error");
        return error != null && !error.isNull() && !(error.isTextual() && error.asText("").isBlank());
    }

    private String errorMessage(JsonNode root) {
        String message = firstText(root, "message", "error", "reason");
        if (message != null) {
            return message;
        }
        JsonNode error = root.get("error");
        if (error != null && error.isObject()) {
            String nested = firstText(error, "message", "reason", "code");
            if (nested != null) {
                return nested;
            }
        }
        return "Relay runtime returned error frame";
    }

    private boolean isCompleted(String type) {
        if (type == null) {
            return false;
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        normalized = normalizeTypeName(normalized);
        return "message.completed".equals(normalized)
                || "run.completed".equals(normalized)
                || "completed".equals(normalized)
                || "complete".equals(normalized)
                || "done".equals(normalized)
                || "steam-complete".equals(normalized)
                || "stream-complete".equals(normalized)
                || "stream.complete".equals(normalized)
                || "stream-completed".equals(normalized);
    }

    private boolean isError(String type) {
        if (type == null) {
            return false;
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return "run.failed".equals(normalized)
                || "message.failed".equals(normalized)
                || "error".equals(normalized)
                || "failed".equals(normalized);
    }

    private boolean isHeartbeat(String type, JsonNode root) {
        if (type != null) {
            String normalized = type.trim().toLowerCase(Locale.ROOT);
            if ("heartbeat".equals(normalized) || "ping".equals(normalized) || "keepalive".equals(normalized)) {
                return true;
            }
        }
        return root.size() == 0;
    }

    private String firstText(JsonNode root, String... fieldNames) {
        JsonNode value = firstNode(root, fieldNames);
        if (value == null) {
            return null;
        }
        String text = value.asText(null);
        return text != null && !text.isBlank() ? text : null;
    }

    private JsonNode firstNode(JsonNode root, String... fieldNames) {
        if (root == null || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = root.get(fieldName);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private String firstConfiguredAnswerText(JsonNode root, String type) {
        List<String> fields = mappingProperties.normalizedAnswerContentFields();
        if (normalizeTypeName(type).equals("agent") && !mappingProperties.isAgentContextAsAnswer()) {
            fields = fields.stream().filter(field -> !"context".equals(field)).toList();
        }
        return firstText(root, fields.toArray(String[]::new));
    }

    static String normalizeTypeName(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('—', '-')
                .replace('_', '-');
    }

    private static final String[] RUNTIME_SESSION_FIELDS = {
            "runtimeSessionId", "runtime_session_id", "session_id", "session-id", "session—id", "sessionId"
    };

}
