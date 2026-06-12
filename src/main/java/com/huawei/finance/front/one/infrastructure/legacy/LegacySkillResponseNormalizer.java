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
import java.util.Locale;
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
        return normalize(runId, sessionId, chunk, newStreamState());
    }

    /**
     * 创建一次老 Agent 流式响应的解析状态。
     *
     * <p>老 Agent 的 {@code <think>} 与 {@code </think>} 可能跨 chunk 到达，因此状态必须是单次
     * query 级别的，不能放在 Spring 单例 normalizer 上共享。</p>
     *
     * @return 单次响应流使用的状态对象。
     */
    public LegacySkillStreamState newStreamState() {
        return new LegacySkillStreamState();
    }

    public List<ChatEvent> normalize(String runId, String sessionId, String chunk, LegacySkillStreamState state) {
        if (chunk == null || chunk.isBlank()) {
            return List.of();
        }
        LegacySkillStreamState streamState = state == null ? newStreamState() : state;
        List<ChatEvent> events = new ArrayList<>();
        for (String frame : splitFrames(chunk)) {
            if (frame == null || frame.isBlank()) {
                continue;
            }
            events.addAll(normalizeFrame(runId, sessionId, frame, streamState));
        }
        return events;
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

    private List<ChatEvent> normalizeFrame(String runId, String sessionId, String frame, LegacySkillStreamState state) {
        try {
            JsonNode root = objectMapper.readTree(frame);
            return normalizeJson(runId, sessionId, root, state);
        } catch (JsonProcessingException ex) {
            return List.of(RuntimeEvent.fallback("legacy-agent", runId, sessionId, "invalid-json", "event",
                    "runtime", "debug", null, Map.of("raw", truncate(frame))));
        }
    }

    private List<ChatEvent> normalizeJson(String runId, String sessionId, JsonNode root, LegacySkillStreamState state) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return List.of();
        }
        if (!root.isObject()) {
            return List.of(RuntimeEvent.fallback("legacy-agent", runId, sessionId, "unknown", "event",
                    "runtime", "debug", null, Map.of("value", truncate(root.asText("")))));
        }
        List<ChatEvent> events = new ArrayList<>();
        addMetadataEvents(runId, sessionId, root, events);
        addStateEvent(runId, sessionId, root, events);
        addStructuredEvents(runId, sessionId, root, events);
        String content = text(root, "content");
        if (content != null) {
            events.addAll(contentEvents(runId, sessionId, content, state));
        }
        if (bool(root, "endFlag")) {
            events.addAll(flushPendingContent(runId, sessionId, state));
            events.add(MessageCompletedEvent.of(runId, sessionId, Map.of(
                    "status", "MESSAGE_COMPLETED",
                    "sourceType", "legacy-agent-end"
            )));
        }
        if (!events.isEmpty()) {
            return List.copyOf(events);
        }
        return List.of(RuntimeEvent.fallback("legacy-agent", runId, sessionId, "unknown", "event",
                "runtime", "debug", null, Map.of("sourcePayload", sanitize(root))));
    }

    private void addMetadataEvents(String runId, String sessionId, JsonNode root, List<ChatEvent> events) {
        if (root.hasNonNull("traceId")) {
            events.add(RuntimeEvent.metadata(runId, sessionId,
                    metadataPayload("trace", Map.of("traceId", text(root, "traceId")))));
        }
        if (root.hasNonNull("sessionId")) {
            events.add(RuntimeEvent.metadata(runId, sessionId, metadataPayload("legacy_session",
                    Map.of("legacySessionId", text(root, "sessionId")))));
        }
        if (root.hasNonNull("messageId")) {
            events.add(RuntimeEvent.metadata(runId, sessionId, metadataPayload("legacy_message",
                    Map.of("legacyMessageId", text(root, "messageId")))));
        }
        if ((root.hasNonNull("intent") || root.hasNonNull("skillId")) && !hasCardPayload(root)) {
            Map<String, Object> values = new LinkedHashMap<>();
            putIfPresent(values, "intent", text(root, "intent"));
            putIfPresent(values, "skillId", text(root, "skillId"));
            events.add(RuntimeEvent.metadata(runId, sessionId, metadataPayload("legacy_skill", values)));
        }
    }

    private void addStateEvent(String runId, String sessionId, JsonNode root, List<ChatEvent> events) {
        String state = text(root, "state");
        if (state == null || state.isBlank()) {
            return;
        }
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "legacy-agent");
        payload.put("sourceType", "state");
        payload.put("state", normalized);
        putIfPresent(payload, "stateDesc", text(root, "stateDesc"));
        if ("THINKING".equals(normalized)) {
            payload.put("status", "STARTED");
            putIfPresent(payload, "text", text(root, "stateDesc"));
            events.add(RuntimeEvent.thinking(runId, sessionId, Map.copyOf(payload)));
            return;
        }
        if ("GENERATE".equals(normalized)) {
            payload.put("stage", "GENERATE");
            putIfPresent(payload, "text", text(root, "stateDesc"));
            events.add(RuntimeEvent.progress(runId, sessionId, Map.copyOf(payload)));
            return;
        }
        payload.put("eventKind", "state");
        events.add(RuntimeEvent.fallback("legacy-agent", runId, sessionId, normalized, "state",
                "runtime", "inline", text(root, "stateDesc"), Map.copyOf(payload)));
    }

    private void addStructuredEvents(String runId, String sessionId, JsonNode root, List<ChatEvent> events) {
        if (root.hasNonNull("processResult")) {
            events.add(RuntimeEvent.thinking(runId, sessionId, processPayload(root)));
        }
        if (root.hasNonNull("searchList")) {
            events.add(RuntimeEvent.reference(runId, sessionId,
                    referencePayload("search_list", "searchList", root.get("searchList"))));
        }
        if (root.hasNonNull("sourcesDocuments")) {
            events.add(RuntimeEvent.reference(runId, sessionId,
                    referencePayload("source_documents", "sourcesDocuments", root.get("sourcesDocuments"))));
        }
        if (hasCardPayload(root)) {
            events.add(RuntimeEvent.card(runId, sessionId, cardPayload(root)));
        }
    }

    private boolean hasCardPayload(JsonNode root) {
        return root.hasNonNull("cardUrl") || root.hasNonNull("diyCardScene") || root.hasNonNull("cardList");
    }

    private Map<String, Object> processPayload(JsonNode root) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "legacy-agent");
        payload.put("sourceType", "processResult");
        payload.put("status", "STREAMING");
        payload.put("title", "思考过程");
        payload.put("processResult", sanitize(root.get("processResult")));
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

    private Map<String, Object> referencePayload(String referenceType, String fieldName, JsonNode value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "legacy-agent");
        payload.put("sourceType", fieldName);
        payload.put("referenceType", referenceType);
        payload.put("references", sanitize(value));
        return Map.copyOf(payload);
    }

    private Map<String, Object> cardPayload(JsonNode root) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "legacy-agent");
        payload.put("sourceType", "legacy-card");
        payload.put("cardType", "legacy-card");
        putIfPresent(payload, "cardUrl", text(root, "cardUrl"));
        putIfPresent(payload, "intent", text(root, "intent"));
        putIfPresent(payload, "skillId", text(root, "skillId"));
        if (root.hasNonNull("diyCardScene")) {
            payload.put("diyCardScene", sanitize(root.get("diyCardScene")));
        }
        if (root.hasNonNull("cardList")) {
            payload.put("cardList", sanitize(root.get("cardList")));
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

    private List<ChatEvent> contentEvents(String runId, String sessionId, String content, LegacySkillStreamState state) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        LegacySkillStreamState streamState = state == null ? newStreamState() : state;
        List<ChatEvent> events = new ArrayList<>();
        String input = streamState.pending + content;
        streamState.pending = "";
        while (!input.isEmpty()) {
            if (streamState.inThinking) {
                int end = indexOfIgnoreCase(input, "</think>");
                if (end >= 0) {
                    addThinkingText(runId, sessionId, events, input.substring(0, end));
                    events.add(thinkingBoundary(runId, sessionId, "COMPLETED", null));
                    streamState.inThinking = false;
                    input = input.substring(end + "</think>".length());
                    continue;
                }
                int keep = partialSuffixLength(input, "</think>");
                addThinkingText(runId, sessionId, events, input.substring(0, input.length() - keep));
                streamState.pending = input.substring(input.length() - keep);
                break;
            }
            int start = indexOfIgnoreCase(input, "<think>");
            if (start >= 0) {
                addAnswerDelta(runId, sessionId, events, input.substring(0, start));
                events.add(thinkingBoundary(runId, sessionId, "STARTED", null));
                streamState.inThinking = true;
                input = input.substring(start + "<think>".length());
                continue;
            }
            int keep = partialSuffixLength(input, "<think>");
            addAnswerDelta(runId, sessionId, events, input.substring(0, input.length() - keep));
            streamState.pending = input.substring(input.length() - keep);
            break;
        }
        return events;
    }

    private List<ChatEvent> flushPendingContent(String runId, String sessionId, LegacySkillStreamState state) {
        if (state == null) {
            return List.of();
        }
        List<ChatEvent> events = new ArrayList<>();
        if (!state.pending.isEmpty()) {
            if (state.inThinking) {
                addThinkingText(runId, sessionId, events, state.pending);
            } else {
                addAnswerDelta(runId, sessionId, events, state.pending);
            }
            state.pending = "";
        }
        if (state.inThinking) {
            events.add(thinkingBoundary(runId, sessionId, "COMPLETED", null));
            state.inThinking = false;
        }
        return events;
    }

    private void addAnswerDelta(String runId, String sessionId, List<ChatEvent> events, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        events.add(new MessageDeltaEvent(runId, sessionId, 0, Instant.now(), delta, Map.of(
                "delta", delta,
                "sourceType", "legacy-agent-content"
        )));
    }

    private void addThinkingText(String runId, String sessionId, List<ChatEvent> events, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        events.add(RuntimeEvent.thinking(runId, sessionId, Map.of(
                "source", "legacy-agent",
                "sourceType", "content.think",
                "status", "STREAMING",
                "text", text
        )));
    }

    private RuntimeEvent thinkingBoundary(String runId, String sessionId, String status, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "legacy-agent");
        payload.put("sourceType", "content.think");
        payload.put("status", status);
        if (text != null && !text.isBlank()) {
            payload.put("text", text);
        }
        return RuntimeEvent.thinking(runId, sessionId, Map.copyOf(payload));
    }

    private int indexOfIgnoreCase(String value, String target) {
        return value.toLowerCase(Locale.ROOT).indexOf(target.toLowerCase(Locale.ROOT));
    }

    private int partialSuffixLength(String value, String target) {
        String lowerValue = value.toLowerCase(Locale.ROOT);
        String lowerTarget = target.toLowerCase(Locale.ROOT);
        int max = Math.min(lowerValue.length(), lowerTarget.length() - 1);
        for (int length = max; length > 0; length--) {
            if (lowerValue.endsWith(lowerTarget.substring(0, length))) {
                return length;
            }
        }
        return 0;
    }

    private Object sanitize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                Object value = sanitize(entry.getKey(), entry.getValue());
                if (value != null) {
                    map.put(entry.getKey(), value);
                }
            });
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
                Object value = sanitize(child);
                if (value != null) {
                    list.add(value);
                }
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

    /**
     * 单次 legacy eventStream 的内容解析状态。
     */
    public static final class LegacySkillStreamState {
        private boolean inThinking;
        private String pending = "";
    }
}
