/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.memory;

import com.huawei.it.ex.one.application.config.RouteMemoryProperties;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.memory.RouteMemoryRepository;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.memory.RouteMemoryContext;
import com.huawei.it.ex.one.domain.memory.RouteMemoryItem;
import com.huawei.it.ex.one.domain.memory.RouteMemoryItemStatus;
import com.huawei.it.ex.one.domain.memory.RouteMemoryItemType;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.routing.RuntimeProfile;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * RouteMemory 是 ChatService 维护的在线意图路由上下文。
 *
 * <p>它只保存路由摘要和未完成的意图澄清链路，不混入普通短期/长期语义记忆。</p>
 */
@Service
public class RouteMemoryApplicationService {
    private static final AppLogger log = AppLoggerFactory.getLogger(RouteMemoryApplicationService.class);
    private static final String CANDIDATE_INTENT_NAMES = "candidateIntentNames";
    private static final String FRONT_SELECTED_ROUTE_SOURCE = "front-selected";

    public static final String TRIGGER_FIRST_TURN = "first_turn";
    public static final String TRIGGER_DOMAIN_REJECT = "domain_reject";
    public static final String TRIGGER_CLARIFY_ANSWER = "clarify_answer";
    public static final String TRIGGER_FALLBACK_FOLLOWUP = "fallback_followup";
    public static final String TRIGGER_USER_CORRECTION = "user_correction";

    private final RouteMemoryRepository repository;
    private final IdGenerator idGenerator;
    private final RouteMemoryProperties properties;
    private final Executor readExecutor;
    private final Executor writeExecutor;
    private final AtomicInteger consecutiveReadFailures = new AtomicInteger();
    private final AtomicReference<Instant> readCircuitOpenUntil = new AtomicReference<>(Instant.EPOCH);

    @Autowired
    public RouteMemoryApplicationService(RouteMemoryRepository repository, IdGenerator idGenerator,
                                         RouteMemoryProperties properties,
                                         @Qualifier("routeMemoryReadExecutor") Executor readExecutor,
                                         @Qualifier("routeMemoryWriteExecutor") Executor writeExecutor) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.properties = properties == null ? new RouteMemoryProperties() : properties;
        this.readExecutor = readExecutor == null ? Runnable::run : readExecutor;
        this.writeExecutor = writeExecutor == null ? Runnable::run : writeExecutor;
    }

    public RouteMemoryApplicationService(RouteMemoryRepository repository, IdGenerator idGenerator,
                                         RouteMemoryProperties properties) {
        this(repository, idGenerator, properties, Runnable::run, Runnable::run);
    }

    public RouteMemoryContext loadForIntent(UserContext user, String sessionId, String routeTrigger,
                                            Map<String, Object> lastIntentRejectReason) {
        if (user == null || blank(sessionId)) {
            return fallbackContext(routeTrigger, lastIntentRejectReason);
        }
        return readSafely("loadForIntent", user, sessionId,
                fallbackContext(routeTrigger, lastIntentRejectReason),
                () -> {
                    List<Map<String, Object>> history = new ArrayList<>();
                    List<RouteMemoryItem> routes = new ArrayList<>(repository.findRecentRoutes(user.tenantId(),
                            user.ownerUserId(), sessionId, properties.normalizedTopK()));
                    Collections.reverse(routes);
                    List<RouteMemoryItem> visibleRoutes = routes.stream()
                            .filter(this::visibleInIntentHistory)
                            .toList();
                    visibleRoutes.stream()
                            .map(this::toHistoryRoute)
                            .forEach(history::add);
                    repository.findActiveClarifications(user.tenantId(), user.ownerUserId(), sessionId).stream()
                            .map(this::toHistoryClarify)
                            .forEach(history::add);
                    return new RouteMemoryContext(blankToDefault(routeTrigger, TRIGGER_FIRST_TURN),
                            history, lastIntentRejectReason, latestRouteSourceRunId(visibleRoutes));
                });
    }

    private String latestRouteSourceRunId(List<RouteMemoryItem> routes) {
        if (routes == null || routes.isEmpty()) {
            return null;
        }
        for (int index = routes.size() - 1; index >= 0; index--) {
            RouteMemoryItem item = routes.get(index);
            if ("route".equals(String.valueOf(toHistoryRoute(item).get("type")))) {
                return item.sourceRunId();
            }
        }
        return null;
    }

    public int activeClarificationCount(UserContext user, String sessionId) {
        if (user == null || blank(sessionId)) {
            return 0;
        }
        return readSafely("activeClarificationCount", user, sessionId, 0,
                () -> repository.findActiveClarifications(user.tenantId(), user.ownerUserId(), sessionId).size());
    }

    public int maxClarificationRounds() {
        return properties.normalizedMaxClarificationRounds();
    }

    public int maxRouteHistorySize() {
        return properties.normalizedTopK();
    }

    /**
     * 判断本轮是否产生了可写入 RouteMemory 的新路由决策。
     *
     * <p>已有 RuntimeBinding 的普通续接和 Agent Interaction 续接只是沿用既有路由，
     * 不能把每轮对话重复写成新的意图路由历史。</p>
     */
    public boolean isNewRouteDecision(RouteTarget route) {
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

    public void appendClarification(UserContext user, String sessionId, String sourceRunId,
                                    String interactionId, Map<String, Object> requestPayload) {
        if (user == null || blank(sessionId) || requestPayload == null || requestPayload.isEmpty()) {
            return;
        }
        writeSafely("appendClarification", user, sessionId, () -> {
            Instant now = Instant.now();
            repository.save(new RouteMemoryItem(
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
            ));
        });
    }

    public void appendRoute(RouteMemoryRouteCommand command) {
        if (command == null) {
            return;
        }
        UserContext user = command.user();
        String sessionId = command.sessionId();
        RouteTarget route = command.route();
        if (user == null || blank(sessionId) || !recordableRoute(route)) {
            return;
        }
        writeSafely("appendRoute", user, sessionId, () -> repository.save(newRouteItem(command, Instant.now())));
    }

    /**
     * 最终目标和 RuntimeBinding 已生效后记录路由决策。
     *
     * <p>同一个异步写任务先折叠当前意图澄清链，再追加 route。Runtime 后续失败、取消或拒答
     * 不撤销已经发生的路由事实；写失败也不能阻断当前 run。</p>
     */
    public void recordRouteDecision(RouteMemoryRouteCommand command) {
        if (command == null) {
            return;
        }
        UserContext user = command.user();
        String sessionId = command.sessionId();
        RouteTarget route = command.route();
        if (user == null || blank(sessionId) || !recordableRoute(route)) {
            return;
        }
        writeSafely("recordRouteDecision", user, sessionId, () -> {
            Instant now = Instant.now();
            repository.foldActiveClarifications(user.tenantId(), user.ownerUserId(), sessionId, now);
            repository.save(newRouteItem(command, now));
        });
    }

    /**
     * 无可记录路由时只折叠澄清链。
     */
    public void completeWithoutRoute(UserContext user, String sessionId) {
        if (user == null || blank(sessionId)) {
            return;
        }
        writeSafely("completeWithoutRoute", user, sessionId,
                () -> repository.foldActiveClarifications(user.tenantId(), user.ownerUserId(), sessionId, Instant.now()));
    }

    public boolean latestRouteIsRelayFallback(UserContext user, String sessionId) {
        if (user == null || blank(sessionId)) {
            return false;
        }
        return readSafely("latestRouteIsRelayFallback", user, sessionId, false,
                () -> repository.latestRouteIsCompletedRelayFallback(
                        user.tenantId(), user.ownerUserId(), sessionId));
    }

    /**
     * 构造可发送给意图服务的 route history 摘要，供同一 run 内拒答重路由立即使用。
     */
    public Map<String, Object> routeHistory(RouteMemoryRouteCommand command) {
        if (command == null || !recordableRoute(command.route())
                || !visibleInIntentHistory(command.route())) {
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

    public void foldActiveClarifications(UserContext user, String sessionId) {
        if (user == null || blank(sessionId)) {
            return;
        }
        writeSafely("foldActiveClarifications", user, sessionId,
                () -> repository.foldActiveClarifications(user.tenantId(), user.ownerUserId(), sessionId, Instant.now()));
    }

    private RouteMemoryContext fallbackContext(String routeTrigger, Map<String, Object> lastIntentRejectReason) {
        return new RouteMemoryContext(blankToDefault(routeTrigger, TRIGGER_FIRST_TURN),
                List.of(), lastIntentRejectReason);
    }

    private <T> T readSafely(String operation, UserContext user, String sessionId, T fallback, Supplier<T> supplier) {
        Instant openUntil = readCircuitOpenUntil.get();
        if (openUntil != null && Instant.now().isBefore(openUntil)) {
            log.debug("RouteMemory read circuit is open, fallback to empty context. operation={}, tenantId={}, userId={}, sessionId={}, openUntil={}",
                    operation, user.tenantId(), user.ownerUserId(), sessionId, openUntil);
            return fallback;
        }
        CompletableFuture<T> future = null;
        try {
            future = CompletableFuture.supplyAsync(supplier, readExecutor);
            T result = future.get(properties.normalizedReadTimeout().toMillis(), TimeUnit.MILLISECONDS);
            recordReadSuccess();
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            cancelFuture(future);
            recordReadFailure();
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_READ_FAILED,
                            "RouteMemory read was interrupted; falling back to an empty context")
                    .sessionId(sessionId)
                    .operation(operation)
                    .build(), ex);
            return fallback;
        } catch (TimeoutException ex) {
            cancelFuture(future);
            recordReadFailure();
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_QUERY_TIMEOUT,
                            "RouteMemory read timed out; falling back to an empty context")
                    .sessionId(sessionId)
                    .operation(operation)
                    .attribute("timeout", properties.normalizedReadTimeout())
                    .build(), ex);
            return fallback;
        } catch (RuntimeException ex) {
            cancelFuture(future);
            recordReadFailure();
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_READ_FAILED,
                            "RouteMemory read failed; falling back to an empty context")
                    .sessionId(sessionId)
                    .operation(operation)
                    .build(), ex);
            return fallback;
        } catch (Exception ex) {
            cancelFuture(future);
            recordReadFailure();
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_READ_FAILED,
                            "RouteMemory read failed; falling back to an empty context")
                    .sessionId(sessionId)
                    .operation(operation)
                    .build(), ex);
            return fallback;
        }
    }

    private void writeSafely(String operation, UserContext user, String sessionId, Runnable task) {
        try {
            writeExecutor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException ex) {
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_WRITE_FAILED,
                                    "RouteMemory write failed and was ignored")
                            .sessionId(sessionId)
                            .operation(operation)
                            .build(), ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.TASK_REJECTED,
                            "RouteMemory write queue rejected a task")
                    .sessionId(sessionId)
                    .operation(operation)
                    .build(), ex);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.TASK_REJECTED,
                            "RouteMemory write scheduling failed")
                    .sessionId(sessionId)
                    .operation(operation)
                    .build(), ex);
        }
    }

    private RouteMemoryItem newRouteItem(RouteMemoryRouteCommand command, Instant now) {
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
        if (route.type() == com.huawei.it.ex.one.domain.routing.RouteType.AGENT_RUNTIME) {
            payload.put("targetProvider", "relay");
            payload.put("routeAction", routeAction(intent));
            List<String> candidateIntentNames = candidateIntentNames(intent);
            if (isRouteMultiRoute(intent, route) && !candidateIntentNames.isEmpty()) {
                payload.put(CANDIDATE_INTENT_NAMES, candidateIntentNames);
            }
        } else if (route.type() == com.huawei.it.ex.one.domain.routing.RouteType.DOMAIN_AGENT) {
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

    private void cancelFuture(CompletableFuture<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private void recordReadSuccess() {
        consecutiveReadFailures.set(0);
        readCircuitOpenUntil.set(Instant.EPOCH);
    }

    private void recordReadFailure() {
        int failures = consecutiveReadFailures.incrementAndGet();
        if (failures < properties.normalizedCircuitBreakerFailureThreshold()) {
            return;
        }
        Instant openUntil = Instant.now().plus(properties.normalizedCircuitBreakerOpenDuration());
        readCircuitOpenUntil.set(openUntil);
        consecutiveReadFailures.set(0);
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_UNAVAILABLE,
                        "RouteMemory read circuit opened after repeated failures")
                .operation("route-memory.read-circuit.open")
                .attribute("failureThreshold", properties.normalizedCircuitBreakerFailureThreshold())
                .attribute("openDuration", properties.normalizedCircuitBreakerOpenDuration())
                .attribute("openUntil", openUntil)
                .build());
    }

    private Map<String, Object> toHistoryRoute(RouteMemoryItem item) {
        if (isNoMatchRoute(item)) {
            return noMatchHistory(item.queryText());
        }
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("type", "route");
        history.put("query", blankToDefault(item.queryText(), ""));
        history.put("intent", routeHistoryIntent(item));
        return Map.copyOf(history);
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
        if (route == null || route.type() != com.huawei.it.ex.one.domain.routing.RouteType.AGENT_RUNTIME) {
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
        if (route == null || route.type() != com.huawei.it.ex.one.domain.routing.RouteType.AGENT_RUNTIME) {
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

    private boolean recordableRoute(RouteTarget route) {
        return isNewRouteDecision(route);
    }

    private boolean visibleInIntentHistory(RouteTarget route) {
        return route != null && visibleInIntentHistory(route.routeSource());
    }

    private boolean visibleInIntentHistory(RouteMemoryItem item) {
        return item != null && visibleInIntentHistory(item.routeSource());
    }

    private boolean visibleInIntentHistory(String routeSource) {
        return !FRONT_SELECTED_ROUTE_SOURCE.equals(routeSource);
    }

    private String routeIntentId(IntentDecision intent, RouteTarget route) {
        if (delegateRelayFallback(intent, route)) {
            return "relay";
        }
        if (intent != null && !blank(intent.intentCode())) {
            return intent.intentCode();
        }
        return route == null ? null : route.selectedAgentCode();
    }

    private String routeIntentName(IntentDecision intent, RouteTarget route) {
        if (delegateRelayFallback(intent, route)) {
            return "no_match";
        }
        if (intent != null && !blank(intent.intentName())) {
            return intent.intentName();
        }
        return route == null ? null : route.selectedAgentCode();
    }

    private String routeDomainAgentId(RouteTarget route) {
        return route != null && route.type() == com.huawei.it.ex.one.domain.routing.RouteType.DOMAIN_AGENT
                ? route.selectedAgentCode()
                : null;
    }

    private boolean delegateRelayFallback(IntentDecision intent, RouteTarget route) {
        return route != null
                && route.type() == com.huawei.it.ex.one.domain.routing.RouteType.AGENT_RUNTIME
                && route.runtimeProfile() != RuntimeProfile.DOMAIN_EXPERT
                && !adoptedRouteSingleIntent(intent);
    }

    private boolean adoptedRouteSingleIntent(IntentDecision intent) {
        return intent != null
                && intent.simpleTask()
                && !blank(intent.candidateDomainAgentId())
                && "ROUTE_SINGLE".equalsIgnoreCase(routeAction(intent));
    }

    private String routeAction(IntentDecision intent) {
        Object routeAction = intent == null || intent.slots() == null ? null : intent.slots().get("routeAction");
        return routeAction == null || String.valueOf(routeAction).isBlank() ? "NO_MATCH" : String.valueOf(routeAction);
    }

    private Map<String, Object> toHistoryClarify(RouteMemoryItem item) {
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source ? new LinkedHashMap<>((Map<String, Object>) source) : Map.of();
    }

    private String clarifyQuestion(Map<String, Object> payload) {
        String direct = text(payload.get("clarifyQuestion"));
        if (!blank(direct)) {
            return direct;
        }
        return text(map(payload.get("clarification")).get("clarifyQuestion"));
    }

    private String clarificationType(Map<String, Object> payload) {
        String direct = text(payload.get("clarificationType"));
        if (!blank(direct)) {
            return direct;
        }
        String type = text(payload.get("type"));
        return blank(type) ? text(map(payload.get("clarification")).get("type")) : type;
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

    public record RouteMemoryRouteCommand(
            UserContext user,
            String sessionId,
            String sourceRunId,
            String query,
            IntentDecision intent,
            RouteTarget route
    ) {
    }
}
