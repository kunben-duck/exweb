package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.intent.application.model.RouteMemoryRouteCommand;

import com.huawei.it.ex.one.chat.application.mapper.ChatIntentMapper;
import com.huawei.it.ex.one.intent.application.service.RouteMemoryService;
import com.huawei.it.ex.one.chat.application.service.ChatRunApplicationService;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;
import com.huawei.it.ex.one.intent.application.model.RouteMemoryContext;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.intent.application.model.RouteType;
import com.huawei.it.ex.one.intent.application.model.IntentDecision;
import com.huawei.it.ex.one.intent.application.model.IntentRecognitionRecordSnapshot;
import com.huawei.it.ex.one.intent.application.model.RouteSignalResult;
import com.huawei.it.ex.one.intent.application.model.TaskComplexity;
import com.huawei.it.ex.one.intent.application.service.IntentRecognitionService;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Records applied route decisions and best-effort run diagnostics. */
@Component
public class RouteDecisionRecorder {
    private static final AppLogger log = AppLoggerFactory.getLogger(RouteDecisionRecorder.class);

    private final IntentRecognitionService intentRecognitionRecordService;
    private final RouteMemoryService routeMemoryService;
    private final ChatRunApplicationService chatRunService;

    public RouteDecisionRecorder(IntentRecognitionService intentRecognitionRecordService,
                                 RouteMemoryService routeMemoryService,
                                 ChatRunApplicationService chatRunService) {
        this.intentRecognitionRecordService = intentRecognitionRecordService;
        this.routeMemoryService = routeMemoryService;
        this.chatRunService = chatRunService;
    }

    public void recordIntent(IntentRecord request) {
        if (request.intent() == null) {
            return;
        }
        intentRecognitionRecordService.recordAsync(IntentRecognitionRecordSnapshot.of(
                new IntentRecognitionRecordSnapshot.IntentRecognitionRecordInput(
                        request.user(), ChatIntentMapper.toCommand(request.command()), request.runId(),
                        request.intent(), request.route(),
                        request.confidenceThreshold(), request.latencyMs())));
    }

    public void recordIntentSignal(UserContext user, ChatCommand command, String runId,
                                   RouteSignalResult signal, RouteTarget route) {
        if (signal == null || signal.intentDecision() == null) {
            return;
        }
        recordIntent(new IntentRecord(
                user, command, runId, signal.intentDecision(), route,
                signal.intentConfidenceThreshold() == null ? 0.0 : signal.intentConfidenceThreshold(),
                signal.intentLatencyMs()));
    }

    public void bindResolvedRoute(String runId, RouteTarget route, RuntimeBinding binding) {
        try {
            chatRunService.bindResolvedRoute(runId, route, binding);
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

    public void bindIntentAgentProvider(String runId) {
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

    public MemoryContext recordAppliedRouteDecision(AppliedRouteDecision decision) {
        MemoryContext currentMemory = decision.memory() == null ? MemoryContext.empty() : decision.memory();
        if (routeMemoryService == null || decision.binding() == null
                || !routeMemoryService.isNewRouteDecision(decision.route())) {
            return currentMemory;
        }
        try {
            IntentDecision routeMemoryIntent = normalizedRouteMemoryIntent(decision.intent(), decision.route());
            RouteMemoryRouteCommand command =
                    new RouteMemoryRouteCommand(
                            decision.user(), decision.sessionId(), decision.runId(),
                            blankToDefault(decision.query(), ""), routeMemoryIntent, decision.route());
            routeMemoryService.recordRouteDecision(command);
            Map<String, Object> historyItem = routeMemoryService.routeHistory(command);
            return appendInlineRouteHistory(currentMemory, historyItem);
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

    public IntentDecision routeSwitchIntent(ChatInteractionRequest interaction, RouteTarget route) {
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

    public void foldClarificationsWithoutDecision(UserContext user, String sessionId) {
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

    private MemoryContext appendInlineRouteHistory(MemoryContext memory, Map<String, Object> historyItem) {
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
        return memory.withRouteMemory(new RouteMemoryContext(
                current.routeTrigger(), history, current.lastIntentRejectReason()));
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

    public record AppliedRouteDecision(
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

    public record IntentRecord(
            UserContext user,
            ChatCommand command,
            String runId,
            IntentDecision intent,
            RouteTarget route,
            double confidenceThreshold,
            Long latencyMs
    ) {
    }
}
