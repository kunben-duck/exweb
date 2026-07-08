package com.huawei.finance.front.one.application.service.memory;

import com.huawei.finance.front.one.application.config.RouteMemoryProperties;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.memory.RouteMemoryRepository;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.RouteMemoryContext;
import com.huawei.finance.front.one.domain.memory.RouteMemoryItem;
import com.huawei.finance.front.one.domain.memory.RouteMemoryItemStatus;
import com.huawei.finance.front.one.domain.memory.RouteMemoryItemType;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * RouteMemory 是 ChatService 维护的在线意图路由上下文。
 *
 * <p>它只保存路由摘要和未完成的意图澄清链路，不混入普通短期/长期语义记忆。</p>
 */
@Service
public class RouteMemoryApplicationService {
    private static final Logger log = LoggerFactory.getLogger(RouteMemoryApplicationService.class);

    public static final String TRIGGER_FIRST_TURN = "first_turn";
    public static final String TRIGGER_DOMAIN_REJECT = "domain_reject";
    public static final String TRIGGER_CLARIFY_ANSWER = "clarify_answer";

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
                    routes.stream()
                            .map(this::toHistoryRoute)
                            .forEach(history::add);
                    repository.findActiveClarifications(user.tenantId(), user.ownerUserId(), sessionId).stream()
                            .map(this::toHistoryClarify)
                            .forEach(history::add);
                    return new RouteMemoryContext(blankToDefault(routeTrigger, TRIGGER_FIRST_TURN),
                            history, lastIntentRejectReason);
                });
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

    public void appendClarification(UserContext user, String sessionId, String sourceRunId,
                                    String hitlRequestId, Map<String, Object> requestPayload) {
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
                    hitlRequestId,
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
        if (user == null || blank(sessionId) || route == null || blank(route.selectedAgentCode())) {
            return;
        }
        writeSafely("appendRoute", user, sessionId, () -> repository.save(newRouteItem(command, Instant.now())));
    }

    /**
     * 成功路由闭合后在同一个写任务中先折叠当前澄清链，再追加 route 记录。
     *
     * <p>RouteMemory 不是聊天事实源，写失败只影响后续意图上下文质量，不能回滚 run 终态。</p>
     */
    public void completeRoute(RouteMemoryRouteCommand command) {
        if (command == null) {
            return;
        }
        UserContext user = command.user();
        String sessionId = command.sessionId();
        RouteTarget route = command.route();
        if (user == null || blank(sessionId) || route == null || blank(route.selectedAgentCode())) {
            return;
        }
        writeSafely("completeRoute", user, sessionId, () -> {
            Instant now = Instant.now();
            repository.foldActiveClarifications(user.tenantId(), user.ownerUserId(), sessionId, now);
            repository.save(newRouteItem(command, now));
        });
    }

    /**
     * ROUTE_MULTI/NO_MATCH 或最终进入 Relay 成功闭合后，只折叠澄清链，不写成功 DomainAgent route。
     */
    public void completeWithoutRoute(UserContext user, String sessionId) {
        if (user == null || blank(sessionId)) {
            return;
        }
        writeSafely("completeWithoutRoute", user, sessionId,
                () -> repository.foldActiveClarifications(user.tenantId(), user.ownerUserId(), sessionId, Instant.now()));
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
            log.warn("RouteMemory read interrupted, fallback to empty context. operation={}, tenantId={}, userId={}, sessionId={}",
                    operation, user.tenantId(), user.ownerUserId(), sessionId);
            return fallback;
        } catch (TimeoutException ex) {
            cancelFuture(future);
            recordReadFailure();
            log.warn("RouteMemory read timed out, fallback to empty context. operation={}, tenantId={}, userId={}, sessionId={}, timeout={}",
                    operation, user.tenantId(), user.ownerUserId(), sessionId, properties.normalizedReadTimeout());
            return fallback;
        } catch (RuntimeException ex) {
            cancelFuture(future);
            recordReadFailure();
            log.warn("RouteMemory read failed, fallback to empty context. operation={}, tenantId={}, userId={}, sessionId={}, reason={}",
                    operation, user.tenantId(), user.ownerUserId(), sessionId, ex.getMessage());
            return fallback;
        } catch (Exception ex) {
            cancelFuture(future);
            recordReadFailure();
            log.warn("RouteMemory read timed out or failed, fallback to empty context. operation={}, tenantId={}, userId={}, sessionId={}, reason={}",
                    operation, user.tenantId(), user.ownerUserId(), sessionId, ex.getMessage());
            return fallback;
        }
    }

    private void writeSafely(String operation, UserContext user, String sessionId, Runnable task) {
        try {
            writeExecutor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException ex) {
                    log.warn("RouteMemory write failed and was ignored. operation={}, tenantId={}, userId={}, sessionId={}, reason={}",
                            operation, user.tenantId(), user.ownerUserId(), sessionId, ex.getMessage());
                }
            });
        } catch (RejectedExecutionException ex) {
            log.warn("RouteMemory write queue rejected task and was ignored. operation={}, tenantId={}, userId={}, sessionId={}, reason={}",
                    operation, user.tenantId(), user.ownerUserId(), sessionId, ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("RouteMemory write scheduling failed and was ignored. operation={}, tenantId={}, userId={}, sessionId={}, reason={}",
                    operation, user.tenantId(), user.ownerUserId(), sessionId, ex.getMessage());
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
        return new RouteMemoryItem(
                idGenerator.newId("rmem", IdGenerateContext.of(user.tenantId(), user.ownerUserId(), sessionId)),
                user.tenantId(),
                user.ownerUserId(),
                sessionId,
                RouteMemoryItemType.ROUTE,
                RouteMemoryItemStatus.ACTIVE,
                command.query(),
                intent == null ? route.selectedAgentCode() : intent.intentCode(),
                intent == null ? route.selectedAgentCode() : intent.intentName(),
                route.selectedAgentCode(),
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
        log.warn("RouteMemory read circuit opened. failureThreshold={}, openDuration={}, openUntil={}",
                properties.normalizedCircuitBreakerFailureThreshold(),
                properties.normalizedCircuitBreakerOpenDuration(),
                openUntil);
    }

    private Map<String, Object> toHistoryRoute(RouteMemoryItem item) {
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("type", "route");
        history.put("query", blankToDefault(item.queryText(), ""));
        history.put("intent", blankToDefault(item.intentName(), blankToDefault(item.domainAgentId(), "")));
        return Map.copyOf(history);
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
