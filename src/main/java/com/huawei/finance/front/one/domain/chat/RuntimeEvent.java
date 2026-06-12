package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 下游 Runtime 运行态扩展事件。
 *
 * <p>{@code runtime.event} 用于承载 Relay/AgentRuntime 的非 assistant 正文事件，例如进度、
 * 思考、工作区路径、工具状态等。它保留稳定的 ChatService 外层事件类型，同时允许下游版本演进
 * 带来新的业务事件。该事件不参与 assistant 历史消息拼接。</p>
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param sequence 事件持久化后的恢复游标序号，由 openGauss 事实源生成。
 * @param createdAt 事件创建时间。
 * @param payload 前端可消费的运行态事件载荷。
 */
public record RuntimeEvent(
        String runId,
        String sessionId,
        long sequence,
        Instant createdAt,
        String eventType,
        Map<String, Object> payload
) implements ChatEvent {
    public RuntimeEvent(String runId, String sessionId, long sequence, Instant createdAt, Map<String, Object> payload) {
        this(runId, sessionId, sequence, createdAt, "runtime.event", payload);
    }

    public static RuntimeEvent relay(String runId, String sessionId, String sourceType, String eventKind,
                                     String channel, String displayHint, String text,
                                     Map<String, Object> sourcePayload) {
        return fallback(runId, sessionId, sourceType, eventKind, channel, displayHint, text, sourcePayload);
    }

    public static RuntimeEvent progress(String runId, String sessionId, Map<String, Object> payload) {
        return typed("runtime.progress", runId, sessionId, payload);
    }

    public static RuntimeEvent metadata(String runId, String sessionId, Map<String, Object> payload) {
        return typed("runtime.metadata", runId, sessionId, payload);
    }

    public static RuntimeEvent agent(String runId, String sessionId, Map<String, Object> payload) {
        return typed("runtime.agent", runId, sessionId, payload);
    }

    public static RuntimeEvent thinking(String runId, String sessionId, Map<String, Object> payload) {
        return typed("runtime.thinking", runId, sessionId, payload);
    }

    public static RuntimeEvent tool(String runId, String sessionId, Map<String, Object> payload) {
        return typed("runtime.tool", runId, sessionId, payload);
    }

    public static RuntimeEvent reference(String runId, String sessionId, Map<String, Object> payload) {
        return typed("runtime.reference", runId, sessionId, payload);
    }

    public static RuntimeEvent card(String runId, String sessionId, Map<String, Object> payload) {
        return typed("runtime.card", runId, sessionId, payload);
    }

    public static RuntimeEvent fallback(String runId, String sessionId, String sourceType, String eventKind,
                                        String channel, String displayHint, String text,
                                        Map<String, Object> sourcePayload) {
        return fallback("relay", runId, sessionId, sourceType, eventKind, channel, displayHint, text, sourcePayload);
    }

    public static RuntimeEvent fallback(String source, String runId, String sessionId, String sourceType, String eventKind,
                                        String channel, String displayHint, String text,
                                        Map<String, Object> sourcePayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", blankToDefault(source, "runtime"));
        payload.put("sourceType", blankToDefault(sourceType, "unknown"));
        payload.put("eventKind", blankToDefault(eventKind, "event"));
        payload.put("channel", blankToDefault(channel, "runtime"));
        payload.put("displayHint", blankToDefault(displayHint, "runtime"));
        if (text != null && !text.isBlank()) {
            payload.put("text", text);
        }
        payload.put("sourcePayload", sourcePayload == null ? Map.of() : sourcePayload);
        return typed("runtime.event", runId, sessionId, payload);
    }

    private static RuntimeEvent typed(String eventType, String runId, String sessionId, Map<String, Object> payload) {
        Map<String, Object> nextPayload = payload == null ? Map.of() : Map.copyOf(payload);
        return new RuntimeEvent(runId, sessionId, 0, Instant.now(), eventType, nextPayload);
    }

    @Override
    public String type() {
        return eventType == null || eventType.isBlank() ? "runtime.event" : eventType;
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
