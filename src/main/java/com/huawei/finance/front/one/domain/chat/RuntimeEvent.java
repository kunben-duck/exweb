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
 * @param sequence 事件持久化后的恢复游标序号，由数据库事实源生成。
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

    public static RuntimeEvent relay(String runId, String sessionId, FallbackPayload fallbackPayload) {
        return fallback(runId, sessionId, fallbackPayload);
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

    public static RuntimeEvent fallback(String runId, String sessionId, FallbackPayload fallbackPayload) {
        FallbackPayload safePayload = fallbackPayload == null ? FallbackPayload.empty() : fallbackPayload;
        return buildFallback(runId, sessionId, safePayload.withDefaultSource("relay"));
    }

    public static RuntimeEvent fallback(String runId, String sessionId, FallbackPayload fallbackPayload,
                                        String defaultSource) {
        FallbackPayload safePayload = fallbackPayload == null ? FallbackPayload.empty() : fallbackPayload;
        return buildFallback(runId, sessionId, safePayload.withDefaultSource(defaultSource));
    }

    private static RuntimeEvent buildFallback(String runId, String sessionId, FallbackPayload fallbackPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", blankToDefault(fallbackPayload.source(), "runtime"));
        payload.put("sourceType", blankToDefault(fallbackPayload.sourceType(), "unknown"));
        payload.put("eventKind", blankToDefault(fallbackPayload.eventKind(), "event"));
        payload.put("channel", blankToDefault(fallbackPayload.channel(), "runtime"));
        payload.put("displayHint", blankToDefault(fallbackPayload.displayHint(), "runtime"));
        if (fallbackPayload.text() != null && !fallbackPayload.text().isBlank()) {
            payload.put("text", fallbackPayload.text());
        }
        payload.put("sourcePayload", fallbackPayload.sourcePayload() == null ? Map.of() : fallbackPayload.sourcePayload());
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

    /**
     * runtime.event 兜底事件载荷。
     *
     * <p>Relay/Legacy 等下游协议可能持续增加非正文事件，该对象集中表达这些事件的展示语义，
     * 避免 fallback 工厂方法出现一长串容易传错顺序的字符串参数。</p>
     */
    public record FallbackPayload(String source, String sourceType, String eventKind, String channel,
                                  String displayHint, String text, Map<String, Object> sourcePayload) {
        public static FallbackPayload empty() {
            return new FallbackPayload(null, null, null, null, null, null, Map.of());
        }

        private FallbackPayload withDefaultSource(String defaultSource) {
            return source == null || source.isBlank()
                    ? new FallbackPayload(defaultSource, sourceType, eventKind, channel, displayHint, text, sourcePayload)
                    : this;
        }
    }
}
