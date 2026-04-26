package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.facade.FinanceChatFacade;
import com.huawei.finance.front.one.application.gateway.AuthContextProvider;
import com.huawei.finance.front.one.application.gateway.ChatEventStore;
import com.huawei.finance.front.one.application.gateway.IdGenerateContext;
import com.huawei.finance.front.one.application.gateway.IdGenerator;
import com.huawei.finance.front.one.application.gateway.IntentRecognizer;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatResponseMode;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ErrorEvent;
import com.huawei.finance.front.one.domain.chat.ImMessageType;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.domain.chat.RunCompletedEvent;
import com.huawei.finance.front.one.domain.chat.RunStartedEvent;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.routing.RouteType;
import com.huawei.finance.front.one.domain.routing.RoutingPolicy;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 聊天主编排服务：负责把一次前端请求串联成可追踪的 Agent 运行。
 *
 * <p>这里只编排业务流程，不绑定具体 Agent 引擎、Runtime、存储或鉴权实现；
 * 这些外部能力统一通过 gateway 注入，保持 application 层的稳定边界。</p>
 */
@Service
public class FinanceEXChatService implements FinanceChatFacade {
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    private final SessionApplicationService sessionService;
    private final MemoryApplicationService memoryService;
    private final IntentRecognizer intentRecognizer;
    private final RoutingPolicy routingPolicy;
    private final LocalAgentExecutor localAgentExecutor;
    private final RuntimeRelayService runtimeRelayService;
    private final ChatEventStore eventStore;
    private final IdGenerator idGenerator;

    public FinanceEXChatService(AuthContextProvider auth, PermissionChecker permissionChecker, SessionApplicationService sessionService,
                                MemoryApplicationService memoryService, IntentRecognizer intentRecognizer, RoutingPolicy routingPolicy,
                                LocalAgentExecutor localAgentExecutor, RuntimeRelayService runtimeRelayService, ChatEventStore eventStore,
                                IdGenerator idGenerator) {
        this.auth = auth; this.permissionChecker = permissionChecker; this.sessionService = sessionService; this.memoryService = memoryService;
        this.intentRecognizer = intentRecognizer; this.routingPolicy = routingPolicy; this.localAgentExecutor = localAgentExecutor;
        this.runtimeRelayService = runtimeRelayService; this.eventStore = eventStore; this.idGenerator = idGenerator;
    }

    @Override
    public Flux<ChatEvent> chat(ChatCommand command) {
        // defer 确保每个订阅都会生成独立 runId，避免热流复用导致事件串线。
        return Flux.defer(() -> {
            // 解析调用方身份并完成聊天权限校验，后续所有 gateway 都使用同一份用户上下文。
            UserContext user = auth.resolve(command.tenantId(), command.userId());
            permissionChecker.checkChatPermission(user);

            // 以后 userId 可由服务端 Session 上下文解析；进入 application 后统一以 UserContext 为准。
            ChatCommand identified = new ChatCommand(command.commandId(), user.tenantId(), user.userId(), command.sessionId(), command.conversationId(), command.channel(), command.protocol(), command.messageType(), command.responseMode(), command.message(), command.attachments(), command.metadata());

            // 会话不存在时创建会话；历史 Memory 先排除本轮输入，避免 AgentScope 再接收用户消息时重复。
            ChatSession session = sessionService.loadOrCreate(identified);

            // 对协议字段做兜底归一化，避免下游 Agent/Runtime 处理 null 分支。
            ImMessageType messageType = identified.messageType() == null ? ImMessageType.TEXT : identified.messageType();
            ChatResponseMode responseMode = identified.responseMode() == null ? ChatResponseMode.BLOCK : identified.responseMode();
            List<AttachmentRef> attachments = identified.attachments() == null ? List.of() : identified.attachments();
            ChatCommand normalized = new ChatCommand(identified.commandId(), user.tenantId(), user.userId(), session.id(), identified.conversationId(), identified.channel(), identified.protocol(), messageType, responseMode, identified.message(), attachments, identified.metadata());
            String runId = idGenerator.newId("run", IdGenerateContext.of(user.tenantId(), user.userId(), session.id()));

            // 先装配短期/长期上下文，再做意图识别和路由决策。
            MemoryContext memory = memoryService.loadForRun(normalized);
            IntentDecision intent = intentRecognizer.recognize(normalized, memory, user);
            RouteTarget route = routingPolicy.decide(normalized, memory, intent, user);
            boolean localAgentRoute = route.type() == RouteType.LOCAL_AGENT;
            StringBuilder assistant = new StringBuilder();

            // 本地 Agent 路径由 AgentScope Memory 负责保存 user/assistant；其他路径由应用编排保存。
            if (!localAgentRoute) {
                sessionService.saveUserMessage(normalized, session);
            }

            // 根据路由结果选择本地 Agent、Relay Runtime、澄清或拒绝。
            Flux<ChatEvent> body = switch (route.type()) {
                case LOCAL_AGENT -> localAgentExecutor.execute(normalized, runId, memory, intent, user);
                case RELAY_AGENT -> runtimeRelayService.relay(normalized, runId, memory, intent, user, route.runtimeProtocol());
                case ASK_CLARIFICATION -> Flux.just(MessageDeltaEvent.of(runId, session.id(), "请补充具体业务口径或查询条件。"), MessageCompletedEvent.of(runId, session.id()));
                case REJECT -> Flux.just(ErrorEvent.of(runId, session.id(), "REJECT", route.reason()));
            };

            // 外层统一补齐 run.started/run.completed，接口层只需要转发事件流。
            return Flux.concat(Flux.just(RunStartedEvent.of(runId, session.id())), body, Flux.just(RunCompletedEvent.of(runId, session.id())))
                    .doOnNext(event -> {
                        // 事件落库与 assistant 消息拼接都在流式事件到达时完成。
                        eventStore.append(event);
                        if (event instanceof MessageDeltaEvent delta) assistant.append(delta.delta());
                    })
                    .doOnComplete(() -> {
                        // 完整回复只保存一次；本地 Agent 的回复由 AgentScope Memory 写回项目仓储。
                        if (!localAgentRoute && !assistant.isEmpty()) sessionService.saveAssistantMessage(user.tenantId(), user.userId(), session.id(), assistant.toString());
                        memoryService.updateAfterRun(normalized, Map.of("lastRunId", runId));
                    })
                    // 运行期异常转换为协议事件，避免直接中断前端流。
                    .onErrorResume(ex -> Flux.just(ErrorEvent.of(runId, session.id(), "RUN_ERROR", ex.getMessage())));
        });
    }
}
