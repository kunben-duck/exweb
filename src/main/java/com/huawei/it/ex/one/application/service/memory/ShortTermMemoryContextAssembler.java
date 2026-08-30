/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.memory;

import com.huawei.it.ex.one.application.config.MemoryProperties;
import com.huawei.it.ex.one.application.integration.agent.MessageSkillContext;
import com.huawei.it.ex.one.application.integration.memory.MemoryTokenCounter;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.memory.ConversationMemoryMessage;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.memory.RouteMemoryContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 统一组装 Agent Runtime 与 Intent 使用的短期上下文投影。
 */
@Component
public class ShortTermMemoryContextAssembler {
    public static final String PRIVATE_INTENT_MESSAGES_KEY = "_intentDomainSessionMessages";
    public static final String INTENT_MESSAGES_KEY = "domainSessionMessages";

    private static final String DOMAIN_REJECT = "domain_reject";
    private static final String USER_CORRECTION = "user_correction";
    private static final String CLARIFY_ANSWER = "clarify_answer";

    private final MemoryProperties properties;
    private final MemoryTokenCounter tokenCounter;
    private final ObjectMapper objectMapper;

    public ShortTermMemoryContextAssembler(MemoryProperties properties,
                                           MemoryTokenCounter tokenCounter,
                                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.tokenCounter = tokenCounter;
        this.objectMapper = objectMapper;
    }

    public int sourceMessageLimit() {
        return properties.getShortTerm().sourceMessageLimit();
    }

    public List<ConversationMemoryMessage> agentRuntimeMessages(List<ChatMessage> source) {
        MemoryProperties.ContextWindow window = properties.getShortTerm().getAgentRuntime();
        List<ConversationMemoryMessage> messages = normalizedMessages(source, true);
        return trim(messages, window.messageLimit(), window.normalizedMaxContextTokens());
    }

    public IntentProjection projectIntent(MemoryContext memory,
                                          RouteMemoryContext routeMemory,
                                          String routeTrigger,
                                          String currentRunId,
                                          Map<String, Object> commandMetadata) {
        RouteMemoryContext safeRouteMemory = routeMemory == null ? RouteMemoryContext.empty() : routeMemory;
        if (!properties.getShortTerm().isEnabled()) {
            return new IntentProjection(safeRouteMemory, Optional.empty());
        }
        Optional<List<ConversationMemoryMessage>> frozen = frozenMessages(routeTrigger, commandMetadata);
        if (frozen.isEmpty() && !initialIntentMemoryTrigger(routeTrigger)) {
            return new IntentProjection(safeRouteMemory, Optional.empty());
        }
        List<ConversationMemoryMessage> selected = frozen.orElseGet(() -> intentMessages(
                memory == null ? List.of() : memory.recentMessages(),
                safeRouteMemory.latestRouteSourceRunId(),
                currentRunId));
        RouteMemoryContext enriched = attachToLatestRoute(safeRouteMemory, selected);
        List<ConversationMemoryMessage> actual = latestAttachedMessages(enriched).orElse(List.of());
        return new IntentProjection(enriched, Optional.of(actual));
    }

    public Optional<List<ConversationMemoryMessage>> privateMessagesForClarification(
            MemoryContext memory,
            String routeTrigger,
            Map<String, Object> commandMetadata) {
        Optional<List<ConversationMemoryMessage>> frozen = frozenMessages(routeTrigger, commandMetadata);
        if (frozen.isPresent()) {
            return frozen;
        }
        if (!initialIntentMemoryTrigger(routeTrigger) || memory == null || memory.routeMemory() == null) {
            return Optional.empty();
        }
        return latestAttachedMessages(memory.routeMemory()).or(() -> Optional.of(List.of()));
    }

    public static Map<String, Object> publicInteractionPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty() || !payload.containsKey(PRIVATE_INTENT_MESSAGES_KEY)) {
            return payload == null ? Map.of() : payload;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(payload);
        sanitized.remove(PRIVATE_INTENT_MESSAGES_KEY);
        return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
    }

    private List<ConversationMemoryMessage> intentMessages(List<ChatMessage> source,
                                                           String routeSourceRunId,
                                                           String currentRunId) {
        if (routeSourceRunId == null || routeSourceRunId.isBlank()) {
            return List.of();
        }
        // 同一 run 内刚完成路由后发生拒答时，记忆快照尚未包含当前问题，不能把路由前消息误判为后续上下文。
        if (routeSourceRunId.equals(currentRunId)) {
            return List.of();
        }
        int boundary = -1;
        for (int index = 0; index < source.size(); index++) {
            ChatMessage message = source.get(index);
            if (message != null && routeSourceRunId.equals(message.runId())) {
                boundary = index;
            }
        }
        if (boundary < 0 && !source.isEmpty()) {
            boundary = -1;
        }
        List<ConversationMemoryMessage> messages = new ArrayList<>();
        for (int index = boundary + 1; index < source.size(); index++) {
            ChatMessage message = source.get(index);
            if (message == null || currentRunId != null && currentRunId.equals(message.runId())
                    || !"user".equalsIgnoreCase(message.role()) || blank(message.content())) {
                continue;
            }
            messages.add(new ConversationMemoryMessage("user", message.content().trim()));
        }
        MemoryProperties.ContextWindow window = properties.getShortTerm().getIntent();
        return trim(messages, window.normalizedRecentTurns(), window.normalizedMaxContextTokens());
    }

    private List<ConversationMemoryMessage> normalizedMessages(List<ChatMessage> source, boolean includeAssistant) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        Map<String, String> assistantSkills = new LinkedHashMap<>();
        Map<String, String> userSkills = new LinkedHashMap<>();
        for (ChatMessage message : source) {
            if (message == null || !"assistant".equalsIgnoreCase(message.role())) {
                continue;
            }
            String skillId = MessageSkillContext.messageSkillId(objectMapper, message.metadataJson());
            if (skillId == null) {
                continue;
            }
            assistantSkills.put(message.id(), skillId);
            if (message.parentMessageId() != null && !message.parentMessageId().isBlank()) {
                userSkills.put(message.parentMessageId(), skillId);
            }
        }
        List<ConversationMemoryMessage> result = new ArrayList<>();
        for (ChatMessage message : source) {
            if (message == null || blank(message.role()) || blank(message.content())) {
                continue;
            }
            String role = message.role().trim().toLowerCase(Locale.ROOT);
            if (!"user".equals(role) && !(includeAssistant && "assistant".equals(role))) {
                continue;
            }
            String skillId = "assistant".equals(role)
                    ? assistantSkills.get(message.id())
                    : userSkills.get(message.id());
            result.add(new ConversationMemoryMessage(role, message.content().trim(), skillId));
        }
        return List.copyOf(result);
    }

    private List<ConversationMemoryMessage> trim(List<ConversationMemoryMessage> source,
                                                 int messageLimit,
                                                 int tokenLimit) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        int fromIndex = Math.max(0, source.size() - Math.max(1, messageLimit));
        List<ConversationMemoryMessage> selected = new ArrayList<>(source.subList(fromIndex, source.size()));
        while (selected.size() > 1 && tokenCounter.countTokens(selected) > tokenLimit) {
            selected.removeFirst();
        }
        if (selected.isEmpty() || tokenCounter.countTokens(selected) <= tokenLimit) {
            return List.copyOf(selected);
        }
        ConversationMemoryMessage latest = selected.getFirst();
        ConversationMemoryMessage truncated = truncate(latest, tokenLimit);
        return truncated == null ? List.of() : List.of(truncated);
    }

    private ConversationMemoryMessage truncate(ConversationMemoryMessage message, int tokenLimit) {
        int[] codePoints = message.content().codePoints().toArray();
        int low = 0;
        int high = codePoints.length;
        ConversationMemoryMessage best = null;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            String content = new String(codePoints, 0, middle);
            ConversationMemoryMessage candidate = new ConversationMemoryMessage(
                    message.role(), content, message.skillId());
            if (tokenCounter.countTokens(List.of(candidate)) <= tokenLimit) {
                best = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best == null || best.content().isEmpty() ? null : best;
    }

    private RouteMemoryContext attachToLatestRoute(RouteMemoryContext context,
                                                    List<ConversationMemoryMessage> messages) {
        List<Map<String, Object>> history = new ArrayList<>(context.history());
        for (int index = history.size() - 1; index >= 0; index--) {
            Map<String, Object> item = history.get(index);
            if (!"route".equals(String.valueOf(item.get("type")))) {
                continue;
            }
            Map<String, Object> enriched = new LinkedHashMap<>(item);
            enriched.put(INTENT_MESSAGES_KEY, List.copyOf(messages));
            history.set(index, Map.copyOf(enriched));
            return new RouteMemoryContext(context.routeTrigger(), history,
                    context.lastIntentRejectReason(), context.latestRouteSourceRunId());
        }
        return context;
    }

    private Optional<List<ConversationMemoryMessage>> latestAttachedMessages(RouteMemoryContext context) {
        if (context == null || context.history() == null) {
            return Optional.empty();
        }
        for (int index = context.history().size() - 1; index >= 0; index--) {
            Object value = context.history().get(index).get(INTENT_MESSAGES_KEY);
            if (value instanceof List<?> list) {
                return Optional.of(toMemoryMessages(list));
            }
        }
        return Optional.empty();
    }

    private Optional<List<ConversationMemoryMessage>> frozenMessages(String routeTrigger,
                                                                     Map<String, Object> metadata) {
        if (!CLARIFY_ANSWER.equals(routeTrigger) || metadata == null
                || !metadata.containsKey(PRIVATE_INTENT_MESSAGES_KEY)) {
            return Optional.empty();
        }
        Object value = metadata.get(PRIVATE_INTENT_MESSAGES_KEY);
        return value instanceof List<?> list ? Optional.of(toMemoryMessages(list)) : Optional.of(List.of());
    }

    private List<ConversationMemoryMessage> toMemoryMessages(List<?> values) {
        List<ConversationMemoryMessage> messages = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof ConversationMemoryMessage message) {
                if (!blank(message.role()) && !blank(message.content())) {
                    messages.add(new ConversationMemoryMessage(
                            message.role().trim().toLowerCase(Locale.ROOT), message.content().trim()));
                }
                continue;
            }
            if (value instanceof Map<?, ?> map) {
                String role = text(map.get("role"));
                String content = text(map.get("content"));
                if (role != null && content != null) {
                    messages.add(new ConversationMemoryMessage(role.toLowerCase(Locale.ROOT), content));
                }
            }
        }
        return List.copyOf(messages);
    }

    private boolean initialIntentMemoryTrigger(String routeTrigger) {
        return DOMAIN_REJECT.equals(routeTrigger) || USER_CORRECTION.equals(routeTrigger);
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record IntentProjection(
            RouteMemoryContext routeMemory,
            Optional<List<ConversationMemoryMessage>> frozenMessages
    ) {
        public IntentProjection {
            routeMemory = routeMemory == null ? RouteMemoryContext.empty() : routeMemory;
            frozenMessages = frozenMessages == null ? Optional.empty() : frozenMessages;
        }
    }
}
