package com.huawei.finance.front.one.infrastructure.agent.runtime.relay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Relay WebSocket 文本帧翻译器。
 *
 * <p>RelayAgent 的 WebSocket 对话接口可能返回标准 JSON 帧，也可能短期内返回纯文本 delta。
 * 为了让接入更稳，本翻译器同时支持两类输入：非 JSON 文本直接视为 {@code message.delta}；
 * JSON 帧则根据 {@code type/status/event} 与 {@code delta/content/message} 字段映射为标准 ChatEvent。</p>
 */
@Component
public class RelayWebSocketFrameTranslator {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public RelayWebSocketFrameTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将 Relay WebSocket 文本帧转换为标准聊天事件。
     *
     * @param runId 本轮 SuperAgent run 标识。
     * @param sessionId 前端聊天会话标识。
     * @param frame Relay WebSocket 返回的一帧文本。
     * @return 该帧对应的聊天事件列表；空白帧返回空列表。
     */
    public List<ChatEvent> translate(String runId, String sessionId, String frame) {
        if (frame == null || frame.isBlank()) {
            return List.of();
        }
        String text = frame.trim();
        try {
            JsonNode root = objectMapper.readTree(text);
            return translateJson(runId, sessionId, root);
        } catch (JsonProcessingException ex) {
            return List.of(MessageDeltaEvent.of(runId, sessionId, frame));
        }
    }

    private List<ChatEvent> translateJson(String runId, String sessionId, JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return List.of();
        }
        if (root.isTextual()) {
            return List.of(MessageDeltaEvent.of(runId, sessionId, root.asText()));
        }
        if (!root.isObject()) {
            return List.of(MessageDeltaEvent.of(runId, sessionId, root.toString()));
        }
        String type = firstText(root, "type", "event", "status");
        if (isError(type)) {
            throw new RelayRuntimeProtocolException(firstText(root, "message", "error", "reason"));
        }
        if (isCompleted(type)) {
            return List.of(MessageCompletedEvent.of(runId, sessionId, payload(root)));
        }
        String delta = firstText(root, "delta", "content", "message", "text");
        if (delta == null || delta.isBlank()) {
            return List.of();
        }
        return List.of(deltaEvent(runId, sessionId, delta, root));
    }

    private ChatEvent deltaEvent(String runId, String sessionId, String delta, JsonNode root) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("delta", delta);
        copyIfPresent(root, payload, "runtimeSessionId");
        copyIfPresent(root, payload, "agentSessionId");
        return new MessageDeltaEvent(runId, sessionId, 0, Instant.now(), delta, Map.copyOf(payload));
    }

    private Map<String, Object> payload(JsonNode root) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "MESSAGE_COMPLETED");
        try {
            payload.putAll(objectMapper.convertValue(root, MAP_TYPE));
        } catch (IllegalArgumentException ignored) {
            // 已有标准 status 字段即可完成事件闭合。
        }
        return Map.copyOf(payload);
    }

    private void copyIfPresent(JsonNode root, Map<String, Object> payload, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value != null && !value.isNull()) {
            payload.put(fieldName, value.isValueNode() ? value.asText() : value.toString());
        }
    }

    private String firstText(JsonNode root, String... fieldNames) {
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

    private boolean isCompleted(String type) {
        if (type == null) {
            return false;
        }
        String normalized = type.trim().toLowerCase();
        return "message.completed".equals(normalized)
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
                || "error".equals(normalized)
                || "failed".equals(normalized);
    }
}
