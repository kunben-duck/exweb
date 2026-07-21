package com.huawei.it.ex.one.intent.application.service;

import com.huawei.it.ex.one.common.id.IdGenerateContext;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.intent.application.model.IntentDecision;
import com.huawei.it.ex.one.intent.application.model.RouteMemoryRouteCommand;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.intent.application.model.RouteType;
import com.huawei.it.ex.one.intent.domain.memory.RouteMemoryItem;
import com.huawei.it.ex.one.intent.domain.memory.RouteMemoryItemStatus;
import com.huawei.it.ex.one.intent.domain.memory.RouteMemoryItemType;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Package-local mapping between RouteMemory facts and Intent history snapshots. */
final class RouteMemoryItemMapper {
    private static final String CANDIDATE_INTENT_NAMES = "candidateIntentNames";

    private final IdGenerator idGenerator;

    RouteMemoryItemMapper(IdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    boolean isNewRouteDecision(RouteTarget route) {
        if (route == null || route.type() == null) {
            return false;
        }
        String source = route.routeSource();
        if ("runtime-binding".equals(source) || "interaction-continuation".equals(source)) {
            return false;
        }
        return switch (route.type()) {
            case DOMAIN_AGENT -> !blank(route.selectedAgentCode());
            case AGENT_RUNTIME -> true;
            case SYSTEM_RESPONSE -> false;
        };
    }

    RouteMemoryItem newRouteItem(RouteMemoryRouteCommand command, Instant now) {
        UserContext user = command.user();
        String sessionId = command.sessionId();
        RouteTarget route = command.route();
        IntentDecision intent = command.intent();
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "routeType", route.type() == null ? "" : route.type().name());
        putIfPresent(payload, "routeSource", route.routeSource());
        putIfPresent(payload, "routeReason", route.reason());
        if (intent != null) {
            putIfPresent(payload, "intentCode", intent.intentCode());
            putIfPresent(payload, "intentName", intent.intentName());
            putIfPresent(payload, "confidence", intent.confidence());
            putIfPresent(payload, "slots", intent.slots());
        }
        if (route.type() == RouteType.AGENT_RUNTIME) {
            payload.put("targetProvider", "relay");
            payload.put("routeAction", routeAction(intent));
            List<String> candidateIntentNames = candidateIntentNames(intent);
            if (isRouteMultiRoute(intent, route) && !candidateIntentNames.isEmpty()) {
                payload.put(CANDIDATE_INTENT_NAMES, candidateIntentNames);
            }
        } else if (route.type() == RouteType.DOMAIN_AGENT) {
            payload.put("targetProvider", "domain-agent");
        }
        return new RouteMemoryItem(
                idGenerator.newId("rmem", IdGenerateContext.of(user.tenantId(), user.ownerUserId(), sessionId)),
                user.tenantId(),
                user.ownerUserId(),
                sessionId,
                RouteMemoryItemType.ROUTE,
                RouteMemoryItemStatus.ACTIVE,
                command.query(),
                routeIntentId(intent, route),
                routeIntentName(intent, route),
                routeDomainAgentId(route),
                route.routeSource(),
                null,
                null,
                command.sourceRunId(),
                null,
                Map.copyOf(payload),
                null,
                now,
                now
        );
    }

    RouteMemoryItem newClarificationItem(
            UserContext user,
            String sessionId,
            String sourceRunId,
            String interactionId,
            Map<String, Object> requestPayload,
            Instant now) {
        return new RouteMemoryItem(
                idGenerator.newId("rmem", IdGenerateContext.of(user.tenantId(), user.ownerUserId(), sessionId)),
                user.tenantId(),
                user.ownerUserId(),
                sessionId,
                RouteMemoryItemType.CLARIFY,
                RouteMemoryItemStatus.ACTIVE,
                text(requestPayload.get("originalQuery")),
                null,
                null,
                null,
                null,
                clarifyQuestion(requestPayload),
                clarificationType(requestPayload),
                sourceRunId,
                interactionId,
                requestPayload,
                null,
                now,
                now
        );
    }

    Map<String, Object> routeHistory(RouteMemoryRouteCommand command) {
        if (command == null || !isNewRouteDecision(command.route())) {
            return Map.of();
        }
        if (isNoMatchRoute(command.intent(), command.route())) {
            return noMatchHistory(command.query());
        }
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("type", "route");
        history.put("query", blankToDefault(command.query(), ""));
        history.put("intent", routeHistoryIntent(command.intent(), command.route()));
        return Map.copyOf(history);
    }

    Map<String, Object> toHistoryRoute(RouteMemoryItem item) {
        if (isNoMatchRoute(item)) {
            return noMatchHistory(item.queryText());
        }
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("type", "route");
        history.put("query", blankToDefault(item.queryText(), ""));
        history.put("intent", routeHistoryIntent(item));
        return Map.copyOf(history);
    }

    Map<String, Object> toHistoryClarify(RouteMemoryItem item) {
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("type", "clarify");
        history.put("query", blankToDefault(item.queryText(), ""));
        if (!blank(item.clarifyQuestion())) {
            history.put("clarifyQuestion", item.clarifyQuestion());
        }
        if (!blank(item.clarificationType())) {
            history.put("clarificationType", item.clarificationType());
        }
        return Map.copyOf(history);
    }

    String clarifyQuestion(Map<String, Object> payload) {
        String direct = text(payload.get("clarifyQuestion"));
        if (!blank(direct)) {
            return direct;
        }
        return text(map(payload.get("clarification")).get("clarifyQuestion"));
    }

    String clarificationType(Map<String, Object> payload) {
        String direct = text(payload.get("clarificationType"));
        if (!blank(direct)) {
            return direct;
        }
        String type = text(payload.get("type"));
        return blank(type) ? text(map(payload.get("clarification")).get("type")) : type;
    }

    private Map<String, Object> noMatchHistory(String query) {
        return Map.of(
                "type", "NO_MATCH",
                "query", blankToDefault(query, ""),
                "intent", ""
        );
    }

    private boolean isNoMatchRoute(RouteMemoryItem item) {
        Object routeAction = item == null || item.payload() == null
                ? null
                : item.payload().get("routeAction");
        return isNoMatchRouteAction(routeAction);
    }

    private boolean isNoMatchRoute(IntentDecision intent, RouteTarget route) {
        if (route == null || route.type() != RouteType.AGENT_RUNTIME) {
            return false;
        }
        Object routeAction = intent == null || intent.slots() == null
                ? null
                : intent.slots().get("routeAction");
        return isNoMatchRouteAction(routeAction);
    }

    private boolean isNoMatchRouteAction(Object routeAction) {
        return routeAction != null && "NO_MATCH".equalsIgnoreCase(String.valueOf(routeAction).trim());
    }

    private boolean isRouteMultiRoute(IntentDecision intent, RouteTarget route) {
        if (route == null || route.type() != RouteType.AGENT_RUNTIME) {
            return false;
        }
        Object routeAction = intent == null || intent.slots() == null
                ? null
                : intent.slots().get("routeAction");
        return isRouteMultiRouteAction(routeAction);
    }

    private boolean isRouteMultiRoute(RouteMemoryItem item) {
        Object routeAction = item == null || item.payload() == null
                ? null
                : item.payload().get("routeAction");
        return isRouteMultiRouteAction(routeAction);
    }

    private boolean isRouteMultiRouteAction(Object routeAction) {
        return routeAction != null && "ROUTE_MULTI".equalsIgnoreCase(String.valueOf(routeAction).trim());
    }

    private String routeHistoryIntent(IntentDecision intent, RouteTarget route) {
        if (isRouteMultiRoute(intent, route)) {
            String candidateNames = String.join(";", candidateIntentNames(intent));
            if (!blank(candidateNames)) {
                return candidateNames;
            }
        }
        return blankToDefault(routeIntentName(intent, route), "");
    }

    private String routeHistoryIntent(RouteMemoryItem item) {
        if (isRouteMultiRoute(item)) {
            String candidateNames = String.join(";", candidateIntentNames(item));
            if (!blank(candidateNames)) {
                return candidateNames;
            }
        }
        return blankToDefault(item == null ? null : item.intentName(),
                blankToDefault(item == null ? null : item.domainAgentId(), ""));
    }

    private List<String> candidateIntentNames(IntentDecision intent) {
        Object value = intent == null || intent.slots() == null
                ? null
                : intent.slots().get(CANDIDATE_INTENT_NAMES);
        return normalizedCandidateIntentNames(value);
    }

    private List<String> candidateIntentNames(RouteMemoryItem item) {
        Map<String, Object> payload = item == null || item.payload() == null ? Map.of() : item.payload();
        Object value = payload.get(CANDIDATE_INTENT_NAMES);
        if (value == null) {
            value = map(payload.get("slots")).get(CANDIDATE_INTENT_NAMES);
        }
        return normalizedCandidateIntentNames(value);
    }

    private List<String> normalizedCandidateIntentNames(Object value) {
        if (!(value instanceof Iterable<?> values)) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Object candidate : values) {
            String name = text(candidate);
            if (!blank(name)) {
                names.add(name.trim());
            }
        }
        return List.copyOf(names);
    }

    private String routeIntentId(IntentDecision intent, RouteTarget route) {
        if (route != null && route.type() == RouteType.AGENT_RUNTIME) {
            return "relay";
        }
        if (intent != null && !blank(intent.intentCode())) {
            return intent.intentCode();
        }
        return route == null ? null : route.selectedAgentCode();
    }

    private String routeIntentName(IntentDecision intent, RouteTarget route) {
        if (route != null && route.type() == RouteType.AGENT_RUNTIME) {
            return "no_match";
        }
        if (intent != null && !blank(intent.intentName())) {
            return intent.intentName();
        }
        return route == null ? null : route.selectedAgentCode();
    }

    private String routeDomainAgentId(RouteTarget route) {
        return route != null && route.type() == RouteType.DOMAIN_AGENT
                ? route.selectedAgentCode()
                : null;
    }

    private String routeAction(IntentDecision intent) {
        Object routeAction = intent == null || intent.slots() == null ? null : intent.slots().get("routeAction");
        return routeAction == null || String.valueOf(routeAction).isBlank()
                ? "NO_MATCH"
                : String.valueOf(routeAction);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source
                ? new LinkedHashMap<>((Map<String, Object>) source)
                : Map.of();
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (payload != null && key != null && value != null) {
            payload.put(key, value);
        }
    }

    private String blankToDefault(String value, String defaultValue) {
        return blank(value) ? defaultValue : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
