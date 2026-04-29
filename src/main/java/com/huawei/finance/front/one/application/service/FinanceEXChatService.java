package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.facade.FinanceChatFacade;
import com.huawei.finance.front.one.application.gateway.AuthContextProvider;
import com.huawei.finance.front.one.application.gateway.ChatEventStore;
import com.huawei.finance.front.one.application.gateway.IdGenerateContext;
import com.huawei.finance.front.one.application.gateway.IdGenerator;
import com.huawei.finance.front.one.application.gateway.IntentService;
import com.huawei.finance.front.one.application.gateway.UseCaseLibraryClient;
import com.huawei.finance.front.one.application.gateway.UseCaseMatchRequest;
import com.huawei.finance.front.one.domain.agent.AgentBinding;
import com.huawei.finance.front.one.domain.agent.AgentBindingType;
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
import com.huawei.finance.front.one.domain.routing.RouteType;
import com.huawei.finance.front.one.domain.routing.RoutingPolicy;
import com.huawei.finance.front.one.domain.usecase.UseCaseMatchResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 聊天主编排服务：负责把一次前端请求串联成可追踪的 SuperAgent 运行。
 *
 * <p>这是 v2 架构的核心入口。这里不承载具体 SubAgent、AgentRuntime、Redis、openGauss
 * 或外部用例库协议细节，只负责把稳定的业务顺序串起来：
 * 身份校验 -> 会话归一化 -> 上下文装配 -> 多轮绑定续接 -> 用例库匹配 -> 意图识别 -> Agent 调用。</p>
 *
 * <p>路由顺序很重要：active AgentBinding 必须优先于用例库和意图服务，否则用户在多轮任务中补充参数时，
 * 这一轮输入可能被重新分类到另一个 Agent，导致下游任务状态丢失。</p>
 */
@Service
public class FinanceEXChatService implements FinanceChatFacade {
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    private final SessionApplicationService sessionService;
    private final MemoryApplicationService memoryService;
    private final AgentBindingApplicationService bindingService;
    private final UseCaseLibraryClient useCaseLibraryClient;
    private final IntentService intentService;
    private final RoutingPolicy routingPolicy;
    private final SubAgentExecutor subAgentExecutor;
    private final SystemResponseExecutor systemResponseExecutor;
    private final AgentRuntimeExecutor agentRuntimeExecutor;
    private final ChatEventStore eventStore;
    private final IdGenerator idGenerator;

    public FinanceEXChatService(AuthContextProvider auth, PermissionChecker permissionChecker, SessionApplicationService sessionService,
                                MemoryApplicationService memoryService, AgentBindingApplicationService bindingService,
                                UseCaseLibraryClient useCaseLibraryClient, IntentService intentService, RoutingPolicy routingPolicy,
                                SubAgentExecutor subAgentExecutor, SystemResponseExecutor systemResponseExecutor,
                                AgentRuntimeExecutor agentRuntimeExecutor, ChatEventStore eventStore,
                                IdGenerator idGenerator) {
        this.auth = auth; this.permissionChecker = permissionChecker; this.sessionService = sessionService; this.memoryService = memoryService;
        this.bindingService = bindingService; this.useCaseLibraryClient = useCaseLibraryClient;
        this.intentService = intentService; this.routingPolicy = routingPolicy; this.subAgentExecutor = subAgentExecutor;
        this.systemResponseExecutor = systemResponseExecutor;
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

            // MemoryContext 是发给用例库、SubAgent 和 AgentRuntime 的统一上下文快照。
            // 当前用户输入还没有保存进去，避免下游 Runtime 既收到 current message 又在历史里看到重复消息。
            MemoryContext memory = memoryService.loadForRun(normalized);
            if (forceNewTask(normalized.metadata())) {
                // 前端显式要求开启新任务时，先终止当前 binding，再按用例库/意图重新路由。
                // 这个开关用于“换个任务”“从头来”等场景，避免强制粘住旧 Agent。
                bindingService.cancelActive(user.tenantId(), user.userId(), session.id());
            }
            IntentDecision intent = null;
            RouteTarget route;
            AgentBinding binding = null;
            Optional<AgentBinding> activeBinding = bindingService.findActive(user.tenantId(), user.userId(), session.id());
            if (activeBinding.isPresent()) {
                // 多轮续接路径：只刷新 runId/过期时间，不再查用例库和意图服务。
                // SubAgent 和 AgentRuntime 的内部 sessionId 会从 binding 带回下游。
                binding = bindingService.touchForRun(activeBinding.get(), runId);
                route = routeFromBinding(binding);
            } else {
                // 首轮路由优先问用例库。用例库代表“业务已沉淀的确定性场景”，命中后直接到指定 SubAgent。
                UseCaseMatchResult match = matchUseCase(user, session, normalized, attachments, memory);
                route = routingPolicy.decideFromUseCase(match);
                if (route.type() == RouteType.SUB_AGENT) {
                    binding = bindingService.createSubAgentBinding(user.tenantId(), user.userId(), session.id(), runId, route.selectedAgentCode());
                } else {
                    // 用例库未命中再调用意图服务。意图服务只给路由信号，不负责多步骤规划。
                    // 复杂任务、低置信任务、无法映射到 SubAgent 的任务统一交给 AgentRuntime。
                    intent = intentService.recognize(normalized, memory, user);
                    route = routingPolicy.decideFromIntent(normalized, memory, intent, user);
                    if (route.type() == RouteType.SUB_AGENT) {
                        binding = bindingService.createSubAgentBinding(user.tenantId(), user.userId(), session.id(), runId, route.selectedAgentCode());
                    } else if (route.type() == RouteType.AGENT_RUNTIME) {
                        binding = bindingService.createRuntimeBinding(user.tenantId(), user.userId(), session.id(), runId, agentRuntimeExecutor.configuredProvider());
                    }
                }
            }
            IntentDecision selectedIntent = intent;
            RouteTarget selectedRoute = route;
            AtomicReference<AgentBinding> bindingRef = new AtomicReference<>(binding);
            AtomicBoolean taskStatusObserved = new AtomicBoolean(false);
            StringBuilder assistant = new StringBuilder();

            // Java 服务统一保存前端可见用户消息，AgentRuntime 内部记忆只负责自身运行状态。
            sessionService.saveUserMessage(normalized, session);

            // 根据路由结果选择 SubAgent、系统响应或统一 AgentRuntime。
            Flux<ChatEvent> body = switch (selectedRoute.type()) {
                case SUB_AGENT -> subAgentExecutor.execute(normalized, runId, memory, selectedRoute, user, bindingRef.get());
                case SYSTEM_RESPONSE -> systemResponseExecutor.execute(normalized, runId, selectedIntent, selectedRoute);
                case AGENT_RUNTIME -> agentRuntimeExecutor.execute(normalized, runId, memory, selectedIntent, selectedRoute, user, bindingRef.get());
            };

            // 外层统一补齐 run.started/run.completed，接口层只需要转发事件流。
            return Flux.concat(Flux.just(RunStartedEvent.of(runId, session.id())), body,
                            Flux.just(RunCompletedEvent.of(runId, session.id(), runCompletedPayload(selectedRoute, bindingRef.get()))))
                    .doOnNext(event -> {
                        // 事件落库与 assistant 消息拼接都在流式事件到达时完成：
                        // - eventStore 保留运行过程，方便前端断线后追溯。
                        // - assistant 只拼用户可见文本，结束后作为一条完整 assistant 消息保存。
                        eventStore.append(event);
                        if (event instanceof MessageDeltaEvent delta) assistant.append(delta.delta());
                        if (bindingService.observeEvent(bindingRef.get(), event)) {
                            taskStatusObserved.set(true);
                        }
                    })
                    .doOnComplete(() -> {
                        // 完整回复只保存一次，避免前端历史由运行时 provider 各自写入导致重复。
                        if (!assistant.isEmpty()) sessionService.saveAssistantMessage(user.tenantId(), user.userId(), session.id(), assistant.toString());
                        if (!taskStatusObserved.get()) {
                            // 下游没有返回 taskStatus 时使用保守兜底：
                            // SubAgent 默认完成并释放 binding；AgentRuntime 默认保持 active，便于复杂任务继续追问。
                            bindingService.completeIfNoTerminalStatus(bindingRef.get());
                        }
                        memoryService.updateAfterRun(normalized, Map.of("lastRunId", runId));
                    })
                    // 运行期异常转换为协议事件，避免直接中断前端流。
                    .onErrorResume(ex -> Flux.just(ErrorEvent.of(runId, session.id(), "RUN_ERROR", ex.getMessage())));
        });
    }

    private RouteTarget routeFromBinding(AgentBinding binding) {
        // bindingType 是续接时的唯一权威来源；不要再用本轮文本重新判断简单/复杂。
        if (binding.bindingType() == AgentBindingType.SUB_AGENT) {
            return RouteTarget.subAgent(binding.agentCode(), "agent-binding", 1.0, "active subagent binding");
        }
        return RouteTarget.agentRuntime("agent-binding", 1.0, "active runtime binding");
    }

    private UseCaseMatchResult matchUseCase(UserContext user, ChatSession session, ChatCommand command,
                                            List<AttachmentRef> attachments, MemoryContext memory) {
        try {
            return useCaseLibraryClient.match(new UseCaseMatchRequest(
                    user.tenantId(), user.userId(), session.id(), command.message(), attachments, memory, command.metadata()));
        } catch (RuntimeException ex) {
            // 用例库不可用不能阻断聊天主链路；降级为未命中，让意图服务和 AgentRuntime 接手。
            return UseCaseMatchResult.notMatched("use case library failed: " + ex.getMessage());
        }
    }

    private boolean forceNewTask(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("forceNewTask");
        return value instanceof Boolean bool && bool || value instanceof String text && Boolean.parseBoolean(text);
    }

    private Map<String, Object> runCompletedPayload(RouteTarget route, AgentBinding binding) {
        // run.completed 兼容旧协议的 status，同时带出 v2 路由诊断字段，方便前端展示和排障。
        Map<String, Object> base = new java.util.LinkedHashMap<>();
        base.put("status", "COMPLETED");
        base.put("routeType", route.type().name());
        base.put("routeSource", route.routeSource());
        if (route.selectedAgentCode() != null) {
            base.put("agentCode", route.selectedAgentCode());
        }
        if (binding != null) {
            base.put("bindingId", binding.id());
            base.put("bindingType", binding.bindingType().name());
        }
        return base;
    }
}
