package com.huawei.it.ex.one.chat.domain;

import com.huawei.it.ex.one.common.event.ChatPayloadMaps;
import java.time.Instant;
import java.util.Map;

/**
 * assistant 历史消息的结构化组成部分。
 *
 * <p>{@link ChatMessage#content()} 只保存最终回答正文；思考、工具调用、进度和 Runtime 元数据
 * 等过程信息保存在 message part 中，供前端刷新会话后还原运行过程。</p>
 *
 * @param id part 主键。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param sessionId 所属会话标识。
 * @param messageId 所属 assistant 消息标识。
 * @param runId 产生该 part 的 run 标识。
 * @param partType part 类型，例如 ANSWER、MESSAGE_SNAPSHOT、THINKING、TOOL、PROGRESS、CARD。
 * @param sourceType 下游原始事件类型，例如 agent、relay-progress、tool_call_streaming。
 * @param contentText 可直接展示的文本摘要。
 * @param title 前端展示标题，例如“工具调用”或“思考过程”。
 * @param status 展示状态，例如 INFO、STARTED、STREAMING、COMPLETED、FAILED、UNKNOWN。
 * @param channel 展示频道，例如 answer、progress、thinking、tool。
 * @param displayHint 展示建议，例如 inline、collapsible、hidden、debug。
 * @param visible 是否默认展示。
 * @param payload 结构化展示载荷，必须是脱敏后的 ChatService 标准 payload。
 * @param partOrder 同一 assistant 消息内的展示顺序。
 * @param createdAt part 创建时间。
 */
public record ChatMessagePart(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String messageId,
        String runId,
        String partType,
        String sourceType,
        String contentText,
        String title,
        String status,
        String channel,
        String displayHint,
        Boolean visible,
        Map<String, Object> payload,
        Integer partOrder,
        Instant createdAt
) {
    private static final String FALLBACK_TITLE = "运行事件";
    private static final Map<String, String> DEFAULT_TITLES = Map.ofEntries(
            Map.entry("ANSWER", "最终回答"),
            Map.entry("MESSAGE_SNAPSHOT", "回答快照"),
            Map.entry("PROGRESS", "运行进度"),
            Map.entry("METADATA", "运行元数据"),
            Map.entry("AGENT", "Agent 调用"),
            Map.entry("THINKING", "思考过程"),
            Map.entry("TOOL", "工具调用"),
            Map.entry("REFERENCE", "引用来源"),
            Map.entry("CARD", "卡片展示"),
            Map.entry("CLARIFICATION_REQUEST", "澄清请求"),
            Map.entry("CLARIFICATION_RESPONSE", "澄清回答"),
            Map.entry("AGENT_CLARIFICATION_REQUEST", "Agent 澄清请求"),
            Map.entry("AGENT_CLARIFICATION_RESPONSE", "Agent 澄清回答"),
            Map.entry("INTENT_CLARIFICATION_REQUEST", "意图澄清请求"),
            Map.entry("INTENT_CLARIFICATION_RESPONSE", "意图澄清回答"),
            Map.entry("DOMAIN_AGENT_REFUSAL", "领域 Agent 拒答"),
            Map.entry("ROUTE_SWITCH_CONFIRMATION_REQUEST", "路由切换确认"),
            Map.entry("ROUTE_SWITCH_CONFIRMATION_RESPONSE", "路由切换确认结果"),
            Map.entry("ROUTE_SWITCH_DECLINED", "路由切换已拒绝")
    );
    private static final Map<String, String> DEFAULT_STATUSES = Map.of(
            "ANSWER", "COMPLETED",
            "MESSAGE_SNAPSHOT", "INFO",
            "PROGRESS", "STREAMING",
            "TOOL", "STREAMING"
    );
    private static final Map<String, String> DEFAULT_CHANNELS = Map.ofEntries(
            Map.entry("ANSWER", "answer"),
            Map.entry("MESSAGE_SNAPSHOT", "answer"),
            Map.entry("PROGRESS", "progress"),
            Map.entry("METADATA", "metadata"),
            Map.entry("AGENT", "agent"),
            Map.entry("THINKING", "thinking"),
            Map.entry("TOOL", "tool"),
            Map.entry("REFERENCE", "reference"),
            Map.entry("CARD", "card"),
            Map.entry("CLARIFICATION_REQUEST", "clarification"),
            Map.entry("CLARIFICATION_RESPONSE", "clarification"),
            Map.entry("AGENT_CLARIFICATION_REQUEST", "clarification"),
            Map.entry("AGENT_CLARIFICATION_RESPONSE", "clarification"),
            Map.entry("INTENT_CLARIFICATION_REQUEST", "clarification"),
            Map.entry("INTENT_CLARIFICATION_RESPONSE", "clarification"),
            Map.entry("DOMAIN_AGENT_REFUSAL", "domain-agent"),
            Map.entry("ROUTE_SWITCH_CONFIRMATION_REQUEST", "routing"),
            Map.entry("ROUTE_SWITCH_CONFIRMATION_RESPONSE", "routing"),
            Map.entry("ROUTE_SWITCH_DECLINED", "routing")
    );
    private static final Map<String, String> DEFAULT_DISPLAY_HINTS = Map.ofEntries(
            Map.entry("ANSWER", "hidden"),
            Map.entry("MESSAGE_SNAPSHOT", "collapsible"),
            Map.entry("PROGRESS", "inline"),
            Map.entry("RUNTIME_EVENT", "debug"),
            Map.entry("CARD", "inline"),
            Map.entry("CLARIFICATION_REQUEST", "inline"),
            Map.entry("CLARIFICATION_RESPONSE", "inline"),
            Map.entry("AGENT_CLARIFICATION_REQUEST", "inline"),
            Map.entry("AGENT_CLARIFICATION_RESPONSE", "inline"),
            Map.entry("INTENT_CLARIFICATION_REQUEST", "inline"),
            Map.entry("INTENT_CLARIFICATION_RESPONSE", "inline"),
            Map.entry("DOMAIN_AGENT_REFUSAL", "inline"),
            Map.entry("ROUTE_SWITCH_CONFIRMATION_REQUEST", "inline"),
            Map.entry("ROUTE_SWITCH_CONFIRMATION_RESPONSE", "inline"),
            Map.entry("ROUTE_SWITCH_DECLINED", "inline")
    );

    public ChatMessagePart(String id, String tenantId, String userId, String sessionId, String messageId,
                           String runId, String partType, String sourceType, String contentText,
                           Map<String, Object> payload, Integer partOrder, Instant createdAt) {
        this(id, tenantId, userId, sessionId, messageId, runId, partType, sourceType, contentText,
                null, null, null, null, null, payload, partOrder, createdAt);
    }

    public ChatMessagePart {
        partType = blankToDefault(partType, "RUNTIME_EVENT");
        payload = ChatPayloadMaps.immutableCopy(payload);
        title = blankToDefault(title, defaultTitle(partType));
        status = blankToDefault(status, defaultStatus(partType, payload));
        channel = blankToDefault(channel, defaultChannel(partType));
        displayHint = blankToDefault(displayHint, defaultDisplayHint(partType));
        visible = visible == null ? defaultVisible(partType) : visible;
        partOrder = partOrder == null ? 0 : partOrder;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    private static String defaultTitle(String partType) {
        return DEFAULT_TITLES.getOrDefault(partType, FALLBACK_TITLE);
    }

    private static String defaultStatus(String partType, Map<String, Object> payload) {
        if ("AGENT".equals(partType)) {
            Object started = payload.get("started");
            if (Boolean.TRUE.equals(started)) {
                return "STARTED";
            }
            if (Boolean.FALSE.equals(started)) {
                return "COMPLETED";
            }
            return "INFO";
        }
        if ("THINKING".equals(partType)) {
            String status = stringValue(payload.get("status"));
            if ("STARTED".equalsIgnoreCase(status)) {
                return "STARTED";
            }
            if ("ENDED".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)) {
                return "COMPLETED";
            }
            return "UNKNOWN";
        }
        return DEFAULT_STATUSES.getOrDefault(partType, "INFO");
    }

    private static String defaultChannel(String partType) {
        return DEFAULT_CHANNELS.getOrDefault(partType, "runtime");
    }

    private static String defaultDisplayHint(String partType) {
        return DEFAULT_DISPLAY_HINTS.getOrDefault(partType, "collapsible");
    }

    private static boolean defaultVisible(String partType) {
        return !"ANSWER".equals(partType) && !"MESSAGE_SNAPSHOT".equals(partType)
                && !"RUNTIME_EVENT".equals(partType);
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
