package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.facade.FinanceChatFacade;
import com.huawei.finance.front.one.application.gateway.AuthContextProvider;
import com.huawei.finance.front.one.application.gateway.ChatEventStore;
import com.huawei.finance.front.one.application.gateway.IdGenerateContext;
import com.huawei.finance.front.one.application.gateway.IdGenerator;
import com.huawei.finance.front.one.application.gateway.IntentService;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatResponseMode;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ErrorEvent;
import com.huawei.finance.front.one.domain.chat.ImMessageType;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.domain.chat.RunCompletedEvent;
import com.huawei.finance.front.one.domain.chat.RunStartedEvent;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
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
    private final IntentService intentService;
    private final RoutingPolicy routingPolicy;
    private final DirectTaskExecutor directTaskExecutor;
    private final AgentRuntimeExecutor agentRuntimeExecutor;
    private final ChatEventStore eventStore;
    private final IdGenerator idGenerator;

    public FinanceEXChatService(AuthContextProvider auth, PermissionChecker permissionChecker, SessionApplicationService sessionService,
                                MemoryApplicationService memoryService, IntentService intentService, RoutingPolicy routingPolicy,
                                DirectTaskExecutor directTaskExecutor, AgentRuntimeExecutor agentRuntimeExecutor, ChatEventStore eventStore,
                                IdGenerator idGenerator) {
        this.auth = auth; this.permissionChecker = permissionChecker; this.sessionService = sessionService; this.memoryService = memoryService;
        this.intentService = intentService; this.routingPolicy = routingPolicy; this.directTaskExecutor = directTaskExecutor;
        this.agentRuntimeExecutor = agentRuntimeExecutor; this.eventStore = eventStore; this.idGenerator = idGenerator;
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
            IntentDecision intent = intentService.recognize(normalized, memory, user);
            RouteTarget route = routingPolicy.decide(normalized, memory, intent, user);
            StringBuilder assistant = new StringBuilder();

            // Java 服务统一保存前端可见用户消息，AgentRuntime 内部记忆只负责自身运行状态。
            sessionService.saveUserMessage(normalized, session);

            // 根据路由结果选择直接工具、直接模型或统一 AgentRuntime。
            Flux<ChatEvent> body = switch (route.type()) {
                case DIRECT_TOOL -> directTaskExecutor.executeTool(normalized, runId, intent, route, user);
                case DIRECT_MODEL -> directTaskExecutor.executeModel(normalized, runId, intent);
                case AGENT_RUNTIME -> agentRuntimeExecutor.execute(normalized, runId, memory, intent, route, user);
            };

            // 外层统一补齐 run.started/run.completed，接口层只需要转发事件流。
            return Flux.concat(Flux.just(RunStartedEvent.of(runId, session.id())), body, Flux.just(RunCompletedEvent.of(runId, session.id())))
                    .doOnNext(event -> {
                        // 事件落库与 assistant 消息拼接都在流式事件到达时完成。
                        eventStore.append(event);
                        if (event instanceof MessageDeltaEvent delta) assistant.append(delta.delta());
                    })
                    .doOnComplete(() -> {
                        // 完整回复只保存一次，避免前端历史由运行时 provider 各自写入导致重复。
                        if (!assistant.isEmpty()) sessionService.saveAssistantMessage(user.tenantId(), user.userId(), session.id(), assistant.toString());
                        memoryService.updateAfterRun(normalized, Map.of("lastRunId", runId));
                    })
                    // 运行期异常转换为协议事件，避免直接中断前端流。
                    .onErrorResume(ex -> Flux.just(ErrorEvent.of(runId, session.id(), "RUN_ERROR", ex.getMessage())));
        });
    }
}
