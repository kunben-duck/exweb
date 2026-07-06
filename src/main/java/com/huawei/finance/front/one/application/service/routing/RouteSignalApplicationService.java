package com.huawei.finance.front.one.application.service.routing;

import com.huawei.finance.front.one.application.config.RouteSignalProperties;
import com.huawei.finance.front.one.application.integration.intent.IntentService;
import com.huawei.finance.front.one.application.integration.usecase.UseCaseLibraryClient;
import com.huawei.finance.front.one.application.integration.usecase.UseCaseMatchRequest;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.intent.TaskComplexity;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.routing.RouteType;
import com.huawei.finance.front.one.domain.routing.RoutingPolicy;
import com.huawei.finance.front.one.domain.usecase.UseCaseMatchResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
    private final IntentService intentService;
    private final RoutingPolicy routingPolicy;
    private final RouteSignalProperties properties;

    /**
     * 创建可选路由信号编排服务。
     *
     * @param useCaseLibraryClient 用例库 HTTP 端口；仅在配置开启时调用。
     * @param intentService 意图服务 HTTP 端口；仅在配置开启时调用。
     * @param routingPolicy 纯领域路由策略。
     * @param properties 外部路由信号开关配置。
     */
    public RouteSignalApplicationService(UseCaseLibraryClient useCaseLibraryClient, IntentService intentService,
                                         RoutingPolicy routingPolicy, RouteSignalProperties properties) {
        this.useCaseLibraryClient = useCaseLibraryClient;
        this.intentService = intentService;
        this.routingPolicy = routingPolicy;
        this.properties = properties;
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
        if (properties.useCaseLibraryEnabled()) {
            RouteTarget useCaseRoute = routingPolicy.decideFromUseCase(matchUseCase(user, session, command, attachments, memory));
            if (useCaseRoute.type() == RouteType.SUB_AGENT) {
                return RouteSignalResult.of(useCaseRoute);
            }
        }

        if (properties.intentEnabled()) {
            long started = System.nanoTime();
            IntentDecision intent = recognizeIntent(command, memory, user);
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            return RouteSignalResult.ofIntent(routingPolicy.decideFromIntent(command, memory, intent, user),
                    intent, latencyMs, routingPolicy.intentConfidenceThreshold());
        }

        return RouteSignalResult.of(RouteTarget.agentRuntime("route-signal", 0.0,
                "use case library and intent service disabled or not matched"));
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

    private IntentDecision recognizeIntent(ChatCommand command, MemoryContext memory, UserContext user) {
        try {
            return intentService.recognize(command, memory, user);
        } catch (RuntimeException ex) {
            log.warn("Intent route signal failed, degrading to Relay Runtime. tenantId={}, userId={}, sessionId={}, reason={}",
                    user.tenantId(), user.ownerUserId(), command.sessionId(), ex.getMessage());
            /*
             * 保留一次真实调用失败的意图决策快照，方便异步记录服务统计降级样本。
             * RoutingPolicy 会把该低置信复杂任务继续路由到 AgentRuntime。
             */
            return new IntentDecision(
                    "finance.runtime.degraded",
                    "意图服务不可用，转入 AgentRuntime",
                    TaskComplexity.COMPLEX,
                    0.0,
                    false,
                    null,
                    Map.of(),
                    List.of(),
                    Map.of("source", "route-signal-intent-degraded", "reason", ex.getMessage() == null ? "" : ex.getMessage())
            );
        }
    }

}
