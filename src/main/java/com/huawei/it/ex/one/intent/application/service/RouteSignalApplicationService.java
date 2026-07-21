package com.huawei.it.ex.one.intent.application.service;

import com.huawei.it.ex.one.intent.application.config.IntentFailureStrategy;
import com.huawei.it.ex.one.intent.application.config.RouteSignalProperties;
import com.huawei.it.ex.one.intent.application.coordinator.IntentResultFrameFactory;
import com.huawei.it.ex.one.intent.application.coordinator.IntentRouteContextAssembler;
import com.huawei.it.ex.one.common.metadata.SelectedIntentContext;
import com.huawei.it.ex.one.intent.application.model.IntentAgentRouteFrame;
import com.huawei.it.ex.one.intent.application.model.IntentAgentRouteRequest;
import com.huawei.it.ex.one.intent.application.model.IntentAgentRouteResult;
import com.huawei.it.ex.one.intent.application.client.IntentAgentRuntime;
import com.huawei.it.ex.one.intent.application.model.IntentRecognitionResult;
import com.huawei.it.ex.one.intent.application.model.IntentRouteContext;
import com.huawei.it.ex.one.intent.application.model.RouteSignalFrame;
import com.huawei.it.ex.one.intent.application.model.RouteSignalProgress;
import com.huawei.it.ex.one.intent.application.model.RouteSignalRequest;
import com.huawei.it.ex.one.intent.application.model.RouteSignalResult;
import com.huawei.it.ex.one.intent.compat.caselibrary.client.UseCaseLibraryClient;
import com.huawei.it.ex.one.intent.compat.caselibrary.model.UseCaseMatchRequest;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.intent.application.model.IntentAttachmentSnapshot;
import com.huawei.it.ex.one.intent.application.model.IntentCommandSnapshot;
import com.huawei.it.ex.one.intent.application.model.IntentSessionSnapshot;
import com.huawei.it.ex.one.intent.application.model.IntentDecision;
import com.huawei.it.ex.one.intent.application.model.TaskComplexity;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.intent.application.model.RouteType;
import com.huawei.it.ex.one.intent.application.coordinator.RoutingPolicy;
import com.huawei.it.ex.one.intent.compat.caselibrary.model.UseCaseMatchResult;
import com.huawei.it.ex.one.intent.application.service.IntentDecisionService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
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
public class RouteSignalApplicationService implements IntentDecisionService {
    private static final AppLogger log = AppLoggerFactory.getLogger(RouteSignalApplicationService.class);

    private final UseCaseLibraryClient useCaseLibraryClient;
    private final IntentAgentRuntime intentAgentRuntime;
    private final RoutingPolicy routingPolicy;
    private final RouteSignalProperties properties;
    private final IntentRouteContextAssembler routeContextAssembler;
    private final IntentResultFrameFactory resultFrameFactory;

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
                                         RouteMemoryService routeMemoryService) {
        this.useCaseLibraryClient = useCaseLibraryClient;
        this.intentAgentRuntime = intentAgentRuntime;
        this.routingPolicy = routingPolicy;
        this.properties = properties;
        this.routeContextAssembler = new IntentRouteContextAssembler(routeMemoryService);
        this.resultFrameFactory = new IntentResultFrameFactory();
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
    /**
     * 解析首轮路由，并允许 IntentAgent 返回带 runId/sessionId 的标准事件。
     */
    public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
        return Flux.defer(() -> routeInitialFrames(request));
    }

    private Flux<RouteSignalFrame> routeInitialFrames(RouteSignalRequest request) {
        UserContext user = request.user();
        IntentSessionSnapshot session = request.session();
        IntentCommandSnapshot command = request.command();
        List<IntentAttachmentSnapshot> attachments = request.attachments();
        MemoryContext memory = request.memory();
        if (properties.useCaseLibraryEnabled()) {
            return Flux.just(RouteSignalFrame.progress(progress("use_case_matching",
                            "正在匹配可用能力", Map.of())))
                    .concatWith(Mono.fromCallable(() -> routingPolicy.decideFromUseCase(
                                    matchUseCase(user, session, command, attachments, memory)))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMapMany(useCaseRoute -> useCaseRoute.type() == RouteType.DOMAIN_AGENT
                                    ? Flux.just(RouteSignalFrame.result(RouteSignalResult.of(useCaseRoute)))
                                    : intentOrFallbackFrames(request)));
        }

        return intentOrFallbackFrames(request);
    }

    private Flux<RouteSignalFrame> intentOrFallbackFrames(RouteSignalRequest request) {
        String runId = request.runId();
        UserContext user = request.user();
        IntentSessionSnapshot session = request.session();
        IntentCommandSnapshot command = request.command();
        MemoryContext memory = request.memory();
        if (properties.intentEnabled()) {
            IntentCommandSnapshot intentCommand = commandWithMessage(command, request.intentQuery());
            IntentRouteContext routeRequest = routeContextAssembler.create(
                    user, session, intentCommand, memory, runId);
            MemoryContext intentMemory = routeContextAssembler.memoryWithRouteContext(routeRequest);
            return intentAgentRuntime.route(new IntentAgentRouteRequest(
                            user, session, intentCommand, intentMemory, runId, routeRequest.routeTrigger()))
                    .concatMap(frame -> toRouteSignalFrames(routeRequest, intentMemory, frame))
                    .onErrorResume(ex -> {
                        String reason = "intent agent stream failed: "
                                + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTENT_DECISION_STREAM_FAILED,
                                        "IntentDecision event stream failed; applying configured failure strategy")
                                .sessionId(session.id())
                                .operation("intent.stream")
                                .attribute("failureStrategy", properties.intentFailureStrategy())
                                .build(), ex);
                        return intentFailureFrames(routeRequest, degradedIntent(reason), 0L, reason);
                    });
        }

        return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.agentRuntime("route-signal", 0.0,
                "use case library and intent service disabled or not matched"))));
    }

    private IntentCommandSnapshot commandWithMessage(IntentCommandSnapshot command, String message) {
        if (command == null || java.util.Objects.equals(command.message(), message)) {
            return command;
        }
        return command.withMessage(message);
    }

    private Flux<RouteSignalFrame> toRouteSignalFrames(IntentRouteContext request, MemoryContext intentMemory,
                                                       IntentAgentRouteFrame frame) {
        if (frame.eventFrame()) {
            return Flux.just(RouteSignalFrame.event(frame.event()));
        }
        return intentResultFrames(request, intentMemory, frame.result());
    }

    private Flux<RouteSignalFrame> intentResultFrames(IntentRouteContext request, MemoryContext intentMemory,
                                                      IntentAgentRouteResult agentResult) {
        IntentRecognitionResult result = agentResult == null ? null : agentResult.recognitionResult();
        long latencyMs = agentResult == null ? 0L : agentResult.latencyMs();
        if (result == null) {
            return intentFailureFrames(request, null, latencyMs, "intent agent returned empty result");
        }
        if (result.status() == IntentRecognitionResult.Status.FAILED_OR_DEGRADED) {
            return intentFailureFrames(request, result.decision(), latencyMs, intentFailureReason(result.decision()));
        }
        if (result.waitingClarification()) {
            if (routeContextAssembler.clarificationRoundLimitReached(request)) {
                RouteSignalResult routeResult = RouteSignalResult.of(RouteTarget.agentRuntime(IntentAgentRuntime.PROVIDER, 0.0,
                        "intent clarification max rounds exceeded"));
                return intentResultAndRouteFrames(request, routeResult, null, latencyMs, "DEGRADED");
            }
            Map<String, Object> clarificationPayload = routeContextAssembler.intentClarificationPayload(
                    request.command(), result);
            return Flux.just(RouteSignalFrame.result(RouteSignalResult.waitingIntentClarification(
                    clarificationPayload,
                    latencyMs,
                    routingPolicy.intentConfidenceThreshold(),
                    result.intentSessionId(),
                    result.intentRequestId())));
        }
        IntentDecision intent = result == null ? null : result.decision();
        if (intent != null && intent.complexity() == TaskComplexity.NEED_CLARIFICATION) {
            if (routeContextAssembler.clarificationRoundLimitReached(request)) {
                RouteSignalResult routeResult = RouteSignalResult.ofIntent(RouteTarget.agentRuntime(IntentAgentRuntime.PROVIDER, 0.0,
                        "intent clarification max rounds exceeded"),
                        intent, latencyMs, routingPolicy.intentConfidenceThreshold());
                return intentResultAndRouteFrames(request, routeResult, intent, latencyMs, "DEGRADED");
            }
            Map<String, Object> clarificationPayload = routeContextAssembler.intentClarificationPayload(
                    request.command(), result);
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
                resultFrameFactory.routeAction(result, intent, route));
    }

    private Flux<RouteSignalFrame> intentFailureFrames(IntentRouteContext request, IntentDecision intent,
                                                       long latencyMs, String reason) {
        IntentDecision failureIntent = intent == null ? degradedIntent(reason) : intent;
        IntentFailureStrategy strategy = properties.intentFailureStrategy();
        RouteTarget route = strategy == IntentFailureStrategy.RELAY_FALLBACK
                ? RouteTarget.agentRuntime(IntentAgentRuntime.PROVIDER, 0.0,
                "intent routing failed, fallback to relay")
                : null;
        RouteSignalResult routeResult = RouteSignalResult.intentFailure(
                route, failureIntent, latencyMs, routingPolicy.intentConfidenceThreshold(),
                new RouteSignalResult.IntentFailure(strategy, reason));
        return intentResultAndRouteFrames(request, routeResult, failureIntent, latencyMs, "DEGRADED");
    }

    private IntentDecision degradedIntent(String reason) {
        return new IntentDecision(
                "finance.runtime.degraded",
                "意图服务不可用",
                TaskComplexity.COMPLEX,
                0.0,
                false,
                null,
                Map.of(),
                List.of(),
                Map.of("source", "intent-agent-degraded", "reason", blankToDefault(reason, "intent routing failed")));
    }

    private RouteSignalProgress progress(String stage, String message, Map<String, Object> attributes) {
        return RouteSignalProgress.of(stage, message, attributes);
    }

    private Flux<RouteSignalFrame> intentResultAndRouteFrames(IntentRouteContext request, RouteSignalResult routeResult,
                                                              IntentDecision intent, long latencyMs, String routeAction) {
        return resultFrameFactory.frames(request, routeResult, intent, latencyMs, routeAction);
    }

    private String intentFailureReason(IntentDecision intent) {
        if (intent == null) {
            return "intent agent returned no decision";
        }
        String reason = firstText(intent.raw().get("reason"), intent.raw().get("message"));
        return blankToDefault(reason, "intent routing failed");
    }

    private UseCaseMatchResult matchUseCase(
            UserContext user,
            IntentSessionSnapshot session,
            IntentCommandSnapshot command,
            List<IntentAttachmentSnapshot> attachments,
            MemoryContext memory) {
        try {
            return useCaseLibraryClient.match(new UseCaseMatchRequest(
                    user.tenantId(), user.ownerUserId(), session.id(), command.message(), attachments, memory,
                    SelectedIntentContext.removeReserved(command.metadata())));
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "Use case route signal failed; degrading to the next route stage")
                    .sessionId(session.id())
                    .operation("use-case.route")
                    .build(), ex);
            return UseCaseMatchResult.notMatched("use case library failed: " + ex.getMessage());
        }
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
