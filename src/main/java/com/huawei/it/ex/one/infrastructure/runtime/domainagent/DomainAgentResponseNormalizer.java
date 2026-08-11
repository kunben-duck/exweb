package com.huawei.it.ex.one.infrastructure.runtime.domainagent;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;
import com.huawei.it.ex.one.domain.chat.MessageCompletedEvent;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DomainAgent eventStream 响应归一化器。
 *
 * <p>DomainAgent 使用 {@code message: {...}} 这类私有流式帧。该组件把它转换成 ChatService
 * 标准事件，保证 WebSocket、Event Resume、message 与 parts 存储链路继续复用统一协议。</p>
 */
@Component
public class DomainAgentResponseNormalizer {
    private final ObjectMapper objectMapper;
    private final DomainAgentControlEventMapper controlEventMapper;
    private final int maxPendingFrameBytes;

    public DomainAgentResponseNormalizer(ObjectMapper objectMapper) {
        this(objectMapper, new DomainAgentProperties());
    }

    @Autowired
    public DomainAgentResponseNormalizer(ObjectMapper objectMapper, DomainAgentProperties properties) {
        this.objectMapper = objectMapper;
        this.controlEventMapper = new DomainAgentControlEventMapper();
        DomainAgentProperties nextProperties = properties == null ? new DomainAgentProperties() : properties;
        this.maxPendingFrameBytes = nextProperties.normalizedMaxPendingFrameBytes();
    }

    public List<ChatEvent> normalize(String runId, String sessionId, String chunk) {
        return normalize(runId, sessionId, chunk, newStreamState());
    }

    /**
     * 创建一次 DomainAgent 流式响应的解析状态。
     *
     * <p>DomainAgent 的 {@code <think>} 与 {@code </think>} 可能跨 chunk 到达，因此状态必须是单次
     * query 级别的，不能放在 Spring 单例 normalizer 上共享。</p>
     *
     * @return 单次响应流使用的状态对象。
     */
    public DomainAgentStreamState newStreamState() {
        return new DomainAgentStreamState();
    }

    public List<ChatEvent> normalize(String runId, String sessionId, String chunk, DomainAgentStreamState state) {
        if (chunk == null || chunk.isEmpty()) {
            return List.of();
        }
        DomainAgentStreamState streamState = state == null ? newStreamState() : state;
        List<ChatEvent> events = new ArrayList<>();
        if (streamState.pendingFrame != null) {
            events.addAll(continuePendingFrame(runId, sessionId, chunk, streamState));
            return List.copyOf(events);
        }
        streamState.frameBuffer.append(chunk);
        while (true) {
            FrameExtraction extraction = extractCompleteFrame(streamState.frameBuffer);
            if (extraction == null) {
                break;
            }
            if (extraction.frame() != null && !extraction.frame().isBlank()) {
                events.addAll(normalizeFrame(runId, sessionId, extraction.frame(), streamState));
            }
        }
        if (!streamState.frameBuffer.isEmpty()) {
            String pending = stripLeadingFramePrefix(streamState.frameBuffer.toString());
            int jsonStart = firstJsonStart(pending);
            if (jsonStart >= 0) {
                streamState.frameBuffer.setLength(0);
                startPendingFrame(streamState, pending.substring(jsonStart));
            } else {
                validateFrameSize(pending);
            }
        }
        return List.copyOf(events);
    }

    /**
     * 下游流结束时调用，用于处理未闭合的残留帧和未闭合的 think 内容。
     *
     * <p>正常分包不会在这里产生 invalid-json；只有上游连接结束后仍残留无法闭合的 frame，
     * 才输出诊断事件，避免把半截 DataBuffer 误判为业务事件。</p>
     */
    public List<ChatEvent> finish(String runId, String sessionId, DomainAgentStreamState state) {
        if (state == null) {
            return List.of();
        }
        List<ChatEvent> events = new ArrayList<>();
        if (state.pendingFrame != null) {
            String remaining = state.pendingFrame.content().toString();
            state.pendingFrame = null;
            events.add(RuntimeEvent.fallback(runId, sessionId, new RuntimeEvent.FallbackPayload(
                    "domain-agent", "invalid-json", "event", "runtime", "debug", null,
                    Map.of("raw", truncate(remaining),
                            "reason", "domain-agent stream ended before current frame was closed"))));
        }
        if (!state.frameBuffer.isEmpty()) {
            String remaining = stripLeadingFramePrefix(state.frameBuffer.toString());
            if (!remaining.isBlank()) {
                events.add(RuntimeEvent.fallback(runId, sessionId, new RuntimeEvent.FallbackPayload(
                        "domain-agent", "invalid-json", "event", "runtime", "debug", null,
                        Map.of("raw", truncate(remaining)))));
            }
            state.frameBuffer.setLength(0);
        }
        events.addAll(flushPendingContent(runId, sessionId, state));
        return List.copyOf(events);
    }

    private String valueAfterPrefix(String line) {
        if (line.startsWith("message.")) {
            return stripProtocolLeadingWhitespace(line.substring("message.".length()));
        }
        if (line.startsWith("message ")) {
            return stripProtocolLeadingWhitespace(line.substring("message ".length()));
        }
        int colon = line.indexOf(':');
        if (colon >= 0) {
            return stripProtocolLeadingWhitespace(line.substring(colon + 1));
        }
        return line;
    }

    private String stripProtocolLeadingWhitespace(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index == 0 ? value : value.substring(index);
    }

    private FrameExtraction extractCompleteFrame(StringBuilder buffer) {
        trimLeadingWhitespace(buffer);
        if (buffer.isEmpty()) {
            return null;
        }
        EventDelimiter delimiter = findEventDelimiter(buffer);
        if (delimiter != null) {
            String segment = buffer.substring(0, delimiter.index());
            buffer.delete(0, delimiter.index() + delimiter.length());
            return new FrameExtraction(payloadFromSegment(segment));
        }
        String candidate = stripLeadingFramePrefix(buffer.toString());
        int jsonStart = firstJsonStart(candidate);
        if (jsonStart < 0) {
            return null;
        }
        int end = completeJsonEnd(candidate, jsonStart);
        if (end < 0) {
            return null;
        }
        String frame = candidate.substring(jsonStart, end + 1);
        int deleteLength = buffer.length() - candidate.length() + end + 1;
        buffer.delete(0, deleteLength);
        return new FrameExtraction(frame);
    }

    private String payloadFromSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return "";
        }
        StringBuilder payload = new StringBuilder();
        boolean sawStreamPrefix = false;
        for (String line : segment.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(":")
                    || trimmed.startsWith("event:") || trimmed.startsWith("id:")
                    || trimmed.startsWith("retry:")) {
                continue;
            }
            if (hasFramePrefix(trimmed)) {
                sawStreamPrefix = true;
                String value = valueAfterPrefix(trimmed);
                if (!value.isBlank()) {
                    if (!payload.isEmpty()) {
                        payload.append('\n');
                    }
                    payload.append(value);
                }
            }
        }
        return sawStreamPrefix ? payload.toString() : segment.trim();
    }

    private String stripLeadingFramePrefix(String value) {
        String next = value == null ? "" : value.stripLeading();
        if (next.startsWith("data:") || next.startsWith("message:")
                || next.startsWith("message.") || next.startsWith("message ")) {
            return valueAfterPrefix(next);
        }
        return next;
    }

    private boolean hasFramePrefix(String value) {
        return value.startsWith("data:") || value.startsWith("message:")
                || value.startsWith("message.") || value.startsWith("message ");
    }

    private void trimLeadingWhitespace(StringBuilder buffer) {
        int index = 0;
        while (index < buffer.length() && Character.isWhitespace(buffer.charAt(index))) {
            index++;
        }
        if (index > 0) {
            buffer.delete(0, index);
        }
    }

    private EventDelimiter findEventDelimiter(CharSequence value) {
        int lf = indexOf(value, "\n\n");
        int crlf = indexOf(value, "\r\n\r\n");
        if (lf < 0 && crlf < 0) {
            return null;
        }
        if (lf < 0 || crlf >= 0 && crlf < lf) {
            return new EventDelimiter(crlf, "\r\n\r\n".length());
        }
        return new EventDelimiter(lf, "\n\n".length());
    }

    private int indexOf(CharSequence value, String target) {
        int max = value.length() - target.length();
        for (int i = 0; i <= max; i++) {
            int j = 0;
            while (j < target.length() && value.charAt(i + j) == target.charAt(j)) {
                j++;
            }
            if (j == target.length()) {
                return i;
            }
        }
        return -1;
    }

    private int firstJsonStart(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '{' || c == '[') {
                return i;
            }
        }
        return -1;
    }

    private int completeJsonEnd(String value, int start) {
        JsonBoundaryScanner scanner = new JsonBoundaryScanner();
        return scanner.accept(value, start);
    }

    private void startPendingFrame(DomainAgentStreamState state, String payload) {
        validateFrameSize(payload);
        state.pendingFrame = new DomainAgentPendingFrame(payload);
    }

    private List<ChatEvent> continuePendingFrame(String runId, String sessionId, String chunk,
                                                 DomainAgentStreamState state) {
        DomainAgentPendingFrame pending = state.pendingFrame;
        if (pending == null || chunk == null || chunk.isEmpty()) {
            return List.of();
        }
        int completeIndex = pending.scanner().accept(chunk, 0);
        String effective = completeIndex >= 0 ? chunk.substring(0, completeIndex + 1) : chunk;
        int actualBytes = pending.append(effective);
        try {
            validateFrameSize(actualBytes);
        } catch (DomainAgentProtocolException ex) {
            pending.content().setLength(0);
            state.pendingFrame = null;
            throw ex;
        }
        if (completeIndex < 0) {
            return List.of();
        }
        String frame = pending.content().toString();
        pending.content().setLength(0);
        state.pendingFrame = null;
        List<ChatEvent> events = new ArrayList<>(normalizeFrame(runId, sessionId, frame, state));
        String tail = chunk.substring(completeIndex + 1).trim();
        if (!tail.isEmpty()) {
            events.addAll(normalize(runId, sessionId, tail, state));
        }
        return List.copyOf(events);
    }

    private List<ChatEvent> normalizeFrame(String runId, String sessionId, String frame, DomainAgentStreamState state) {
        validateFrameSize(frame);
        try {
            JsonNode root = objectMapper.readTree(frame);
            return normalizeJson(runId, sessionId, root, state);
        } catch (JsonProcessingException ex) {
            RuntimeEvent.FallbackPayload payload = new RuntimeEvent.FallbackPayload(
                    "domain-agent", "invalid-json", "event", "runtime", "debug", null,
                    Map.of("raw", truncate(frame)));
            return List.of(RuntimeEvent.fallback(runId, sessionId, payload));
        }
    }

    private List<ChatEvent> normalizeJson(String runId, String sessionId, JsonNode root, DomainAgentStreamState state) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return List.of();
        }
        if (!root.isObject()) {
            return List.of(RuntimeEvent.fallback(runId, sessionId, new RuntimeEvent.FallbackPayload(
                    "domain-agent", "unknown", "event", "runtime", "debug", null,
                    Map.of("value", truncate(root.asText(""))))));
        }
        List<ChatEvent> events = new ArrayList<>();
        DomainAgentControlEventMapper.ControlEvent controlEvent = controlEventMapper.map(root).orElse(null);
        if (controlEvent != null) {
            if (controlEvent.reroute()) {
                events.addAll(flushPendingContent(runId, sessionId, state));
            }
            events.add(RuntimeEvent.metadata(runId, sessionId, controlEvent.payload()));
            if (controlEvent.reroute()) {
                return List.copyOf(events);
            }
        }
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
                    "sourceType", "domain-agent-end"
            )));
        }
        if (!events.isEmpty()) {
            return List.copyOf(events);
        }
        RuntimeEvent.FallbackPayload payload = new RuntimeEvent.FallbackPayload(
                "domain-agent", "unknown", "event", "runtime", "debug", null,
                Map.of("sourcePayload", sanitizeDiagnostic(root)));
        return List.of(RuntimeEvent.fallback(runId, sessionId, payload));
    }

    private void addMetadataEvents(String runId, String sessionId, JsonNode root, List<ChatEvent> events) {
        if (root.hasNonNull("traceId")) {
            events.add(RuntimeEvent.metadata(runId, sessionId,
                    metadataPayload("trace", Map.of("traceId", text(root, "traceId")))));
        }
        if (root.hasNonNull("sessionId")) {
            String domainSessionId = text(root, "sessionId");
            events.add(RuntimeEvent.metadata(runId, sessionId, metadataPayload("domain_agent_session",
                    Map.of("domainAgentSessionId", domainSessionId, "runtimeSessionId", domainSessionId))));
        }
        if (root.hasNonNull("messageId")) {
            events.add(RuntimeEvent.metadata(runId, sessionId, metadataPayload("domain_agent_message",
                    Map.of("domainAgentMessageId", text(root, "messageId")))));
        }
        if ((root.hasNonNull("intent") || root.hasNonNull("skillId")) && !hasCardPayload(root)) {
            Map<String, Object> values = new LinkedHashMap<>();
            putIfPresent(values, "intent", text(root, "intent"));
            putIfPresent(values, "domainAgentId", text(root, "skillId"));
            events.add(RuntimeEvent.metadata(runId, sessionId, metadataPayload("domain_agent", values)));
        }
    }

    private void addStateEvent(String runId, String sessionId, JsonNode root, List<ChatEvent> events) {
        String state = text(root, "state");
        if (state == null || state.isBlank()) {
            return;
        }
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", "state");
        payload.put("state", normalized);
        putIfPresent(payload, "stateDesc", text(root, "stateDesc"));
        if ("THINKING".equals(normalized)) {
            payload.put("status", "STARTED");
            putIfPresent(payload, "text", text(root, "stateDesc"));
            events.add(RuntimeEvent.thinking(runId, sessionId, payload));
            return;
        }
        if ("GENERATE".equals(normalized)) {
            payload.put("stage", "GENERATE");
            putIfPresent(payload, "text", text(root, "stateDesc"));
            events.add(RuntimeEvent.progress(runId, sessionId, payload));
            return;
        }
        payload.put("eventKind", "state");
        events.add(RuntimeEvent.fallback(runId, sessionId, new RuntimeEvent.FallbackPayload(
                "domain-agent", normalized, "state", "runtime", "inline", text(root, "stateDesc"),
                payload)));
    }

    private void addStructuredEvents(String runId, String sessionId, JsonNode root, List<ChatEvent> events) {
        if (root.hasNonNull("processResult")) {
            events.add(RuntimeEvent.progress(runId, sessionId, processPayload(root)));
        }
        if (root.hasNonNull("searchList")) {
            events.add(RuntimeEvent.reference(runId, sessionId,
                    referencePayload("search_list", "searchList", root.get("searchList"))));
        }
        if (root.hasNonNull("sourcesDocuments")) {
            events.add(RuntimeEvent.reference(runId, sessionId,
                    referencePayload("source_documents", "sourcesDocuments", root.get("sourcesDocuments"))));
        }
        if (root.hasNonNull("sourceDocuments")) {
            events.add(RuntimeEvent.reference(runId, sessionId,
                    referencePayload("source_documents", "sourceDocuments", root.get("sourceDocuments"))));
        }
        if (hasCardPayload(root)) {
            events.add(RuntimeEvent.card(runId, sessionId, cardPayload(root)));
        }
    }

    private boolean hasCardPayload(JsonNode root) {
        return root.hasNonNull("cardUrl")
                || root.hasNonNull("diyCardScene")
                || root.hasNonNull("cardList")
                || root.hasNonNull("openCard")
                || root.hasNonNull("specificSceneInfo")
                || standaloneContentAgent(root);
    }

    private Map<String, Object> processPayload(JsonNode root) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", "processResult");
        payload.put("status", "STREAMING");
        payload.put("title", "思考过程");
        payload.put("processResult", sanitizeBusiness(root.get("processResult")));
        JsonNode processResult = root.get("processResult");
        JsonNode dynamicResponse = processResult == null ? null : processResult.get("dynamicResponse");
        if (dynamicResponse != null && dynamicResponse.isArray()) {
            payload.put("dynamicResponse", sanitizeBusiness(dynamicResponse));
            String text = firstDynamicTitle(dynamicResponse);
            if (text != null) {
                payload.put("text", text);
            }
        }
        return payload;
    }

    private Map<String, Object> referencePayload(String referenceType, String fieldName, JsonNode value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", fieldName);
        payload.put("referenceType", referenceType);
        payload.put("references", sanitizeBusiness(value));
        return payload;
    }

    private Map<String, Object> cardPayload(JsonNode root) {
        List<String> sources = cardSources(root);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", cardSourceType(sources));
        payload.put("cardType", cardType(sources));
        payload.put("cardSources", sources);
        putIfPresent(payload, "cardUrl", text(root, "cardUrl"));
        putIfPresent(payload, "openCard", text(root, "openCard"));
        putIfPresent(payload, "intent", text(root, "intent"));
        putIfPresent(payload, "domainAgentId", text(root, "skillId"));
        if (root.hasNonNull("diyCardScene")) {
            payload.put("diyCardScene", sanitizeBusiness(root.get("diyCardScene")));
            if (root.hasNonNull("contentAgent")) {
                payload.put("contentAgent", sanitizeBusiness(root.get("contentAgent")));
            }
        } else if (standaloneContentAgent(root)) {
            payload.put("contentAgent", sanitizeBusiness(root.get("contentAgent")));
        }
        if (root.hasNonNull("cardList")) {
            payload.put("cardList", sanitizeBusiness(root.get("cardList")));
        }
        if (root.hasNonNull("specificSceneInfo")) {
            payload.put("specificSceneInfo", sanitizeSpecificSceneInfo(root.get("specificSceneInfo")));
        }
        return payload;
    }

    private List<String> cardSources(JsonNode root) {
        List<String> sources = new ArrayList<>(5);
        if (root.hasNonNull("cardUrl")) {
            sources.add("cardUrl");
        }
        if (root.hasNonNull("diyCardScene")) {
            sources.add("diyCardScene");
        }
        if (root.hasNonNull("cardList")) {
            sources.add("cardList");
        }
        if (root.hasNonNull("openCard")) {
            sources.add("openCard");
        }
        if (root.hasNonNull("specificSceneInfo")) {
            sources.add("specificSceneInfo");
        }
        if (sources.isEmpty() && standaloneContentAgent(root)) {
            sources.add("contentAgent");
        }
        return List.copyOf(sources);
    }

    private boolean standaloneContentAgent(JsonNode root) {
        return root.hasNonNull("contentAgent")
                && !root.hasNonNull("cardUrl")
                && !root.hasNonNull("diyCardScene")
                && !root.hasNonNull("cardList")
                && !root.hasNonNull("openCard")
                && !root.hasNonNull("specificSceneInfo");
    }

    /**
     * 单一 DomainAgent 卡片字段保留原始字段名，方便前端按下游真实来源选择渲染器。
     * 多个卡片字段同帧到达时使用 domain-agent-card 作为聚合来源，并通过 cardSources 保留明细。
     */
    private String cardSourceType(List<String> sources) {
        return sources.size() == 1 ? sources.get(0) : "domain-agent-card";
    }

    private String cardType(List<String> sources) {
        if (sources.size() != 1) {
            return "mixed";
        }
        return switch (sources.get(0)) {
            case "cardUrl" -> "url";
            case "diyCardScene" -> "diyCardScene";
            case "cardList" -> "cardList";
            case "openCard" -> "openCard";
            case "specificSceneInfo" -> "specificSceneInfo";
            case "contentAgent" -> "contentAgent";
            default -> "domain-agent-card";
        };
    }

    private Map<String, Object> metadataPayload(String metadataType, Map<String, Object> values) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", metadataType);
        payload.put("metadataType", metadataType);
        values.forEach((key, value) -> {
            if (value != null) {
                payload.put(key, value);
            }
        });
        return payload;
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

    private List<ChatEvent> contentEvents(String runId, String sessionId, String content, DomainAgentStreamState state) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        DomainAgentStreamState streamState = state == null ? newStreamState() : state;
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

    private List<ChatEvent> flushPendingContent(String runId, String sessionId, DomainAgentStreamState state) {
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
                "sourceType", "domain-agent-content"
        )));
    }

    private void addThinkingText(String runId, String sessionId, List<ChatEvent> events, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        events.add(RuntimeEvent.thinking(runId, sessionId, Map.of(
                "source", "domain-agent",
                "sourceType", "content.think",
                "status", "STREAMING",
                "text", text
        )));
    }

    private RuntimeEvent thinkingBoundary(String runId, String sessionId, String status, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", "content.think");
        payload.put("status", status);
        if (text != null && !text.isBlank()) {
            payload.put("text", text);
        }
        return RuntimeEvent.thinking(runId, sessionId, payload);
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

    private Object sanitizeBusiness(JsonNode node) {
        return sanitizeBusiness(node, false);
    }

    private Object sanitizeSpecificSceneInfo(JsonNode node) {
        return sanitizeBusiness(node, true);
    }

    private Object sanitizeBusiness(JsonNode node, boolean preserveAuthorizationBusinessFields) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                Object value = sanitizeBusiness(
                        entry.getKey(), entry.getValue(), preserveAuthorizationBusinessFields);
                if (value != null) {
                    map.put(entry.getKey(), value);
                }
            });
            return ChatPayloadMaps.immutableCopy(map);
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode child : node) {
                Object value = sanitizeBusiness(child, preserveAuthorizationBusinessFields);
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
        return node.asText("");
    }

    private Object sanitizeBusiness(String field, JsonNode node, boolean preserveAuthorizationBusinessFields) {
        if (isSensitiveField(field)
                && !(preserveAuthorizationBusinessFields && authorizationBusinessField(field))) {
            return "[REDACTED]";
        }
        return sanitizeBusiness(node, preserveAuthorizationBusinessFields);
    }

    private boolean authorizationBusinessField(String field) {
        if (field == null) {
            return false;
        }
        String normalized = field.toLowerCase(Locale.ROOT);
        return "authorizationallowedflag".equals(normalized)
                || "authorizationpathstr".equals(normalized);
    }

    private Object sanitizeDiagnostic(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                Object value = isSensitiveField(entry.getKey())
                        ? "[REDACTED]"
                        : sanitizeDiagnostic(entry.getValue());
                if (value != null) {
                    map.put(entry.getKey(), value);
                }
            });
            return ChatPayloadMaps.immutableCopy(map);
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            int count = 0;
            for (JsonNode child : node) {
                if (count++ >= 50) {
                    list.add("[TRUNCATED]");
                    break;
                }
                Object value = sanitizeDiagnostic(child);
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

    private boolean isSensitiveField(String field) {
        if (field == null) {
            return false;
        }
        String normalized = field.toLowerCase(Locale.ROOT);
        return normalized.contains("cookie") || normalized.contains("authorization") || normalized.contains("token")
                || normalized.contains("secret") || normalized.contains("password")
                || normalized.contains("credential") || normalized.contains("apikey")
                || normalized.contains("api_key") || normalized.contains("access_key");
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

    private static int utf8Length(CharSequence value) {
        return value == null ? 0 : value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private void validateFrameSize(CharSequence frame) {
        validateFrameSize(utf8Length(frame));
    }

    private void validateFrameSize(int actualBytes) {
        if (actualBytes > maxPendingFrameBytes) {
            throw DomainAgentProtocolException.frameTooLarge(actualBytes, maxPendingFrameBytes);
        }
    }

    private record FrameExtraction(String frame) {
    }

    private record EventDelimiter(int index, int length) {
    }

    private static final class DomainAgentPendingFrame {
        private final StringBuilder content;
        private final JsonBoundaryScanner scanner;
        private int utf8Bytes;

        private DomainAgentPendingFrame(String initialContent) {
            this.content = new StringBuilder(initialContent);
            this.scanner = new JsonBoundaryScanner();
            this.utf8Bytes = utf8Length(initialContent);
            scanner.accept(initialContent, 0);
        }

        private StringBuilder content() {
            return content;
        }

        private JsonBoundaryScanner scanner() {
            return scanner;
        }

        private int append(String value) {
            content.append(value);
            int additionalBytes = utf8Length(value);
            utf8Bytes = additionalBytes > Integer.MAX_VALUE - utf8Bytes
                    ? Integer.MAX_VALUE
                    : utf8Bytes + additionalBytes;
            return utf8Bytes;
        }
    }

    /**
     * 增量 JSON 边界扫描器。
     *
     * <p>它只判断当前 frame 的顶层 JSON 值是否闭合。字符串和转义状态会跨 chunk 保留，
     * 因此 JSON 字符串里的花括号不会误触发完成。</p>
     */
    private static final class JsonBoundaryScanner {
        private boolean started;
        private boolean inString;
        private boolean escaping;
        private int depth;

        private int accept(String value, int offset) {
            if (value == null || value.isEmpty()) {
                return -1;
            }
            for (int i = Math.max(0, offset); i < value.length(); i++) {
                char c = value.charAt(i);
                if (!started) {
                    if (c == '{' || c == '[') {
                        started = true;
                        depth = 1;
                    }
                    continue;
                }
                if (inString) {
                    if (escaping) {
                        escaping = false;
                    } else if (c == '\\') {
                        escaping = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            return -1;
        }

    }

    /**
     * 单次 DomainAgent eventStream 的内容解析状态。
     */
    public static final class DomainAgentStreamState {
        private boolean inThinking;
        private String pending = "";
        private final StringBuilder frameBuffer = new StringBuilder();
        private DomainAgentPendingFrame pendingFrame;
    }
}
