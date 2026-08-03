package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.it.ex.one.application.service.routing.IntentRecognitionRecordService;
import com.huawei.it.ex.one.application.service.routing.IntentRecognitionRecordSnapshot;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.memory.RouteMemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.routing.RouteType;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records applied route facts after a binding succeeds.
 *
 * <p>RouteMemory remains best-effort and its same-run history item is appended only after scheduling the
 * existing asynchronous write. Failures are logged and never block Runtime dispatch.</p>
 */
final class AppliedRouteRecorder {
    private static final AppLogger log = AppLoggerFactory.getLogger(AppliedRouteRecorder.class);

    private final IntentRecognitionRecordService intentRecognitionRecordService;
    private final RouteMemoryApplicationService routeMemoryService;
    private final ChatRunApplicationService chatRunService;

    AppliedRouteRecorder(IntentRecognitionRecordService intentRecognitionRecordService,
                         RouteMemoryApplicationService routeMemoryService,
                         ChatRunApplicationService chatRunService) {
        this.intentRecognitionRecordService = intentRecognitionRecordService;
        this.routeMemoryService = routeMemoryService;
        this.chatRunService = chatRunService;
    }

    void recordIntent(UserContext user, ChatCommand command, String runId,
                      IntentDecision intent, RouteTarget route, double confidenceThreshold, Long latencyMs) {
        if (intent == null) {
            return;
        }
        intentRecognitionRecordService.recordAsync(IntentRecognitionRecordSnapshot.of(
                new IntentRecognitionRecordSnapshot.IntentRecognitionRecordInput(
                        user, command, runId, intent, route, confidenceThreshold, latencyMs)));
    }

    void recordIntentSignal(UserContext user, ChatCommand command, String runId,
                            RouteSignalResult signal, RouteTarget route) {
        if (signal == null || signal.intentDecision() == null) {
            return;
        }
        recordIntent(user, command, runId, signal.intentDecision(), route,
                signal.intentConfidenceThreshold() == null ? 0.0 : signal.intentConfidenceThreshold(),
                signal.intentLatencyMs());
    }

    void bindResolvedRoute(String runId, RouteTarget route, RuntimeBinding binding) {
        try {
            bindResolvedRouteRequired(runId, route, binding);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_WRITE_FAILED,
                            "ChatRun resolved route diagnostic update failed and was ignored")
                    .runId(runId)
                    .operation("chat-run.bind-resolved-route")
                    .attribute("routeType", route == null || route.type() == null ? null : route.type().name())
                    .attribute("agentCode", route == null ? null : route.selectedAgentCode())
                    .build(), ex);
        }
    }

    /**
     * 拒答重路由必须先保存最终 Runtime，避免 stop 继续取消旧 Agent。
     */
    void bindResolvedRouteRequired(String runId, RouteTarget route, RuntimeBinding binding) {
        if (chatRunService.bindResolvedRoute(runId, route, binding) == null) {
            throw new IllegalStateException("ChatRun resolved route update found no run: " + runId);
        }
    }

    /**
     * Interaction 续接必须在 execution 写入权保护下保存最终 Runtime，失败时禁止调用下游。
     */
    void bindResolvedRouteRequired(ChatRun run, RouteTarget route, RuntimeBinding binding,
                                   RunExecutionClaim claim) {
        bindResolvedRouteRequired(run, route, binding, claim, null);
    }

    void bindResolvedRouteRequired(ChatRun run, RouteTarget route, RuntimeBinding binding,
                                   RunExecutionClaim claim, AgentDataPersistenceState persistenceState) {
        Map<String, Object> metadata = persistenceState == null
                ? Map.of()
                : persistenceState.runMetadataOverlay();
        if (chatRunService.bindResolvedRoute(run, route, binding, claim, metadata) == null) {
            throw new IllegalStateException("ChatRun guarded resolved route update found no run: "
                    + (run == null ? null : run.id()));
        }
    }

    /** 拒答重路由按 runId 使用 execution guard 保存最终 Runtime。 */
    void bindResolvedRouteRequired(String runId, RouteTarget route, RuntimeBinding binding,
                                   RunExecutionClaim claim) {
        bindResolvedRouteRequired(runId, route, binding, claim, null);
    }

    void bindResolvedRouteRequired(String runId, RouteTarget route, RuntimeBinding binding,
                                   RunExecutionClaim claim, AgentDataPersistenceState persistenceState) {
        Map<String, Object> metadata = persistenceState == null
                ? Map.of()
                : persistenceState.runMetadataOverlay();
        if (chatRunService.bindResolvedRoute(runId, route, binding, claim, metadata) == null) {
            throw new IllegalStateException("ChatRun guarded resolved route update found no run: " + runId);
        }
    }

    void bindIntentAgentProvider(String runId) {
        try {
            chatRunService.bindRuntimeProvider(runId, "intent-agent");
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_WRITE_FAILED,
                            "ChatRun intent-agent diagnostic update failed and was ignored")
                    .runId(runId)
                    .operation("chat-run.bind-intent-provider")
                    .build(), ex);
        }
    }

    MemoryContext recordAppliedRouteDecision(AppliedRouteDecision decision) {
        MemoryContext currentMemory = decision.memory() == null ? MemoryContext.empty() : decision.memory();
        if (routeMemoryService == null || decision.binding() == null
                || !routeMemoryService.isNewRouteDecision(decision.route())) {
            return currentMemory;
        }
        try {
            IntentDecision routeMemoryIntent = normalizedRouteMemoryIntent(decision.intent(), decision.route());
            RouteMemoryApplicationService.RouteMemoryRouteCommand command =
                    new RouteMemoryApplicationService.RouteMemoryRouteCommand(
                            decision.user(), decision.sessionId(), decision.runId(),
                            blankToDefault(decision.query(), ""), routeMemoryIntent, decision.route());
            routeMemoryService.recordRouteDecision(command);
            return appendInlineRouteHistory(
                    currentMemory, routeMemoryService.routeHistory(command), decision.runId());
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.TASK_REJECTED,
                            "RouteMemory route decision scheduling failed and was ignored")
                    .runId(decision.runId())
                    .sessionId(decision.sessionId())
                    .operation("route-memory.schedule")
                    .attribute("routeType", decision.route() == null || decision.route().type() == null
                            ? null : decision.route().type().name())
                    .attribute("agentCode", decision.route() == null
                            ? null : decision.route().selectedAgentCode())
                    .build(), ex);
            return currentMemory;
        }
    }

    IntentDecision routeSwitchIntent(ChatInteractionRequest interaction, RouteTarget route) {
        Map<String, Object> requestPayload = interaction == null || interaction.requestPayload() == null
                ? Map.of()
                : interaction.requestPayload();
        if (route != null && route.type() == RouteType.AGENT_RUNTIME) {
            String routeAction = blankToDefault(firstText(requestPayload.get("routeAction")), "NO_MATCH");
            return new IntentDecision("relay", "no_match", TaskComplexity.COMPLEX, 1.0,
                    false, null, Map.of("routeAction", routeAction), List.of(),
                    Map.of("targetProvider", "relay", "routeAction", routeAction));
        }
        String domainAgentId = route == null ? null : route.selectedAgentCode();
        String intentCode = firstText(requestPayload.get("candidateIntentCode"), domainAgentId);
        String intentName = firstText(requestPayload.get("candidateIntentName"), domainAgentId);
        return new IntentDecision(intentCode, intentName, TaskComplexity.SIMPLE, 1.0,
                true, domainAgentId,
                Map.of("routeAction", "ROUTE_SINGLE", "accessName", blankToDefault(domainAgentId, "")),
                List.of(), Map.of());
    }

    void completeWithoutRoute(UserContext user, String sessionId) {
        if (routeMemoryService != null) {
            routeMemoryService.completeWithoutRoute(user, sessionId);
        }
    }

    private IntentDecision normalizedRouteMemoryIntent(IntentDecision intent, RouteTarget route) {
        if (route == null || route.type() != RouteType.AGENT_RUNTIME) {
            return intent;
        }
        String routeAction = firstText(
                intent == null || intent.slots() == null ? null : intent.slots().get("routeAction"),
                intent == null || intent.raw() == null ? null : intent.raw().get("routeAction"),
                "NO_MATCH");
        Map<String, Object> slots = intent == null || intent.slots() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(intent.slots());
        slots.put("routeAction", routeAction);
        return new IntentDecision("relay", "no_match", TaskComplexity.COMPLEX, 0.0,
                false, null, slots, List.of(),
                Map.of("targetProvider", "relay", "routeAction", routeAction));
    }

    private MemoryContext appendInlineRouteHistory(
            MemoryContext memory, Map<String, Object> historyItem, String sourceRunId) {
        if (historyItem == null || historyItem.isEmpty()) {
            return memory;
        }
        RouteMemoryContext current = memory.routeMemory() == null
                ? RouteMemoryContext.empty()
                : memory.routeMemory();
        List<Map<String, Object>> history = new ArrayList<>(current.history());
        if (!history.contains(historyItem)) {
            history.add(historyItem);
        }
        String latestRouteSourceRunId = "route".equals(String.valueOf(historyItem.get("type")))
                ? sourceRunId
                : current.latestRouteSourceRunId();
        return memory.withRouteMemory(new RouteMemoryContext(
                current.routeTrigger(), history, current.lastIntentRejectReason(), latestRouteSourceRunId));
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

    record AppliedRouteDecision(
            UserContext user,
            String sessionId,
            String runId,
            String query,
            IntentDecision intent,
            RouteTarget route,
            RuntimeBinding binding,
            MemoryContext memory
    ) {
    }
}
