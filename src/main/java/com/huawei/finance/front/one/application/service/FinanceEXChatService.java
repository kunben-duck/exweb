package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.facade.FinanceChatFacade;
import com.huawei.finance.front.one.application.integration.identity.AuthContextProvider;
import com.huawei.finance.front.one.application.integration.conversation.ChatEventStore;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
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
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 聊天主编排服务：负责把一次前端请求串联成可追踪的 SuperAgent 运行。
 *
 * <p>这是 v3 架构的核心入口。这里不承载具体 SubAgent、AgentRuntime、Redis、openGauss
 * 或外部路由信号协议细节，只负责把稳定的业务顺序串起来：
 * 身份校验 -> 会话归一化 -> 上下文装配 -> Runtime 续接 -> 可选路由信号 -> Agent 调用。</p>
 *
 * <p>只有 Relay Runtime 具备多轮保持能力。SubAgent 只承接用例库或意图服务命中的简单任务，
 * 并且只执行一轮，不创建绑定、不维护任务状态。</p>
 */
@Service
public class FinanceEXChatService implements FinanceChatFacade {
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    private final SessionApplicationService sessionService;
    private final MemoryApplicationService memoryService;
    private final RuntimeBindingApplicationService runtimeBindingService;
    private final RouteSignalApplicationService routeSignalService;
    private final SubAgentExecutor subAgentExecutor;
    private final SystemResponseExecutor systemResponseExecutor;
    private final AgentRuntimeExecutor agentRuntimeExecutor;
    private final ChatEventStore eventStore;
    private final IdGenerator idGenerator;

    public FinanceEXChatService(AuthContextProvider auth, PermissionChecker permissionChecker, SessionApplicationService sessionService,
                                MemoryApplicationService memoryService, RuntimeBindingApplicationService runtimeBindingService,
                                RouteSignalApplicationService routeSignalService,
                                SubAgentExecutor subAgentExecutor, SystemResponseExecutor systemResponseExecutor,
                                AgentRuntimeExecutor agentRuntimeExecutor, ChatEventStore eventStore,
                                IdGenerator idGenerator) {
        this.auth = auth; this.permissionChecker = permissionChecker; this.sessionService = sessionService; this.memoryService = memoryService;
        this.runtimeBindingService = runtimeBindingService; this.routeSignalService = routeSignalService;
        this.subAgentExecutor = subAgentExecutor;
        this.systemResponseExecutor = systemResponseExecutor;
        this.agentRuntimeExecutor = agentRuntimeExecutor; this.eventStore = eventStore; this.idGenerator = idGenerator;
    }

    @Override
    public Flux<ChatEvent> chat(ChatCommand command) {
        // defer 确保每个订阅都会生成独立 runId，避免热流复用导致事件串线。
        return Flux.defer(() -> {
            // 解析调用方身份并完成聊天权限校验，后续所有 integration 都使用同一份用户上下文。
            // tenantId/userId 不再来自前端协议层，而是由应用身份防腐层从当前服务端上下文解析。
            UserContext user = auth.resolve();
            permissionChecker.checkChatPermission(user);

            // 进入 application 后统一以 UserContext 为准；原始前端请求只保留会话、协议、消息和附件信息。
            ChatCommand identified = new ChatCommand(command.commandId(), user.tenantId(), user.userId(), command.sessionId(), command.conversationId(), command.channel(), command.protocol(), command.messageType(), command.responseMode(), command.message(), command.attachments(), command.metadata());

            // 会话不存在时创建会话；历史 Memory 先排除本轮输入，避免 Runtime 再接收用户消息时重复。
            ChatSession session = sessionService.loadOrCreate(identified);

            // 对协议字段做兜底归一化，避免下游 Agent/Runtime 处理 null 分支。
            ImMessageType messageType = identified.messageType() == null ? ImMessageType.TEXT : identified.messageType();
            ChatResponseMode responseMode = identified.responseMode() == null ? ChatResponseMode.BLOCK : identified.responseMode();
            List<AttachmentRef> attachments = identified.attachments() == null ? List.of() : identified.attachments();
            ChatCommand normalized = new ChatCommand(identified.commandId(), user.tenantId(), user.userId(), session.id(), identified.conversationId(), identified.channel(), identified.protocol(), messageType, responseMode, identified.message(), attachments, identified.metadata());
            String runId = idGenerator.newId("run", IdGenerateContext.of(user.tenantId(), user.userId(), session.id()));

            // MemoryContext 是发给可选路由信号、SubAgent 和 AgentRuntime 的统一上下文快照。
            // 当前用户输入还没有保存进去，避免下游 Runtime 既收到 current message 又在历史里看到重复消息。
            MemoryContext memory = memoryService.loadForRun(normalized);
            if (forceNewTask(normalized.metadata())) {
                // 前端显式要求开启新任务时，仅取消 Relay Runtime 续接绑定。
                // SubAgent 不创建绑定，所以不存在需要释放的简单任务粘性会话。
                runtimeBindingService.cancelActive(user.tenantId(), user.userId(), session.id());
            }
            IntentDecision intent = null;
            RouteTarget route = null;
            RuntimeBinding binding = null;
            Optional<RuntimeBinding> activeRuntimeBinding = runtimeBindingService.findActive(user.tenantId(), user.userId(), session.id());
            if (activeRuntimeBinding.isPresent()) {
                // Runtime 是唯一允许多轮续接的执行主体；命中 active binding 后直接继续 Relay Runtime。
                binding = runtimeBindingService.touchForRun(activeRuntimeBinding.get(), runId);
                route = RouteTarget.agentRuntime("runtime-binding", 1.0, "active relay runtime binding");
            }
            if (route == null) {
                // 首轮路由只读取已启用的外部路由信号。默认用例库和意图服务都关闭，
                // 此时不会发生外部 HTTP 调用，请求直接进入 AgentRuntime。
                RouteSignalResult routeSignal = routeSignalService.routeInitial(user, session, normalized, attachments, memory);
                route = routeSignal.route();
                intent = routeSignal.intentDecision();
                if (route.type() == RouteType.SUB_AGENT) {
                    binding = null;
                } else if (route.type() == RouteType.AGENT_RUNTIME) {
                    binding = runtimeBindingService.create(user.tenantId(), user.userId(), session.id(), runId);
                }
            }
            IntentDecision selectedIntent = intent;
            RouteTarget selectedRoute = route;
            AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>(binding);
            StringBuilder assistant = new StringBuilder();

            // Java 服务统一保存前端可见用户消息，AgentRuntime 内部记忆只负责自身运行状态。
            sessionService.saveUserMessage(normalized, session);

            // 根据路由结果选择 SubAgent、系统响应或统一 AgentRuntime。
            Flux<ChatEvent> body = switch (selectedRoute.type()) {
                case SUB_AGENT -> subAgentExecutor.execute(normalized, runId, memory, selectedRoute, user);
                case SYSTEM_RESPONSE -> systemResponseExecutor.execute(normalized, runId, selectedIntent, selectedRoute);
                case AGENT_RUNTIME -> agentRuntimeExecutor.execute(normalized, runId, memory, selectedIntent, selectedRoute, user, bindingRef.get());
            };

            // 外层统一补齐 run.started/run.completed，接口层只需要转发事件流。
            // onErrorResume 必须放在 doOnNext 之前，确保运行异常转换出的 run.failed 事件也会落库。
            return Flux.concat(Flux.just(RunStartedEvent.of(runId, session.id())), body,
                            Flux.just(RunCompletedEvent.of(runId, session.id(), runCompletedPayload(selectedRoute, bindingRef.get()))))
                    .onErrorResume(ex -> Flux.just(ErrorEvent.of(runId, session.id(), "RUN_ERROR", ex.getMessage())))
                    .doOnNext(event -> {
                        // 事件落库与 assistant 消息拼接都在流式事件到达时完成：
                        // - eventStore 保留运行过程，方便前端断线后追溯。
                        // - assistant 只拼用户可见文本，结束后作为一条完整 assistant 消息保存。
                        eventStore.append(event);
                        if (event instanceof MessageDeltaEvent delta) assistant.append(delta.delta());
                        bindingRef.set(runtimeBindingService.observeEvent(bindingRef.get(), event));
                    })
                    .doOnComplete(() -> {
                        // 完整回复只保存一次，避免前端历史由运行时 provider 各自写入导致重复。
                        if (!assistant.isEmpty()) sessionService.saveAssistantMessage(user.tenantId(), user.userId(), session.id(), assistant.toString());
                        memoryService.updateAfterRun(normalized, Map.of("lastRunId", runId));
                    });
        });
    }

    private boolean forceNewTask(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("forceNewTask");
        return value instanceof Boolean bool && bool || value instanceof String text && Boolean.parseBoolean(text);
    }

    private Map<String, Object> runCompletedPayload(RouteTarget route, RuntimeBinding binding) {
        // run.completed 兼容旧协议的 status，同时带出 v3 路由诊断字段，方便前端展示和排障。
        Map<String, Object> base = new java.util.LinkedHashMap<>();
        base.put("status", "COMPLETED");
        base.put("routeType", route.type().name());
        base.put("routeSource", route.routeSource());
        if (route.selectedAgentCode() != null) {
            base.put("agentCode", route.selectedAgentCode());
        }
        if (binding != null) {
            base.put("runtimeBindingId", binding.id());
            base.put("runtimeProvider", binding.provider());
            if (binding.runtimeSessionId() != null) {
                base.put("runtimeSessionId", binding.runtimeSessionId());
            }
        }
        return base;
    }
}
