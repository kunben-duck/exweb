package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.domain.chat.MessageSnapshotEvent;
import com.huawei.finance.front.one.domain.chat.RuntimeEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Relay Runtime 响应归一化器。
 *
 * <p>Relay 下游可能返回纯文本、JSON chunk 或 SSE-like {@code data: ...} 片段。本组件把这些
 * 私有协议统一转换成 ChatService 标准 ChatEvent，确保前端只消费稳定的
 * {@code message.delta/message.snapshot/message.completed/runtime.*} 语义，不接触下游原始响应体。</p>
 */
@Component
public class RelayRuntimeResponseNormalizer {
    private static final int MAX_SOURCE_PAYLOAD_DEPTH = 6;
    private static final int MAX_SOURCE_PAYLOAD_STRING_LENGTH = 2048;
    private static final int MAX_SOURCE_PAYLOAD_ARRAY_SIZE = 50;
    private static final String REDACTED = "[REDACTED]";

    private final ObjectMapper objectMapper;
    private final RelayRuntimeMappingProperties mappingProperties;

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
        String type = firstText(root, "type", "event", "status");
        String normalizedType = normalizeTypeName(type);
        if (isHeartbeat(type, root)) {
            return List.of();
        }
        if (hasError(root) || isError(type)) {
            throw new RelayRuntimeProtocolException(errorMessage(root));
        }
        if ("tool-structured-result".equals(normalizedType)) {
            return toolStructuredResultEvents(runId, sessionId, root);
        }
        ChatEvent runtimeEvent = mappedRuntimeEvent(runId, sessionId, root, type, normalizedType);
        if (runtimeEvent != null) {
            return List.of(runtimeEvent);
        }
        if (isCompleted(type) || hasFinishReason(root)) {
            String delta = extractAnswerDelta(root, type);
            if (delta == null || delta.isBlank()) {
                return List.of(MessageCompletedEvent.of(runId, sessionId, completionPayload(root)));
            }
            return List.of(deltaEvent(runId, sessionId, delta, root),
                    MessageCompletedEvent.of(runId, sessionId, completionPayload(root)));
        }
        String snapshot = extractAnswerSnapshot(root, type);
        if (snapshot != null) {
            return List.of(snapshotEvent(runId, sessionId, snapshot, root));
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
            return List.of(fallbackRuntimeEvent(runId, sessionId, root, type));
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
        payload.put("sourceType", blankToDefault(firstText(root, "type", "event", "status"), "unknown"));
        copyText(root, payload, "runtimeSessionId", RUNTIME_SESSION_FIELDS);
        copyText(root, payload, "agentName", AGENT_NAME_FIELDS);
        copyText(root, payload, "agentSessionId", "agentSessionId", "agent_session_id");
        copyAny(root, payload, "timestamp", "timestamp", "time", "created_at");
        return new MessageDeltaEvent(runId, sessionId, 0, Instant.now(), delta, Map.copyOf(payload));
    }

    private ChatEvent snapshotEvent(String runId, String sessionId, String content, JsonNode root) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("sourceType", blankToDefault(firstText(root, "type", "event", "status"), "unknown"));
        copyText(root, payload, "runtimeSessionId", RUNTIME_SESSION_FIELDS);
        copyText(root, payload, "agentName", AGENT_NAME_FIELDS);
        copyText(root, payload, "agentSessionId", "agentSessionId", "agent_session_id");
        copyAny(root, payload, "timestamp", "timestamp", "time", "created_at");
        return new MessageSnapshotEvent(runId, sessionId, 0, Instant.now(), content, Map.copyOf(payload));
    }

    private Map<String, Object> completionPayload(JsonNode root) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "MESSAGE_COMPLETED");
        copyText(root, payload, "runtimeSessionId", RUNTIME_SESSION_FIELDS);
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

    private ChatEvent mappedRuntimeEvent(String runId, String sessionId, JsonNode root,
                                         String sourceType, String normalizedType) {
        return switch (normalizedType) {
            case "relay-start", "relay-end" -> RuntimeEvent.progress(runId, sessionId,
                    relayLifecyclePayload(root, sourceType));
            case "relay-progress" -> RuntimeEvent.progress(runId, sessionId, progressPayload(root, sourceType));
            case "project-home" -> RuntimeEvent.metadata(runId, sessionId, projectHomePayload(root, sourceType));
            case "available-modes", "availbale-modes" ->
                    RuntimeEvent.metadata(runId, sessionId, availableModesPayload(root, sourceType));
            case "agent-call" -> RuntimeEvent.agent(runId, sessionId, agentCallPayload(root, sourceType));
            case "agent-reasoning" -> RuntimeEvent.thinking(runId, sessionId,
                    agentReasoningPayload(root, sourceType));
            case "thinking-operation-start", "thinkink-operation-start" ->
                    RuntimeEvent.thinking(runId, sessionId, thinkingPayload(root, sourceType, "STARTED"));
            case "thinking-content-update" -> RuntimeEvent.thinking(runId, sessionId,
                    thinkingContentPayload(root, sourceType));
            case "thinking-operation-end", "thinking-operation-finish" ->
                    RuntimeEvent.thinking(runId, sessionId, thinkingPayload(root, sourceType, "ENDED"));
            case "tool-call-streaming" -> RuntimeEvent.tool(runId, sessionId, toolPayload(root, sourceType));
            case "tool-execution" -> RuntimeEvent.tool(runId, sessionId, toolExecutionPayload(root, sourceType));
            case "session-state" -> RuntimeEvent.metadata(runId, sessionId, sessionStatePayload(root, sourceType));
            case "generate-response", "clarified-query" ->
                    RuntimeEvent.progress(runId, sessionId, progressPayload(root, sourceType));
            case "self-evolution-status" -> RuntimeEvent.metadata(runId, sessionId,
                    selfEvolutionPayload(root, sourceType));
            case "url-moderation", "url-moderation-result" ->
                    RuntimeEvent.reference(runId, sessionId, urlModerationReferencePayload(root, sourceType));
            case "search-result-groups", "content-references", "citations", "sources", "references", "safe-urls" ->
                    RuntimeEvent.reference(runId, sessionId, genericReferencePayload(root, sourceType));
            default -> null;
        };
    }

    private List<ChatEvent> toolStructuredResultEvents(String runId, String sessionId, JsonNode root) {
        JsonNode result = firstNode(root, "result_data", "resultData");
        JsonNode data = toolStructuredData(result);
        if (data == null || data.isNull() || data.isMissingNode()) {
            return List.of(toolStructuredFallbackEvent(runId, sessionId, root));
        }
        Map<String, Object> context = toolStructuredContext(root, result);
        List<ChatEvent> events = new ArrayList<>();
        String content = firstText(data, "content");
        if (content != null) {
            events.add(toolStructuredDeltaEvent(runId, sessionId, content, context));
        }
        JsonNode processResult = firstNode(data, "processResult");
        if (processResult != null) {
            events.add(RuntimeEvent.progress(runId, sessionId,
                    toolStructuredProgressPayload(data, processResult, context)));
        }
        JsonNode searchList = firstNode(data, "searchList", "SearchList");
        if (searchList != null) {
            events.add(RuntimeEvent.reference(runId, sessionId,
                    toolStructuredReferencePayload("relay-searchList", "search_list",
                            "searchList", searchList, context)));
        }
        JsonNode sourcesDocuments = firstNode(data, "sourcesDocuments", "sourceDocuments",
                "SourcesDocuments", "SourceDocuments");
        if (sourcesDocuments != null) {
            String sourceType = data.has("sourceDocuments") || data.has("SourceDocuments")
                    ? "relay-sourceDocuments"
                    : "relay-sourcesDocuments";
            events.add(RuntimeEvent.reference(runId, sessionId,
                    toolStructuredReferencePayload(sourceType, "source_documents",
                            "sourcesDocuments", sourcesDocuments, context)));
        }
        if (hasToolStructuredCardPayload(data)) {
            events.add(RuntimeEvent.card(runId, sessionId, toolStructuredCardPayload(data, context)));
        }
        if (events.isEmpty()) {
            events.add(toolStructuredFallbackEvent(runId, sessionId, root));
        }
        return List.copyOf(events);
    }

    private JsonNode toolStructuredData(JsonNode result) {
        if (result == null || result.isNull() || result.isMissingNode()) {
            return null;
        }
        JsonNode widget = result.get("widget");
        if (widget != null && widget.isObject()) {
            JsonNode widgetData = widget.get("data");
            if (widgetData != null && !widgetData.isNull()) {
                return widgetData;
            }
        }
        JsonNode data = result.get("data");
        return data == null || data.isNull() ? result : data;
    }

    private Map<String, Object> toolStructuredContext(JsonNode root, JsonNode result) {
        Map<String, Object> context = new LinkedHashMap<>();
        copyText(root, context, "agentName", AGENT_NAME_FIELDS);
        copyText(root, context, "toolId", "tool_id", "toolId", "tool-id");
        copyText(root, context, "parentInstanceId", "parent_instance_id", "parentInstanceId", "parent-instance-id");
        copyText(root, context, "runtimeSessionId", RUNTIME_SESSION_FIELDS);
        copyAny(root, context, "timestamp", "timestamp", "time", "created_at");
        if (result != null) {
            copyAny(result, context, "index", "index");
            copyAny(result, context, "total", "total");
            copyBoolean(result, context, "isLast", "is_last", "isLast");
        }
        return Map.copyOf(context);
    }

    private ChatEvent toolStructuredDeltaEvent(String runId, String sessionId, String content,
                                               Map<String, Object> context) {
        Map<String, Object> payload = new LinkedHashMap<>(context);
        payload.put("delta", content);
        payload.put("sourceType", "relay-content");
        return new MessageDeltaEvent(runId, sessionId, 0, Instant.now(), content, Map.copyOf(payload));
    }

    private Map<String, Object> toolStructuredProgressPayload(JsonNode data, JsonNode processResult,
                                                              Map<String, Object> context) {
        Map<String, Object> payload = basePayload("relay-processResult");
        payload.putAll(context);
        payload.put("status", "STREAMING");
        payload.put("title", "思考过程");
        payload.put("processResult", sanitizeJson(processResult, "processResult", 0));
        JsonNode dynamicResponse = firstNode(processResult, "dynamicResponse", "dynamic_response");
        if (dynamicResponse != null) {
            payload.put("dynamicResponse", sanitizeJson(dynamicResponse, "dynamicResponse", 0));
            String text = firstDynamicResponseText(dynamicResponse);
            if (text != null) {
                payload.put("text", text);
            }
        }
        return Map.copyOf(payload);
    }

    private Map<String, Object> toolStructuredReferencePayload(String sourceType, String referenceType,
                                                               String fieldName, JsonNode references,
                                                               Map<String, Object> context) {
        Map<String, Object> payload = basePayload(sourceType);
        payload.putAll(context);
        payload.put("referenceType", referenceType);
        payload.put("references", sanitizeJson(references, fieldName, 0));
        return Map.copyOf(payload);
    }

    private boolean hasToolStructuredCardPayload(JsonNode data) {
        return firstNode(data, "cardUrl", "diyCardScene", "cardList", "openCard") != null;
    }

    private Map<String, Object> toolStructuredCardPayload(JsonNode data, Map<String, Object> context) {
        List<String> sources = toolStructuredCardSources(data);
        String sourceType = sources.size() == 1 ? "relay-" + sources.getFirst() : "relay-card";
        Map<String, Object> payload = basePayload(sourceType);
        payload.putAll(context);
        payload.put("cardType", sources.size() == 1 ? cardType(sources.getFirst()) : "mixed");
        payload.put("cardSources", sources.stream().map(source -> "relay-" + source).toList());
        copyText(data, payload, "cardUrl", "cardUrl");
        copyText(data, payload, "openCard", "openCard");
        copyText(data, payload, "intent", "intent");
        copyText(data, payload, "skillId", "skillId", "skill_id");
        copyAny(data, payload, "diyCardScene", "diyCardScene");
        copyAny(data, payload, "cardList", "cardList");
        return Map.copyOf(payload);
    }

    private List<String> toolStructuredCardSources(JsonNode data) {
        List<String> sources = new ArrayList<>();
        for (String field : List.of("cardUrl", "diyCardScene", "cardList", "openCard")) {
            JsonNode value = data.get(field);
            if (value != null && !value.isNull()) {
                sources.add(field);
            }
        }
        return List.copyOf(sources);
    }

    private String cardType(String source) {
        return "cardUrl".equals(source) ? "url" : source;
    }

    private String firstDynamicResponseText(JsonNode dynamicResponse) {
        if (dynamicResponse == null || !dynamicResponse.isArray() || dynamicResponse.isEmpty()) {
            return null;
        }
        for (JsonNode item : dynamicResponse) {
            String text = firstText(item, "title", "titile", "text", "content");
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private RuntimeEvent toolStructuredFallbackEvent(String runId, String sessionId, JsonNode root) {
        return RuntimeEvent.fallback(runId, sessionId, new RuntimeEvent.FallbackPayload(
                "relay", "relay-tool-structured-result", "event", "runtime", "runtime",
                null, sourcePayload(root)));
    }

    private RuntimeEvent fallbackRuntimeEvent(String runId, String sessionId, JsonNode root, String type) {
        String sourceType = type == null || type.isBlank() ? "unknown" : type.trim();
        String channel = runtimeChannel(sourceType);
        String text = firstText(root, "displayText", "display_text", "title", "description");
        if (text == null && isRuntimeTextSafe(sourceType)) {
            text = firstText(root, "message", "text");
        }
        return RuntimeEvent.fallback(runId, sessionId, new RuntimeEvent.FallbackPayload(
                "relay", sourceType, runtimeEventKind(sourceType), channel, channel, text, sourcePayload(root)));
    }

    private Map<String, Object> progressPayload(JsonNode root, String sourceType) {
        Map<String, Object> payload = basePayload(sourceType);
        copyText(root, payload, "text", "content", "message", "text");
        copyText(root, payload, "runtimeSessionId", RUNTIME_SESSION_FIELDS);
        copyAny(root, payload, "timestamp", "timestamp", "time", "created_at");
        return Map.copyOf(payload);
    }

    private Map<String, Object> relayLifecyclePayload(JsonNode root, String sourceType) {
        Map<String, Object> next = new LinkedHashMap<>(progressPayload(root, sourceType));
        copyText(root, next, "status", "status");
        copyAny(root, next, "mcpStatus", "mcp_status", "mcpStatus");
        copyAny(root, next, "initialization", "is_initialization", "isInitialization");
        return Map.copyOf(next);
    }

    private Map<String, Object> projectHomePayload(JsonNode root, String sourceType) {
        Map<String, Object> payload = basePayload(sourceType);
        payload.put("metadataType", "project_home");
        copyText(root, payload, "projectHome", "project_home", "projectHome");
        copyAny(root, payload, "timestamp", "timestamp", "time", "created_at");
        return Map.copyOf(payload);
    }

    private Map<String, Object> availableModesPayload(JsonNode root, String sourceType) {
        Map<String, Object> payload = basePayload(sourceType);
        payload.put("metadataType", "available_modes");
        JsonNode modes = root.get("modes");
        if (modes != null && modes.isArray()) {
            List<Object> normalizedModes = new ArrayList<>();
            for (JsonNode mode : modes) {
                if (mode != null && mode.isObject()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    copyText(mode, item, "value", "value");
                    copyText(mode, item, "label", "label", "lable");
                    copyText(mode, item, "description", "description");
                    copyText(mode, item, "source", "source");
                    normalizedModes.add(Map.copyOf(item));
                }
            }
            payload.put("modes", List.copyOf(normalizedModes));
        }
        copyAny(root, payload, "timestamp", "timestamp", "time", "created_at");
        return Map.copyOf(payload);
    }

    private Map<String, Object> agentCallPayload(JsonNode root, String sourceType) {
        Map<String, Object> payload = basePayload(sourceType);
        copyText(root, payload, "agentName", AGENT_NAME_FIELDS);
        copyBoolean(root, payload, "started", "started", "istart", "isStart", "is_start");
        copyText(root, payload, "task", "task");
        copyText(root, payload, "modelName", "modelName", "modelname", "model_name");
        copyText(root, payload, "agentId", "agent_id", "agentId");
        copyText(root, payload, "instanceId", "instance_id", "instanceId");
        copyText(root, payload, "parentId", "parent_id", "parentId");
        copyAny(root, payload, "endTimestamp", "end_timestamp", "endTimestamp");
        copyText(root, payload, "runtimeSessionId", RUNTIME_SESSION_FIELDS);
        copyAny(root, payload, "timestamp", "timestamp", "time", "created_at");
        return Map.copyOf(payload);
    }

    private Map<String, Object> agentReasoningPayload(JsonNode root, String sourceType) {
        Map<String, Object> payload = basePayload(sourceType);
        copyText(root, payload, "agentName", AGENT_NAME_FIELDS);
        copyText(root, payload, "text", "thought", "content", "text");
        copyBoolean(root, payload, "started", "is_start", "isStart", "started");
        payload.put("status", Boolean.TRUE.equals(payload.get("started")) ? "STARTED" : "ENDED");
        copyAny(root, payload, "timestamp", "timestamp", "time", "created_at");
        return Map.copyOf(payload);
    }

    private Map<String, Object> thinkingPayload(JsonNode root, String sourceType, String status) {
        Map<String, Object> payload = basePayload(sourceType);
        payload.put("status", status);
        copyText(root, payload, "operationId", "operationId", "operation_id");
        copyText(root, payload, "agentName", AGENT_NAME_FIELDS);
        copyText(root, payload, "instanceId", "instance_id", "instanceId");
        copyText(root, payload, "modelId", "model_id", "modelId");
        JsonNode tools = firstNode(root, "availableTools", "available_tools", "availbale_tools");
        if (tools != null && tools.isArray()) {
            payload.put("availableTools", sanitizeJson(tools, "availableTools", 0));
        }
        copyAny(root, payload, "inputTokens", "input_tokens", "inputTokens");
        copyAny(root, payload, "outputTokens", "output_tokens", "outputTokens");
        copyAny(root, payload, "reasoningTokens", "reasoning_tokens", "reasoningTokens");
        copyAny(root, payload, "cachedTokens", "cached_tokens", "cachedTokens");
        copyAny(root, payload, "toolCallCount", "tool_call_count", "toolCallCount");
        copyAny(root, payload, "toolCallIds", "tool_call_ids", "toolCallIds");
        copyText(root, payload, "reasoningContent", "reasoning_content", "reasoningContent");
        copyText(root, payload, "output", "output");
        copyAny(root, payload, "endTimestamp", "end_timestamp", "endTimestamp");
        copyAny(root, payload, "timestamp", "timestamp", "time", "created_at");
        return Map.copyOf(payload);
    }

    private Map<String, Object> thinkingContentPayload(JsonNode root, String sourceType) {
        Map<String, Object> payload = basePayload(sourceType);
        payload.put("status", "STREAMING");
        copyText(root, payload, "operationId", "operationId", "operation_id");
        copyText(root, payload, "agentName", AGENT_NAME_FIELDS);
        copyText(root, payload, "text", "content", "thought", "text");
        copyAny(root, payload, "timestamp", "timestamp", "time", "created_at");
        return Map.copyOf(payload);
    }

    private Map<String, Object> toolPayload(JsonNode root, String sourceType) {
        Map<String, Object> payload = basePayload(sourceType);
        payload.put("status", "STREAMING");
        copyText(root, payload, "agentName", AGENT_NAME_FIELDS);
        copyText(root, payload, "toolName", "toolName", "tool_name", "too_name", "tool-name");
        copyText(root, payload, "toolId", "tool_id", "toolId", "tool-id");
        copyText(root, payload, "operationId", "operation_id", "operationId");
        copyText(root, payload, "inputPreview", "inputPreview", "input_preview");
        copyAny(root, payload, "timestamp", "timestamp", "time", "created_at");
        return Map.copyOf(payload);
    }

    private Map<String, Object> toolExecutionPayload(JsonNode root, String sourceType) {
        Map<String, Object> payload = basePayload(sourceType);
        boolean started = booleanValue(firstNode(root, "is_start", "isStart", "started"));
        payload.put("status", started ? "STARTED" : "ENDED");
        payload.put("started", started);
        copyText(root, payload, "agentName", AGENT_NAME_FIELDS);
        copyText(root, payload, "toolName", "tool_name", "toolName", "tool-name");
        copyText(root, payload, "toolId", "tool_id", "toolId", "tool-id");
        copyText(root, payload, "displayName", "display_name", "displayName");
        copyText(root, payload, "parentInstanceId", "parent_instance_id", "parentInstanceId");
        copyText(root, payload, "modelId", "model_id", "modelId");
        copyText(root, payload, "thinkingOperationId", "thinking_operation_id", "thinkingOperationId");
        copyText(root, payload, "argsSummary", "args_summary", "argsSummary");
        copyText(root, payload, "resultSummary", "result_summary", "resultSummary");
        copyText(root, payload, "runtimeSessionId", RUNTIME_SESSION_FIELDS);
        copyAny(root, payload, "timestamp", "timestamp", "time", "created_at");
        return Map.copyOf(payload);
    }

    private Map<String, Object> sessionStatePayload(JsonNode root, String sourceType) {
        Map<String, Object> payload = basePayload(sourceType);
        payload.put("metadataType", "session_state");
        copyText(root, payload, "state", "state");
        copyText(root, payload, "detail", "detail", "message", "content");
        copyText(root, payload, "runtimeSessionId", RUNTIME_SESSION_FIELDS);
        copyAny(root, payload, "timestamp", "timestamp", "time", "created_at");
        return Map.copyOf(payload);
    }

    private Map<String, Object> selfEvolutionPayload(JsonNode root, String sourceType) {
        Map<String, Object> payload = basePayload(sourceType);
        payload.put("metadataType", "self_evolution_status");
        copyAny(root, payload, "data", "data");
        copyAny(root, payload, "timestamp", "timestamp", "time", "created_at");
        return Map.copyOf(payload);
    }

    private Map<String, Object> urlModerationReferencePayload(JsonNode root, String sourceType) {
        Map<String, Object> payload = basePayload(sourceType);
        payload.put("referenceType", "url_moderation");
        JsonNode result = firstNode(root, "url_moderation_result", "urlModerationResult", "result", "data");
        JsonNode valueRoot = result == null || !result.isObject() ? root : result;
        copyText(valueRoot, payload, "url", "full_url", "fullUrl", "url");
        copyText(valueRoot, payload, "title", "title", "name");
        copyAny(valueRoot, payload, "safe", "is_safe", "isSafe", "safe");
        copyAny(valueRoot, payload, "blocked", "is_blocked", "isBlocked", "blocked");
        copyText(valueRoot, payload, "messageId", "message_id", "messageId");
        payload.put("sourcePayload", sourcePayload(root));
        return Map.copyOf(payload);
    }

    private Map<String, Object> genericReferencePayload(JsonNode root, String sourceType) {
        Map<String, Object> payload = basePayload(sourceType);
        payload.put("referenceType", "source");
        copyText(root, payload, "title", "title", "name", "label");
        copyText(root, payload, "url", "url", "href", "link");
        copyText(root, payload, "text", "text", "snippet", "summary", "content");
        JsonNode references = firstNode(root, "references", "citations", "sources", "safe_urls",
                "safeUrls", "content_references", "contentReferences", "search_result_groups",
                "searchResultGroups");
        if (references != null) {
            payload.put("references", sanitizeJson(references, "references", 0));
        }
        payload.put("sourcePayload", sourcePayload(root));
        return Map.copyOf(payload);
    }

    private Map<String, Object> basePayload(String sourceType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "relay");
        payload.put("sourceType", blankToDefault(sourceType, "unknown"));
        return payload;
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
        String normalized = type.trim().toLowerCase();
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

    private void copyAny(JsonNode root, Map<String, Object> payload, String target, String... sourceNames) {
        JsonNode value = firstNode(root, sourceNames);
        if (value != null && !value.isNull()) {
            payload.put(target, sanitizeJson(value, target, 0));
        }
    }

    private void copyBoolean(JsonNode root, Map<String, Object> payload, String target, String... sourceNames) {
        JsonNode value = firstNode(root, sourceNames);
        if (value != null && value.isBoolean()) {
            payload.put(target, value.booleanValue());
        } else if (value != null && value.isTextual()) {
            payload.put(target, Boolean.parseBoolean(value.asText()));
        }
    }

    private boolean booleanValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.asInt(0) != 0;
        }
        if (value.isTextual()) {
            String text = value.asText("").trim();
            return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
        }
        return false;
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

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    static String normalizeTypeName(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase()
                .replace('—', '-')
                .replace('_', '-');
    }

    private static final String[] RUNTIME_SESSION_FIELDS = {
            "runtimeSessionId", "runtime_session_id", "session_id", "session-id", "session—id",
            "sessionId", "instansid", "instanceId", "instance_id"
    };

    private static final String[] AGENT_NAME_FIELDS = {
            "agentName", "agent_name", "agent-name", "agentname"
    };
}
