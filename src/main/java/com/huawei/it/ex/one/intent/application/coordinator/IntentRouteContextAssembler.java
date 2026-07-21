package com.huawei.it.ex.one.intent.application.coordinator;

import com.huawei.it.ex.one.intent.application.service.RouteMemoryService;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;
import com.huawei.it.ex.one.intent.application.model.RouteMemoryContext;
import com.huawei.it.ex.one.intent.application.client.IntentAgentRuntime;
import com.huawei.it.ex.one.intent.application.model.IntentRecognitionResult;
import com.huawei.it.ex.one.intent.application.model.IntentCommandSnapshot;
import com.huawei.it.ex.one.intent.application.model.IntentRouteContext;
import com.huawei.it.ex.one.intent.application.model.IntentSessionSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the existing IntentAgent route-memory and clarification context without changing its semantics. */
public final class IntentRouteContextAssembler {
    private final RouteMemoryService routeMemoryService;

    public IntentRouteContextAssembler(RouteMemoryService routeMemoryService) {
        this.routeMemoryService = routeMemoryService;
    }

    public IntentRouteContext create(UserContext user, IntentSessionSnapshot session, IntentCommandSnapshot command,
                                     MemoryContext memory, String runId) {
        String routeTrigger = routeTrigger(user, session, command);
        return new IntentRouteContext(user, session, command, memory, routeTrigger,
                lastIntentRejectReason(command), runId);
    }

    public MemoryContext memoryWithRouteContext(IntentRouteContext request) {
        RouteMemoryContext context = routeMemoryService == null
                ? new RouteMemoryContext(request.routeTrigger(), List.of(), request.lastRejectReason())
                : routeMemoryService.loadForIntent(request.user(), request.session().id(),
                request.routeTrigger(), request.lastRejectReason());
        context = mergeInlineRouteHistory(context, request.memory());
        return (request.memory() == null ? MemoryContext.empty() : request.memory())
                .withRouteMemory(mergeInlineClarificationHistory(context, request.command()));
    }

    public boolean clarificationRoundLimitReached(IntentRouteContext request) {
        if (routeMemoryService == null) {
            return false;
        }
        int inlineRounds = inlineClarificationHistory(request.command()).size();
        int persistedRounds = routeMemoryService.activeClarificationCount(request.user(), request.session().id());
        return Math.max(inlineRounds, persistedRounds) >= routeMemoryService.maxClarificationRounds();
    }

    public Map<String, Object> intentClarificationPayload(
            IntentCommandSnapshot command, IntentRecognitionResult result) {
        Map<String, Object> payload = new LinkedHashMap<>(result.normalizedClarificationPayload());
        payload.putIfAbsent("source", IntentAgentRuntime.PROVIDER);
        payload.put("sourceType", "intent-clarification-request");
        payload.put("interactionType", "INTENT_CLARIFICATION");
        payload.put("originalQuery", blankToDefault(originalIntentQuery(command), ""));
        payload.put("clarifyTriggerQuery", command == null ? "" : blankToDefault(command.message(), ""));
        List<Map<String, Object>> clarificationHistory = inlineClarificationHistory(command);
        if (!clarificationHistory.isEmpty()) {
            payload.put("clarificationHistory", clarificationHistory);
        }
        if (result.intentSessionId() != null && !result.intentSessionId().isBlank()) {
            payload.put("intentSessionId", result.intentSessionId());
        }
        if (result.intentRequestId() != null && !result.intentRequestId().isBlank()) {
            payload.put("intentRequestId", result.intentRequestId());
        }
        return Map.copyOf(payload);
    }

    public List<Map<String, Object>> inlineClarificationHistory(IntentCommandSnapshot command) {
        Object value = intentClarification(command).get("clarificationHistory");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> historyClarification(map(item)))
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private RouteMemoryContext mergeInlineRouteHistory(RouteMemoryContext context, MemoryContext memory) {
        if (memory == null || memory.routeMemory() == null || memory.routeMemory().history() == null) {
            return context;
        }
        List<Map<String, Object>> inlineRoutes = memory.routeMemory().history().stream()
                .filter(this::isRouteHistoryItem)
                .toList();
        if (inlineRoutes.isEmpty()) {
            return context;
        }
        List<Map<String, Object>> history = new ArrayList<>();
        if (context != null && context.history() != null) {
            history.addAll(context.history());
        }
        inlineRoutes.stream()
                .filter(item -> !history.contains(item))
                .forEach(history::add);
        trimOldestRoutes(history);
        return new RouteMemoryContext(
                context == null ? RouteMemoryService.TRIGGER_FIRST_TURN : context.routeTrigger(),
                history,
                context == null ? Map.of() : context.lastIntentRejectReason());
    }

    private void trimOldestRoutes(List<Map<String, Object>> history) {
        int maxRoutes = routeMemoryService == null
                ? Integer.MAX_VALUE
                : routeMemoryService.maxRouteHistorySize();
        int routeCount = (int) history.stream()
                .filter(this::isRouteHistoryItem)
                .count();
        for (int index = 0; routeCount > maxRoutes && index < history.size();) {
            if (isRouteHistoryItem(history.get(index))) {
                history.remove(index);
                routeCount--;
            } else {
                index++;
            }
        }
    }

    private boolean isRouteHistoryItem(Map<String, Object> item) {
        String type = item == null ? "" : String.valueOf(item.get("type"));
        return "route".equals(type) || "NO_MATCH".equals(type);
    }

    private RouteMemoryContext mergeInlineClarificationHistory(
            RouteMemoryContext context, IntentCommandSnapshot command) {
        List<Map<String, Object>> inlineClarifications = inlineClarificationHistory(command);
        if (inlineClarifications.isEmpty()) {
            return context;
        }
        List<Map<String, Object>> history = new ArrayList<>();
        if (context != null && context.history() != null) {
            context.history().stream()
                    .filter(item -> !"clarify".equals(String.valueOf(item.get("type"))))
                    .forEach(history::add);
        }
        history.addAll(inlineClarifications);
        return new RouteMemoryContext(
                context == null ? RouteMemoryService.TRIGGER_CLARIFY_ANSWER : context.routeTrigger(),
                history,
                context == null ? Map.of() : context.lastIntentRejectReason());
    }

    private String routeTrigger(UserContext user, IntentSessionSnapshot session, IntentCommandSnapshot command) {
        Map<String, Object> metadata = command == null || command.metadata() == null ? Map.of() : command.metadata();
        String trigger = firstText(command == null ? null : command.routeTrigger(),
                metadata.get("routeTrigger"),
                map(metadata.get("conversationContext")).get("routeTrigger"));
        if ("intent_clarification".equals(trigger)) {
            return RouteMemoryService.TRIGGER_CLARIFY_ANSWER;
        }
        if (trigger != null) {
            return trigger;
        }
        if (routeMemoryService != null && session != null
                && routeMemoryService.latestRouteIsRelayFallback(user, session.id())) {
            return RouteMemoryService.TRIGGER_FALLBACK_FOLLOWUP;
        }
        return RouteMemoryService.TRIGGER_FIRST_TURN;
    }

    private Map<String, Object> lastIntentRejectReason(IntentCommandSnapshot command) {
        Map<String, Object> metadata = command == null || command.metadata() == null ? Map.of() : command.metadata();
        Map<String, Object> explicit = map(metadata.get("lastIntentRejectReason"));
        if (explicit.isEmpty()) {
            explicit = map(map(metadata.get("conversationContext")).get("lastIntentRejectReason"));
        }
        if (explicit.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> reason = new LinkedHashMap<>();
        String lastIntent = firstText(explicit.get("lastIntent"),
                explicit.get("lastDomainAgentId"),
                explicit.get("domainAgentId"));
        String domainRejectMessage = firstText(explicit.get("domainRejectMessage"),
                explicit.get("message"),
                explicit.get("reason"));
        if (lastIntent != null) {
            reason.put("lastIntent", lastIntent);
        }
        if (domainRejectMessage != null) {
            reason.put("domainRejectMessage", domainRejectMessage);
        }
        return reason.isEmpty() ? Map.of() : Map.copyOf(reason);
    }

    private String originalIntentQuery(IntentCommandSnapshot command) {
        Map<String, Object> intentClarification = intentClarification(command);
        return firstText(intentClarification.get("originalQuery"), command == null ? null : command.message());
    }

    private Map<String, Object> historyClarification(Map<String, Object> source) {
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("type", "clarify");
        String query = firstText(source.get("query"), source.get("originalQuery"), source.get("clarifyTriggerQuery"));
        String question = firstText(source.get("clarifyQuestion"),
                map(source.get("clarification")).get("clarifyQuestion"),
                source.get("question"));
        String type = firstText(source.get("clarificationType"),
                source.get("type"),
                map(source.get("clarification")).get("type"));
        String answer = firstText(source.get("answer"), source.get("answerText"));
        if (query != null) {
            history.put("query", query);
        }
        if (question != null) {
            history.put("clarifyQuestion", question);
        }
        if (type != null && !"clarify".equals(type)) {
            history.put("clarificationType", type);
        }
        if (answer != null) {
            history.put("answer", answer);
        }
        return history.size() <= 1 ? Map.of() : Map.copyOf(history);
    }

    private Map<String, Object> intentClarification(IntentCommandSnapshot command) {
        Map<String, Object> metadata = command == null || command.metadata() == null ? Map.of() : command.metadata();
        return map(metadata.get("intentClarification"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source ? new LinkedHashMap<>((Map<String, Object>) source) : Map.of();
    }

    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
