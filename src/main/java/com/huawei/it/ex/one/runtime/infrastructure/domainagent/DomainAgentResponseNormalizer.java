package com.huawei.it.ex.one.runtime.infrastructure.domainagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.runtime.infrastructure.config.DomainAgentProperties;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.common.event.RuntimeEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * DomainAgent eventStream 响应归一化器。
 *
 * <p>DomainAgent 使用 {@code message: {...}} 这类私有流式帧。该组件把它转换成 ChatService
 * 标准事件，保证 WebSocket、Event Resume、message 与 parts 存储链路继续复用统一协议。</p>
 */
@Component
public class DomainAgentResponseNormalizer {
    private final ObjectMapper objectMapper;
    private final DomainAgentContentNormalizer contentNormalizer;
    private final DomainAgentPayloadSanitizer payloadSanitizer;
    private final DomainAgentResponseEventMapper eventMapper;
    private final int maxPendingFrameBytes;

    public DomainAgentResponseNormalizer(ObjectMapper objectMapper) {
        this(objectMapper, new DomainAgentProperties());
    }

    @Autowired
    public DomainAgentResponseNormalizer(ObjectMapper objectMapper, DomainAgentProperties properties) {
        this.objectMapper = objectMapper;
        this.contentNormalizer = new DomainAgentContentNormalizer();
        this.payloadSanitizer = new DomainAgentPayloadSanitizer();
        this.eventMapper = new DomainAgentResponseEventMapper(
                new DomainAgentControlEventMapper(), contentNormalizer, payloadSanitizer);
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
                    Map.of("raw", payloadSanitizer.truncate(remaining),
                            "reason", "domain-agent stream ended before current frame was closed"))));
        }
        if (!state.frameBuffer.isEmpty()) {
            String remaining = stripLeadingFramePrefix(state.frameBuffer.toString());
            if (!remaining.isBlank()) {
                events.add(RuntimeEvent.fallback(runId, sessionId, new RuntimeEvent.FallbackPayload(
                        "domain-agent", "invalid-json", "event", "runtime", "debug", null,
                        Map.of("raw", payloadSanitizer.truncate(remaining)))));
            }
            state.frameBuffer.setLength(0);
        }
        events.addAll(contentNormalizer.flush(runId, sessionId, state));
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
            if (ignoredStreamLine(trimmed)) {
                continue;
            }
            if (hasFramePrefix(trimmed)) {
                sawStreamPrefix = true;
                appendFrameValue(payload, valueAfterPrefix(trimmed));
            }
        }
        return sawStreamPrefix ? payload.toString() : segment.trim();
    }

    private boolean ignoredStreamLine(String value) {
        return value.isEmpty() || value.startsWith(":") || value.startsWith("event:")
                || value.startsWith("id:") || value.startsWith("retry:");
    }

    private void appendFrameValue(StringBuilder payload, String value) {
        if (value.isBlank()) {
            return;
        }
        if (!payload.isEmpty()) {
            payload.append('\n');
        }
        payload.append(value);
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
            return eventMapper.normalize(runId, sessionId, root, state);
        } catch (JsonProcessingException ex) {
            RuntimeEvent.FallbackPayload payload = new RuntimeEvent.FallbackPayload(
                    "domain-agent", "invalid-json", "event", "runtime", "debug", null,
                    Map.of("raw", payloadSanitizer.truncate(frame)));
            return List.of(RuntimeEvent.fallback(runId, sessionId, payload));
        }
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

        // String, escape and nesting transitions form one incremental JSON protocol state machine. Keeping the
        // transitions together preserves exact cross-buffer boundary detection.
        @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.CyclomaticComplexity"})
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
        boolean inThinking;
        String pending = "";
        private final StringBuilder frameBuffer = new StringBuilder();
        private DomainAgentPendingFrame pendingFrame;
    }
}
