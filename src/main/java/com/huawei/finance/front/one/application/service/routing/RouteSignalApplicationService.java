package com.huawei.finance.front.one.application.service.routing;

import com.huawei.finance.front.one.application.config.RouteSignalProperties;
import com.huawei.finance.front.one.application.integration.intent.IntentAgentRouteFrame;
import com.huawei.finance.front.one.application.integration.intent.IntentAgentRouteRequest;
import com.huawei.finance.front.one.application.integration.intent.IntentAgentRouteResult;
import com.huawei.finance.front.one.application.integration.intent.IntentAgentRuntime;
import com.huawei.finance.front.one.application.integration.intent.IntentRecognitionResult;
import com.huawei.finance.front.one.application.integration.usecase.UseCaseLibraryClient;
import com.huawei.finance.front.one.application.integration.usecase.UseCaseMatchRequest;
import com.huawei.finance.front.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.RuntimeEvent;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.intent.TaskComplexity;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.memory.RouteMemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.routing.RouteType;
import com.huawei.finance.front.one.domain.routing.RoutingPolicy;
import com.huawei.finance.front.one.domain.usecase.UseCaseMatchResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 可选路由信号编排服务。
 *
 * <p>该服务是 FinanceEXChatService 与外部用例库/意图服务之间的应用层防腐层。它确保两个外部
 * 信号都可通过配置关闭；关闭时不发起 HTTP 调用，异常时不阻断聊天主链路，而是降级到下一路由阶段或
 * Relay Runtime。</p>
 */
@Service
public class RouteSignalApplicationService {
    private static final Logger log = LoggerFactory.getLogger(RouteSignalApplicationService.class);

    private final UseCaseLibraryClient useCaseLibraryClient;
    private final IntentAgentRuntime intentAgentRuntime;
    private final RoutingPolicy routingPolicy;
    private final RouteSignalProperties properties;
    private final RouteMemoryApplicationService routeMemoryService;

    /**
     * 创建可选路由信号编排服务。
     *
     * @param useCaseLibraryClient 用例库 HTTP 端口；仅在配置开启时调用。
     * @param intentAgentRuntime 意图路由 Agent；仅在配置开启时调用。
     * @param routingPolicy 纯领域路由策略。
     * @param properties 外部路由信号开关配置。
     */
    @Autowired
    public RouteSignalApplicationService(UseCaseLibraryClient useCaseLibraryClient, IntentAgentRuntime intentAgentRuntime,
                                         RoutingPolicy routingPolicy, RouteSignalProperties properties,
                                         RouteMemoryApplicationService routeMemoryService) {
        this.useCaseLibraryClient = useCaseLibraryClient;
        this.intentAgentRuntime = intentAgentRuntime;
        this.routingPolicy = routingPolicy;
        this.properties = properties;
        this.routeMemoryService = routeMemoryService;
    }

    public RouteSignalApplicationService(UseCaseLibraryClient useCaseLibraryClient, IntentAgentRuntime intentAgentRuntime,
                                         RoutingPolicy routingPolicy, RouteSignalProperties properties) {
        this(useCaseLibraryClient, intentAgentRuntime, routingPolicy, properties, null);
    }

    /**
     * 解析没有 active RuntimeBinding 可直接续接时的首轮路由。
     *
     * <p>默认情况下两个外部信号都关闭，方法会直接返回 Relay Runtime 路由。任一信号开启后，只调用开启
     * 的服务；服务失败按未命中处理，不向前端抛出外部依赖异常。</p>
     *
     * @param user 当前服务端身份上下文。
     * @param session 当前聊天会话。
     * @param command 本轮聊天命令。
     * @param attachments 本轮附件引用。
     * @param memory 本轮运行上下文快照。
     * @return 首轮路由信号结果。
     */
    public RouteSignalResult routeInitial(UserContext user, ChatSession session, ChatCommand command,
                                          List<AttachmentRef> attachments, MemoryContext memory) {
        return routeInitialWithProgress(user, session, command, attachments, memory)
                .filter(RouteSignalFrame::resultFrame)
                .map(RouteSignalFrame::result)
                .blockLast();
    }

    /**
     * 解析首轮路由，并在外部路由调用前先输出可落库的进度帧。
     *
     * <p>阻塞式 use-case/intent HTTP 调用放在 boundedElastic，避免占住 Servlet 或事件写入线程。</p>
     */
    public Flux<RouteSignalFrame> routeInitialWithProgress(UserContext user, ChatSession session, ChatCommand command,
                                                           List<AttachmentRef> attachments, MemoryContext memory) {
        return routeInitialWithProgress(new RouteSignalRequest(null, user, session, command, attachments, memory));
    }

    /**
     * 解析首轮路由，并允许 IntentAgent 返回带 runId/sessionId 的标准事件。
     */
    public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
        return Flux.defer(() -> routeInitialFrames(request));
    }

    private Flux<RouteSignalFrame> routeInitialFrames(RouteSignalRequest request) {
        String runId = request.runId();
        UserContext user = request.user();
        ChatSession session = request.session();
        ChatCommand command = request.command();
        List<AttachmentRef> attachments = request.attachments();
        MemoryContext memory = request.memory();
        if (properties.useCaseLibraryEnabled()) {
            return Flux.just(RouteSignalFrame.progress(progress("use_case_matching",
                            "正在匹配可用能力", Map.of())))
                    .concatWith(Mono.fromCallable(() -> routingPolicy.decideFromUseCase(
                                    matchUseCase(user, session, command, attachments, memory)))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMapMany(useCaseRoute -> useCaseRoute.type() == RouteType.DOMAIN_AGENT
                                    ? Flux.just(RouteSignalFrame.result(RouteSignalResult.of(useCaseRoute)))
                                    : intentOrFallbackFrames(runId, user, session, command, memory)));
        }

        return intentOrFallbackFrames(runId, user, session, command, memory);
    }

    private Flux<RouteSignalFrame> intentOrFallbackFrames(String runId, UserContext user, ChatSession session, ChatCommand command,
                                                          MemoryContext memory) {
        if (properties.intentEnabled()) {
            String routeTrigger = routeTrigger(user, session, command);
            Map<String, Object> lastRejectReason = lastIntentRejectReason(command);
            IntentRouteRequest routeRequest = new IntentRouteRequest(
                    user, session, command, memory, routeTrigger, lastRejectReason, runId);
            MemoryContext intentMemory = memoryWithRouteContext(routeRequest);
            return intentAgentRuntime.route(new IntentAgentRouteRequest(
                            user, session, command, intentMemory, runId, routeTrigger))
                    .concatMap(frame -> toRouteSignalFrames(routeRequest, intentMemory, frame));
        }

        return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.agentRuntime("route-signal", 0.0,
                "use case library and intent service disabled or not matched"))));
    }

    private Flux<RouteSignalFrame> toRouteSignalFrames(IntentRouteRequest request, MemoryContext intentMemory,
                                                       IntentAgentRouteFrame frame) {
        if (frame.eventFrame()) {
            return Flux.just(RouteSignalFrame.event(frame.event()));
        }
        return intentResultFrames(request, intentMemory, frame.result());
    }

    private Flux<RouteSignalFrame> intentResultFrames(IntentRouteRequest request, MemoryContext intentMemory,
                                                      IntentAgentRouteResult agentResult) {
        IntentRecognitionResult result = agentResult == null ? null : agentResult.recognitionResult();
        long latencyMs = agentResult == null ? 0L : agentResult.latencyMs();
        if (result == null) {
            RouteSignalResult routeResult = RouteSignalResult.of(RouteTarget.agentRuntime(IntentAgentRuntime.PROVIDER,
                    0.0, "intent agent returned empty result"));
            return intentResultAndRouteFrames(request, routeResult, null, latencyMs, "DEGRADED");
        }
        if (result.waitingClarification()) {
            if (clarificationRoundLimitReached(request)) {
                RouteSignalResult routeResult = RouteSignalResult.of(RouteTarget.agentRuntime(IntentAgentRuntime.PROVIDER, 0.0,
                        "intent clarification max rounds exceeded"));
                return intentResultAndRouteFrames(request, routeResult, null, latencyMs, "DEGRADED");
            }
            Map<String, Object> clarificationPayload = intentClarificationPayload(request.command(), result);
            return Flux.just(RouteSignalFrame.result(RouteSignalResult.waitingIntentClarification(
                    clarificationPayload,
                    latencyMs,
                    routingPolicy.intentConfidenceThreshold(),
                    result.intentSessionId(),
                    result.intentRequestId())));
        }
        IntentDecision intent = result == null ? null : result.decision();
        if (intent != null && intent.complexity() == TaskComplexity.NEED_CLARIFICATION) {
            if (clarificationRoundLimitReached(request)) {
                RouteSignalResult routeResult = RouteSignalResult.ofIntent(RouteTarget.agentRuntime(IntentAgentRuntime.PROVIDER, 0.0,
                        "intent clarification max rounds exceeded"),
                        intent, latencyMs, routingPolicy.intentConfidenceThreshold());
                return intentResultAndRouteFrames(request, routeResult, intent, latencyMs, "DEGRADED");
            }
            Map<String, Object> clarificationPayload = intentClarificationPayload(request.command(), result);
            return Flux.just(RouteSignalFrame.result(RouteSignalResult.waitingIntentClarification(
                    clarificationPayload,
                    latencyMs,
                    routingPolicy.intentConfidenceThreshold(),
                    result.intentSessionId(),
                    result.intentRequestId())));
        }
        RouteTarget route = routingPolicy.decideFromIntent(request.command(), intentMemory, intent, request.user());
        RouteSignalResult routeResult = RouteSignalResult.ofIntent(route,
                intent, latencyMs, routingPolicy.intentConfidenceThreshold());
        return intentResultAndRouteFrames(request, routeResult, intent, latencyMs,
                routeAction(result, intent, route));
    }

    private boolean clarificationRoundLimitReached(IntentRouteRequest request) {
        if (routeMemoryService == null) {
            return false;
        }
        int inlineRounds = inlineClarificationHistory(request.command()).size();
        int persistedRounds = routeMemoryService.activeClarificationCount(request.user(), request.session().id());
        return Math.max(inlineRounds, persistedRounds) >= routeMemoryService.maxClarificationRounds();
    }

    private RouteSignalProgress progress(String stage, String message, Map<String, Object> attributes) {
        return RouteSignalProgress.of(stage, message, attributes);
    }

    private RuntimeEvent intentResultEvent(IntentRouteRequest request, RouteSignalResult routeResult,
                                           IntentDecision intent, long latencyMs, String routeAction) {
        if (request == null || request.runId() == null || request.runId().isBlank()
                || request.session() == null || request.session().id() == null || request.session().id().isBlank()) {
            return null;
        }
        RouteTarget route = routeResult == null ? null : routeResult.route();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", IntentAgentRuntime.PROVIDER);
        payload.put("sourceType", "intent-result");
        payload.put("stage", "intent_result");
        payload.put("message", "已完成意图识别");
        payload.put("routeAction", blankToDefault(routeAction, "UNKNOWN"));
        payload.put("routeTrigger", request.routeTrigger() == null ? "" : request.routeTrigger());
        payload.put("latencyMs", latencyMs);
        if (intent != null) {
            payload.put("intentCode", blankToDefault(intent.intentCode(), ""));
            payload.put("intentId", blankToDefault(intent.intentCode(), ""));
            payload.put("intentName", blankToDefault(intent.intentName(), ""));
            payload.put("confidence", intent.confidence());
            if (intent.candidateDomainAgentId() != null && !intent.candidateDomainAgentId().isBlank()) {
                payload.put("skillId", intent.candidateDomainAgentId());
            }
        }
        if (route != null && route.type() != null) {
            payload.put("routeType", route.type().name());
            payload.put("routeSource", blankToDefault(route.routeSource(), IntentAgentRuntime.PROVIDER));
            if (route.type() == RouteType.DOMAIN_AGENT) {
                payload.put("targetProvider", "domain-agent");
                payload.put("targetId", blankToDefault(route.selectedAgentCode(), ""));
            } else if (route.type() == RouteType.AGENT_RUNTIME) {
                payload.put("targetProvider", "relay");
            } else {
                payload.put("targetProvider", "system");
            }
        }
        return RuntimeEvent.progress(request.runId(), request.session().id(), Map.copyOf(payload));
    }

    private Flux<RouteSignalFrame> intentResultAndRouteFrames(IntentRouteRequest request, RouteSignalResult routeResult,
                                                              IntentDecision intent, long latencyMs, String routeAction) {
        RuntimeEvent event = intentResultEvent(request, routeResult, intent, latencyMs, routeAction);
        return event == null
                ? Flux.just(RouteSignalFrame.result(routeResult))
                : Flux.just(RouteSignalFrame.event(event), RouteSignalFrame.result(routeResult));
    }

    private String routeAction(IntentRecognitionResult result, IntentDecision intent, RouteTarget route) {
        if (result != null && result.status() == IntentRecognitionResult.Status.FAILED_OR_DEGRADED) {
            return "DEGRADED";
        }
        Object fromSlots = intent == null || intent.slots() == null ? null : intent.slots().get("routeAction");
        if (fromSlots != null && !String.valueOf(fromSlots).isBlank()) {
            return String.valueOf(fromSlots);
        }
        if (route != null && route.type() == RouteType.DOMAIN_AGENT) {
            return "ROUTE_SINGLE";
        }
        return route != null && route.type() == RouteType.AGENT_RUNTIME ? "NO_MATCH" : "UNKNOWN";
    }

    private UseCaseMatchResult matchUseCase(UserContext user, ChatSession session, ChatCommand command,
                                            List<AttachmentRef> attachments, MemoryContext memory) {
        try {
            return useCaseLibraryClient.match(new UseCaseMatchRequest(
                    user.tenantId(), user.ownerUserId(), session.id(), command.message(), attachments, memory, command.metadata()));
        } catch (RuntimeException ex) {
            log.warn("Use case route signal failed, degrading to next route stage. tenantId={}, userId={}, sessionId={}, reason={}",
                    user.tenantId(), user.ownerUserId(), session.id(), ex.getMessage());
            return UseCaseMatchResult.notMatched("use case library failed: " + ex.getMessage());
        }
    }

    private MemoryContext memoryWithRouteContext(IntentRouteRequest request) {
        RouteMemoryContext context = routeMemoryService == null
                ? new RouteMemoryContext(request.routeTrigger(), List.of(), request.lastRejectReason())
                : routeMemoryService.loadForIntent(request.user(), request.session().id(),
                request.routeTrigger(), request.lastRejectReason());
        return (request.memory() == null ? MemoryContext.empty() : request.memory())
                .withRouteMemory(mergeInlineClarificationHistory(context, request.command()));
    }

    private RouteMemoryContext mergeInlineClarificationHistory(RouteMemoryContext context, ChatCommand command) {
        List<Map<String, Object>> inlineClarifications = inlineClarificationHistory(command);
        if (inlineClarifications.isEmpty()) {
            return context;
        }
        List<Map<String, Object>> history = new java.util.ArrayList<>();
        if (context != null && context.history() != null) {
            context.history().stream()
                    .filter(item -> !"clarify".equals(String.valueOf(item.get("type"))))
                    .forEach(history::add);
        }
        history.addAll(inlineClarifications);
        return new RouteMemoryContext(
                context == null ? RouteMemoryApplicationService.TRIGGER_CLARIFY_ANSWER : context.routeTrigger(),
                history,
                context == null ? Map.of() : context.lastIntentRejectReason());
    }

    private String routeTrigger(UserContext user, ChatSession session, ChatCommand command) {
        Map<String, Object> metadata = command == null || command.metadata() == null ? Map.of() : command.metadata();
        String trigger = firstText(command == null ? null : command.routeTrigger(),
                metadata.get("routeTrigger"),
                map(metadata.get("conversationContext")).get("routeTrigger"));
        if ("intent_clarification".equals(trigger)) {
            return RouteMemoryApplicationService.TRIGGER_CLARIFY_ANSWER;
        }
        if (trigger != null) {
            return trigger;
        }
        if (routeMemoryService != null && session != null
                && routeMemoryService.latestRouteIsRelayFallback(user, session.id())) {
            return RouteMemoryApplicationService.TRIGGER_FALLBACK_FOLLOWUP;
        }
        return RouteMemoryApplicationService.TRIGGER_FIRST_TURN;
    }

    private Map<String, Object> lastIntentRejectReason(ChatCommand command) {
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

    private Map<String, Object> intentClarificationPayload(ChatCommand command, IntentRecognitionResult result) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(result.normalizedClarificationPayload());
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

    private String originalIntentQuery(ChatCommand command) {
        Map<String, Object> intentClarification = intentClarification(command);
        return firstText(intentClarification.get("originalQuery"), command == null ? null : command.message());
    }

    private List<Map<String, Object>> inlineClarificationHistory(ChatCommand command) {
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

    private Map<String, Object> intentClarification(ChatCommand command) {
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

    private record IntentRouteRequest(UserContext user,
                                      ChatSession session,
                                      ChatCommand command,
                                      MemoryContext memory,
                                      String routeTrigger,
                                      Map<String, Object> lastRejectReason,
                                      String runId) {
    }

}
