package com.huawei.finance.front.one.infrastructure.legacy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.domain.chat.RuntimeEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 老 Agent eventStream 响应归一化器。
 *
 * <p>老 Agent 使用 {@code message: {...}} 这类私有流式帧。该组件把它转换成 ChatService
 * 标准事件，保证 WebSocket、Event Resume、message 与 parts 存储链路继续复用统一协议。</p>
 */
@Component
public class LegacySkillResponseNormalizer {
    private final ObjectMapper objectMapper;

    public LegacySkillResponseNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ChatEvent> normalize(String runId, String sessionId, String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return List.of();
        }
        List<ChatEvent> events = new ArrayList<>();
        for (String frame : splitFrames(chunk)) {
            if (frame == null || frame.isBlank()) {
                continue;
            }
            events.add(normalizeFrame(runId, sessionId, frame));
        }
        return events.stream().filter(event -> event != null).toList();
    }

    private List<String> splitFrames(String chunk) {
        List<String> frames = new ArrayList<>();
        StringBuilder sseData = new StringBuilder();
        boolean sawSse = false;
        for (String line : chunk.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                flush(frames, sseData);
                continue;
            }
            if (trimmed.startsWith(":")) {
                sawSse = true;
                continue;
            }
            if (trimmed.startsWith("event:") || trimmed.startsWith("id:") || trimmed.startsWith("retry:")) {
                sawSse = true;
                continue;
            }
            if (trimmed.startsWith("data:") || trimmed.startsWith("message:")
                    || trimmed.startsWith("message.") || trimmed.startsWith("message ")) {
                sawSse = true;
                String value = valueAfterPrefix(trimmed);
                if (!value.isBlank()) {
                    if (!sseData.isEmpty()) {
                        sseData.append('\n');
                    }
                    sseData.append(value);
                }
            }
        }
        flush(frames, sseData);
        if (!sawSse) {
            frames.add(chunk.trim());
        }
        return frames;
    }

    private void flush(List<String> frames, StringBuilder sseData) {
        if (!sseData.isEmpty()) {
            frames.add(sseData.toString());
            sseData.setLength(0);
        }
    }

    private String valueAfterPrefix(String line) {
        if (line.startsWith("message.")) {
            return line.substring("message.".length()).trim();
        }
        if (line.startsWith("message ")) {
            return line.substring("message ".length()).trim();
        }
        int colon = line.indexOf(':');
        if (colon >= 0) {
            return line.substring(colon + 1).trim();
        }
        return line;
    }

    private ChatEvent normalizeFrame(String runId, String sessionId, String frame) {
        try {
            JsonNode root = objectMapper.readTree(frame);
            return normalizeJson(runId, sessionId, root);
        } catch (JsonProcessingException ex) {
            return RuntimeEvent.fallback("legacy-agent", runId, sessionId, "invalid-json", "event",
                    "runtime", "debug", null, Map.of("raw", truncate(frame)));
        }
    }

    private ChatEvent normalizeJson(String runId, String sessionId, JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return null;
        }
        if (!root.isObject()) {
            return RuntimeEvent.fallback("legacy-agent", runId, sessionId, "unknown", "event",
                    "runtime", "debug", null, Map.of("value", truncate(root.asText(""))));
        }
        if (bool(root, "endFlag")) {
            return MessageCompletedEvent.of(runId, sessionId, Map.of(
                    "status", "MESSAGE_COMPLETED",
                    "sourceType", "legacy-agent-end"
            ));
        }
        String content = text(root, "content");
        if (content != null) {
            return new MessageDeltaEvent(runId, sessionId, 0, Instant.now(), content, Map.of(
                    "delta", content,
                    "sourceType", "legacy-agent-content"
            ));
        }
        if (root.hasNonNull("processResult")) {
            return RuntimeEvent.progress(runId, sessionId, processPayload(root));
        }
        if (root.hasNonNull("traceId")) {
            return RuntimeEvent.metadata(runId, sessionId, metadataPayload("trace", Map.of("traceId", text(root, "traceId"))));
        }
        if (root.hasNonNull("sessionId")) {
            return RuntimeEvent.metadata(runId, sessionId, metadataPayload("legacy_session",
                    Map.of("legacySessionId", text(root, "sessionId"))));
        }
        if (root.hasNonNull("cardUrl") || root.hasNonNull("intent") || root.hasNonNull("skillId")) {
            Map<String, Object> values = new LinkedHashMap<>();
            putIfPresent(values, "cardUrl", text(root, "cardUrl"));
            putIfPresent(values, "intent", text(root, "intent"));
            putIfPresent(values, "skillId", text(root, "skillId"));
            return RuntimeEvent.metadata(runId, sessionId, metadataPayload("skill_card", values));
        }
        return RuntimeEvent.fallback("legacy-agent", runId, sessionId, "unknown", "event",
                "runtime", "debug", null, Map.of("sourcePayload", sanitize(root)));
    }

    private Map<String, Object> processPayload(JsonNode root) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "legacy-agent");
        payload.put("sourceType", "processResult");
        payload.put("metadataType", "process_result");
        JsonNode processResult = root.get("processResult");
        JsonNode dynamicResponse = processResult == null ? null : processResult.get("dynamicResponse");
        if (dynamicResponse != null && dynamicResponse.isArray()) {
            payload.put("dynamicResponse", sanitize(dynamicResponse));
            String text = firstDynamicTitle(dynamicResponse);
            if (text != null) {
                payload.put("text", text);
            }
        }
        return Map.copyOf(payload);
    }

    private Map<String, Object> metadataPayload(String metadataType, Map<String, Object> values) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "legacy-agent");
        payload.put("sourceType", metadataType);
        payload.put("metadataType", metadataType);
        values.forEach((key, value) -> {
            if (value != null) {
                payload.put(key, value);
            }
        });
        return Map.copyOf(payload);
    }

    private String firstDynamicTitle(JsonNode dynamicResponse) {
        for (JsonNode item : dynamicResponse) {
            String title = text(item, "title", "titile");
            if (title != null && !title.isBlank()) {
                return title;
            }
        }
        return null;
    }

    private Object sanitize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), sanitize(entry.getKey(), entry.getValue())));
            return Map.copyOf(map);
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            int count = 0;
            for (JsonNode child : node) {
                if (count++ >= 50) {
                    list.add("[TRUNCATED]");
                    break;
                }
                list.add(sanitize(child));
            }
            return List.copyOf(list);
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return truncate(node.asText(""));
    }

    private Object sanitize(String field, JsonNode node) {
        if (field != null) {
            String normalized = field.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("cookie") || normalized.contains("authorization") || normalized.contains("token")
                    || normalized.contains("secret") || normalized.contains("password")
                    || normalized.contains("credential") || normalized.contains("apikey")
                    || normalized.contains("api_key") || normalized.contains("access_key")) {
                return "[REDACTED]";
            }
        }
        return sanitize(node);
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private boolean bool(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && !value.isNull() && value.asBoolean(false);
    }

    private String text(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode value = root == null ? null : root.get(field);
            if (value != null && !value.isNull()) {
                return value.asText(null);
            }
        }
        return null;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 2048) {
            return value;
        }
        return value.substring(0, 2048);
    }
}
