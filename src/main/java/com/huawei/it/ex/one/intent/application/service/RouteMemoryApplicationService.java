package com.huawei.it.ex.one.intent.application.service;

import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.intent.application.config.RouteMemoryProperties;
import com.huawei.it.ex.one.intent.application.model.RouteMemoryContext;
import com.huawei.it.ex.one.intent.application.model.RouteMemoryRouteCommand;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.intent.application.repository.RouteMemoryRepository;
import com.huawei.it.ex.one.intent.domain.memory.RouteMemoryItem;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * RouteMemory is the online intent-routing context maintained by ChatService.
 *
 * <p>It stores only route summaries and unfinished intent clarification chains.</p>
 */
@Service
public class RouteMemoryApplicationService implements RouteMemoryService {
    private static final AppLogger log = AppLoggerFactory.getLogger(RouteMemoryApplicationService.class);

    private final RouteMemoryRepository repository;
    private final RouteMemoryProperties properties;
    private final RouteMemoryIoExecutor io;
    private final RouteMemoryItemMapper itemMapper;

    @Autowired
    public RouteMemoryApplicationService(
            RouteMemoryRepository repository,
            IdGenerator idGenerator,
            RouteMemoryProperties properties,
            @Qualifier("routeMemoryReadExecutor") Executor readExecutor,
            @Qualifier("routeMemoryWriteExecutor") Executor writeExecutor) {
        this.repository = repository;
        this.properties = properties == null ? new RouteMemoryProperties() : properties;
        Executor effectiveReadExecutor = readExecutor == null ? Runnable::run : readExecutor;
        Executor effectiveWriteExecutor = writeExecutor == null ? Runnable::run : writeExecutor;
        this.io = new RouteMemoryIoExecutor(
                this.properties, effectiveReadExecutor, effectiveWriteExecutor, log);
        this.itemMapper = new RouteMemoryItemMapper(idGenerator);
    }

    public RouteMemoryApplicationService(
            RouteMemoryRepository repository,
            IdGenerator idGenerator,
            RouteMemoryProperties properties) {
        this(repository, idGenerator, properties, Runnable::run, Runnable::run);
    }

    @Override
    public RouteMemoryContext loadForIntent(
            UserContext user,
            String sessionId,
            String routeTrigger,
            Map<String, Object> lastIntentRejectReason) {
        if (user == null || blank(sessionId)) {
            return fallbackContext(routeTrigger, lastIntentRejectReason);
        }
        return io.readSafely(
                "loadForIntent",
                user,
                sessionId,
                fallbackContext(routeTrigger, lastIntentRejectReason),
                () -> loadContext(user, sessionId, routeTrigger, lastIntentRejectReason));
    }

    @Override
    public int activeClarificationCount(UserContext user, String sessionId) {
        if (user == null || blank(sessionId)) {
            return 0;
        }
        return io.readSafely(
                "activeClarificationCount",
                user,
                sessionId,
                0,
                () -> repository.findActiveClarifications(
                        user.tenantId(), user.ownerUserId(), sessionId).size());
    }

    @Override
    public int maxClarificationRounds() {
        return properties.normalizedMaxClarificationRounds();
    }

    @Override
    public int maxRouteHistorySize() {
        return properties.normalizedTopK();
    }

    /** Returns whether the route is a newly effective decision rather than binding reuse. */
    @Override
    public boolean isNewRouteDecision(RouteTarget route) {
        return itemMapper.isNewRouteDecision(route);
    }

    @Override
    public void appendClarification(
            UserContext user,
            String sessionId,
            String sourceRunId,
            String interactionId,
            Map<String, Object> requestPayload) {
        if (user == null || blank(sessionId) || requestPayload == null || requestPayload.isEmpty()) {
            return;
        }
        io.writeSafely("appendClarification", sessionId, () -> repository.save(
                itemMapper.newClarificationItem(
                        user, sessionId, sourceRunId, interactionId, requestPayload, Instant.now())));
    }

    @Override
    public void appendRoute(RouteMemoryRouteCommand command) {
        if (!recordable(command)) {
            return;
        }
        io.writeSafely("appendRoute", command.sessionId(),
                () -> repository.save(itemMapper.newRouteItem(command, Instant.now())));
    }

    /**
     * Records an effective route after RuntimeBinding has been persisted.
     * Runtime failure, cancellation, or refusal does not erase the route fact.
     */
    @Override
    public void recordRouteDecision(RouteMemoryRouteCommand command) {
        if (!recordable(command)) {
            return;
        }
        io.writeSafely("recordRouteDecision", command.sessionId(), () -> {
            Instant now = Instant.now();
            UserContext user = command.user();
            repository.foldActiveClarifications(
                    user.tenantId(), user.ownerUserId(), command.sessionId(), now);
            repository.save(itemMapper.newRouteItem(command, now));
        });
    }

    /** Folds the clarification chain when no recordable route was produced. */
    @Override
    public void completeWithoutRoute(UserContext user, String sessionId) {
        if (user == null || blank(sessionId)) {
            return;
        }
        io.writeSafely("completeWithoutRoute", sessionId,
                () -> repository.foldActiveClarifications(
                        user.tenantId(), user.ownerUserId(), sessionId, Instant.now()));
    }

    @Override
    public boolean latestRouteIsRelayFallback(UserContext user, String sessionId) {
        if (user == null || blank(sessionId)) {
            return false;
        }
        return io.readSafely(
                "latestRouteIsRelayFallback",
                user,
                sessionId,
                false,
                () -> repository.latestRouteIsCompletedRelayFallback(
                        user.tenantId(), user.ownerUserId(), sessionId));
    }

    /** Builds the same route summary used by persisted history for same-run rerouting. */
    @Override
    public Map<String, Object> routeHistory(RouteMemoryRouteCommand command) {
        return itemMapper.routeHistory(command);
    }

    @Override
    public void foldActiveClarifications(UserContext user, String sessionId) {
        if (user == null || blank(sessionId)) {
            return;
        }
        io.writeSafely("foldActiveClarifications", sessionId,
                () -> repository.foldActiveClarifications(
                        user.tenantId(), user.ownerUserId(), sessionId, Instant.now()));
    }

    private RouteMemoryContext loadContext(
            UserContext user,
            String sessionId,
            String routeTrigger,
            Map<String, Object> lastIntentRejectReason) {
        List<Map<String, Object>> history = new ArrayList<>();
        List<RouteMemoryItem> routes = new ArrayList<>(repository.findRecentRoutes(
                user.tenantId(), user.ownerUserId(), sessionId, properties.normalizedTopK()));
        Collections.reverse(routes);
        routes.stream().map(itemMapper::toHistoryRoute).forEach(history::add);
        repository.findActiveClarifications(user.tenantId(), user.ownerUserId(), sessionId).stream()
                .map(itemMapper::toHistoryClarify)
                .forEach(history::add);
        return new RouteMemoryContext(
                blankToDefault(routeTrigger, RouteMemoryService.TRIGGER_FIRST_TURN),
                history, lastIntentRejectReason);
    }

    private RouteMemoryContext fallbackContext(
            String routeTrigger,
            Map<String, Object> lastIntentRejectReason) {
        return new RouteMemoryContext(
                blankToDefault(routeTrigger, RouteMemoryService.TRIGGER_FIRST_TURN),
                List.of(), lastIntentRejectReason);
    }

    private boolean recordable(RouteMemoryRouteCommand command) {
        return command != null
                && command.user() != null
                && !blank(command.sessionId())
                && itemMapper.isNewRouteDecision(command.route());
    }

    private String blankToDefault(String value, String defaultValue) {
        return blank(value) ? defaultValue : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
