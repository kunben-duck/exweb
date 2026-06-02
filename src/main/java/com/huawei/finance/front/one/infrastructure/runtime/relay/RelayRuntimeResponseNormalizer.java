package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.domain.chat.RuntimeEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Relay Runtime 响应归一化器。
 *
 * <p>Relay 下游可能返回纯文本、JSON chunk 或 SSE-like {@code data: ...} 片段。本组件把这些
 * 私有协议统一转换成 ChatService 标准 ChatEvent，确保前端只消费稳定的
 * {@code message.delta/message.completed/run.failed/runtime.event} 语义，不接触下游原始响应体。</p>
 */
@Component
public class RelayRuntimeResponseNormalizer {
    private static final int MAX_SOURCE_PAYLOAD_DEPTH = 6;
    private static final int MAX_SOURCE_PAYLOAD_STRING_LENGTH = 2048;
    private static final int MAX_SOURCE_PAYLOAD_ARRAY_SIZE = 50;
    private static final String REDACTED = "[REDACTED]";

    private final ObjectMapper objectMapper;

    public RelayRuntimeResponseNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
        String text = chunk.trim();
        List<String> frames = splitFrames(text);
        List<ChatEvent> events = new ArrayList<>();
        for (String frame : frames) {
            events.addAll(normalizeFrame(runId, sessionId, frame));
        }
        return List.copyOf(events);
    }

    private List<String> splitFrames(String text) {
        List<String> frames = new ArrayList<>();
        StringBuilder currentSseData = new StringBuilder();
        boolean sawSseLine = false;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                flushSseFrame(frames, currentSseData);
                continue;
            }
            if (trimmed.startsWith(":")) {
                sawSseLine = true;
                continue;
            }
            if (trimmed.startsWith("event:") || trimmed.startsWith("id:") || trimmed.startsWith("retry:")) {
                sawSseLine = true;
                continue;
            }
            if (trimmed.startsWith("data:")) {
                sawSseLine = true;
                String value = trimmed.substring("data:".length()).trim();
                if (!value.isEmpty()) {
                    if (!currentSseData.isEmpty()) {
                        currentSseData.append('\n');
                    }
                    currentSseData.append(value);
                }
                continue;
            }
            if (sawSseLine) {
                flushSseFrame(frames, currentSseData);
                frames.add(trimmed);
            }
        }
        flushSseFrame(frames, currentSseData);
        if (!sawSseLine) {
            frames.add(text);
        }
        return frames;
    }

    private void flushSseFrame(List<String> frames, StringBuilder currentSseData) {
        if (!currentSseData.isEmpty()) {
            frames.add(currentSseData.toString());
            currentSseData.setLength(0);
        }
    }

    private List<ChatEvent> normalizeFrame(String runId, String sessionId, String frame) {
        if (frame == null || frame.isBlank() || isDone(frame)) {
            return isDone(frame) ? List.of(MessageCompletedEvent.of(runId, sessionId)) : List.of();
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
        String type = firstText(root, "type", "event", "status");
        if (isHeartbeat(type, root)) {
            return List.of();
        }
        if (hasError(root) || isError(type)) {
            throw new RelayRuntimeProtocolException(errorMessage(root));
        }
        if (isCompleted(type) || hasFinishReason(root)) {
            String delta = extractAnswerDelta(root, type);
            if (delta == null || delta.isBlank()) {
                return List.of(MessageCompletedEvent.of(runId, sessionId, completionPayload(root)));
            }
            return List.of(deltaEvent(runId, sessionId, delta, root),
                    MessageCompletedEvent.of(runId, sessionId, completionPayload(root)));
        }
        String delta = extractAnswerDelta(root, type);
        if (delta == null || delta.isBlank()) {
            if (isMetadataOnlyDelta(root, type)) {
                return List.of();
            }
            /*
             * Relay 的事件类型会随下游 Agent 版本持续演进。没有 answer delta、也不是 terminal/error
             * 的合法 JSON object 不应让本轮 run 失败，而是作为 runtime.event 可控透传给前端。
             */
            return List.of(runtimeEvent(runId, sessionId, root, type));
        }
        return List.of(deltaEvent(runId, sessionId, delta, root));
    }

    private boolean isMetadataOnlyDelta(JsonNode root, String type) {
        if (type != null && "message.delta".equals(type.trim().toLowerCase())) {
            return true;
        }
        JsonNode choice = firstChoice(root);
        JsonNode deltaNode = choice == null ? null : choice.get("delta");
        return deltaNode != null && deltaNode.isObject();
    }

    private ChatEvent deltaEvent(String runId, String sessionId, String delta, JsonNode root) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("delta", delta);
        copyText(root, payload, "runtimeSessionId", "runtimeSessionId", "runtime_session_id");
        copyText(root, payload, "agentSessionId", "agentSessionId", "agent_session_id");
        return new MessageDeltaEvent(runId, sessionId, 0, Instant.now(), delta, Map.copyOf(payload));
    }

    private Map<String, Object> completionPayload(JsonNode root) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "MESSAGE_COMPLETED");
        copyText(root, payload, "runtimeSessionId", "runtimeSessionId", "runtime_session_id");
        copyText(root, payload, "agentSessionId", "agentSessionId", "agent_session_id");
        copyText(root, payload, "finishReason", "finishReason", "finish_reason");
        JsonNode choice = firstChoice(root);
        if (choice != null) {
            copyText(choice, payload, "finishReason", "finishReason", "finish_reason");
        }
        return Map.copyOf(payload);
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
        String direct = firstText(root, "delta", "content", "message", "text", "output_text");
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
        String normalized = type.trim().toLowerCase();
        return "message.delta".equals(normalized)
                || "delta".equals(normalized)
                || "answer".equals(normalized)
                || "answer.delta".equals(normalized)
                || "assistant.delta".equals(normalized)
                || "output".equals(normalized)
                || "output.delta".equals(normalized)
                || "output_text".equals(normalized)
                || "text".equals(normalized);
    }

    private RuntimeEvent runtimeEvent(String runId, String sessionId, JsonNode root, String type) {
        String sourceType = type == null || type.isBlank() ? "unknown" : type.trim();
        String channel = runtimeChannel(sourceType);
        String text = firstText(root, "displayText", "display_text", "title", "description");
        if (text == null && isRuntimeTextSafe(sourceType)) {
            text = firstText(root, "message", "text");
        }
        return RuntimeEvent.relay(runId, sessionId, sourceType, runtimeEventKind(sourceType),
                channel, channel, text, sourcePayload(root));
    }

    private boolean isRuntimeTextSafe(String sourceType) {
        String normalized = sourceType == null ? "" : sourceType.trim().toLowerCase();
        return normalized.contains("progress")
                || normalized.contains("thinking")
                || normalized.contains("agent")
                || normalized.contains("tool")
                || normalized.contains("status");
    }

    private String runtimeEventKind(String sourceType) {
        String normalized = sourceType == null ? "" : sourceType.trim().toLowerCase();
        if (normalized.contains("delta")) {
            return "delta";
        }
        if (normalized.contains("progress")) {
            return "progress";
        }
        return "event";
    }

    private String runtimeChannel(String sourceType) {
        String normalized = sourceType == null ? "" : sourceType.trim().toLowerCase();
        if (normalized.contains("thinking") || normalized.contains("reasoning")) {
            return "thinking";
        }
        if (normalized.contains("progress")) {
            return "progress";
        }
        if (normalized.contains("agent")) {
            return "agent";
        }
        if (normalized.contains("tool")) {
            return "tool";
        }
        return "runtime";
    }

    private Map<String, Object> sourcePayload(JsonNode root) {
        Object sanitized = sanitizeJson(root, "", 0);
        if (sanitized instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return Collections.unmodifiableMap(result);
        }
        return Map.of("value", sanitized);
    }

    private Object sanitizeJson(JsonNode node, String fieldName, int depth) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (isSensitiveField(fieldName)) {
            return REDACTED;
        }
        if (depth >= MAX_SOURCE_PAYLOAD_DEPTH) {
            return "[TRUNCATED]";
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                map.put(entry.getKey(), sanitizeJson(entry.getValue(), entry.getKey(), depth + 1));
            }
            return Collections.unmodifiableMap(map);
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            int count = 0;
            for (JsonNode child : node) {
                if (count++ >= MAX_SOURCE_PAYLOAD_ARRAY_SIZE) {
                    values.add("[TRUNCATED]");
                    break;
                }
                values.add(sanitizeJson(child, fieldName, depth + 1));
            }
            return Collections.unmodifiableList(values);
        }
        if (node.isTextual()) {
            return truncate(node.asText(""));
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return truncate(node.asText(""));
    }

    private boolean isSensitiveField(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        String normalized = fieldName.trim().toLowerCase();
        return normalized.contains("cookie")
                || normalized.contains("authorization")
                || normalized.equals("auth")
                || normalized.endsWith("_auth")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("credential")
                || normalized.contains("api_key")
                || normalized.contains("apikey")
                || normalized.contains("access_key");
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_SOURCE_PAYLOAD_STRING_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_SOURCE_PAYLOAD_STRING_LENGTH) + "...[TRUNCATED]";
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

    private boolean isDone(String frame) {
        return "[DONE]".equalsIgnoreCase(frame == null ? "" : frame.trim());
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
        String normalized = type.trim().toLowerCase();
        return "message.completed".equals(normalized)
                || "run.completed".equals(normalized)
                || "completed".equals(normalized)
                || "complete".equals(normalized)
                || "done".equals(normalized);
    }

    private boolean isError(String type) {
        if (type == null) {
            return false;
        }
        String normalized = type.trim().toLowerCase();
        return "run.failed".equals(normalized)
                || "message.failed".equals(normalized)
                || "error".equals(normalized)
                || "failed".equals(normalized);
    }

    private boolean isHeartbeat(String type, JsonNode root) {
        if (type != null) {
            String normalized = type.trim().toLowerCase();
            if ("heartbeat".equals(normalized) || "ping".equals(normalized) || "keepalive".equals(normalized)) {
                return true;
            }
        }
        return root.size() == 0;
    }

    private void copyText(JsonNode root, Map<String, Object> payload, String target, String... sourceNames) {
        String value = firstText(root, sourceNames);
        if (value != null) {
            payload.put(target, value);
        }
    }

    private String firstText(JsonNode root, String... fieldNames) {
        if (root == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = root.get(fieldName);
            if (value != null && !value.isNull()) {
                String text = value.asText(null);
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }
}
