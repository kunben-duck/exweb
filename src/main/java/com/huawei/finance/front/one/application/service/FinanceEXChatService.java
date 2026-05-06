package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.facade.FinanceChatFacade;
import com.huawei.finance.front.one.application.integration.identity.AuthContextProvider;
import com.huawei.finance.front.one.application.integration.conversation.ChatEventStore;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.intent.IntentService;
import com.huawei.finance.front.one.application.integration.usecase.UseCaseLibraryClient;
import com.huawei.finance.front.one.application.integration.usecase.UseCaseMatchRequest;
import com.huawei.finance.front.one.domain.agent.AgentBinding;
import com.huawei.finance.front.one.domain.agent.AgentBindingStatus;
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
import com.huawei.finance.front.one.domain.task.TaskCard;
import com.huawei.finance.front.one.domain.task.TaskStatus;
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
 * <p>路由顺序很重要：active AgentBinding 只代表“存在未完成任务索引”。SubAgent 续接必须先经过
 * TaskCard 和 ContinuationGuard，避免用户已经切换问题时仍被强制粘到旧 SubAgent。</p>
 */
@Service
public class FinanceEXChatService implements FinanceChatFacade {
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    private final SessionApplicationService sessionService;
    private final MemoryApplicationService memoryService;
    private final AgentBindingApplicationService bindingService;
    private final TaskCardApplicationService taskCardService;
    private final TaskOrchestrationApplicationService taskOrchestrationService;
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
                                TaskCardApplicationService taskCardService,
                                TaskOrchestrationApplicationService taskOrchestrationService,
                                UseCaseLibraryClient useCaseLibraryClient, IntentService intentService, RoutingPolicy routingPolicy,
                                SubAgentExecutor subAgentExecutor, SystemResponseExecutor systemResponseExecutor,
                                AgentRuntimeExecutor agentRuntimeExecutor, ChatEventStore eventStore,
                                IdGenerator idGenerator) {
        this.auth = auth; this.permissionChecker = permissionChecker; this.sessionService = sessionService; this.memoryService = memoryService;
        this.bindingService = bindingService; this.taskCardService = taskCardService;
        this.taskOrchestrationService = taskOrchestrationService; this.useCaseLibraryClient = useCaseLibraryClient;
        this.intentService = intentService; this.routingPolicy = routingPolicy; this.subAgentExecutor = subAgentExecutor;
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
                taskCardService.findActive(user.tenantId(), user.userId(), session.id())
                        .ifPresent(task -> taskCardService.transition(task, TaskStatus.CANCELLED, TaskStatus.CANCELLED,
                                runId, "TASK_CANCELLED_BY_FORCE_NEW", Map.of("reason", "metadata.forceNewTask")));
                bindingService.cancelActive(user.tenantId(), user.userId(), session.id());
            }
            IntentDecision intent = null;
            RouteTarget route = null;
            AgentBinding binding = null;
            TaskCard taskCard = null;
            Optional<AgentBinding> activeBinding = bindingService.findActive(user.tenantId(), user.userId(), session.id());
            if (activeBinding.isPresent()) {
                Optional<ActiveTaskResolution> resolution = taskOrchestrationService.resolveActive(activeBinding.get(),
                        normalized, runId, () -> shadowRoute(user, session, normalized, attachments, memory));
                if (resolution.isPresent() && !resolution.get().routeNew()) {
                    route = resolution.get().route();
                    binding = resolution.get().binding();
                    taskCard = resolution.get().taskCard();
                }
            }
            if (route == null) {
                // 首轮路由优先问用例库。用例库代表“业务已沉淀的确定性场景”，命中后直接到指定 SubAgent。
                UseCaseMatchResult match = matchUseCase(user, session, normalized, attachments, memory);
                route = routingPolicy.decideFromUseCase(match);
                if (route.type() == RouteType.SUB_AGENT) {
                    binding = bindingService.createSubAgentBinding(user.tenantId(), user.userId(), session.id(), runId, route.selectedAgentCode());
                    taskCard = taskCardService.createForSubAgent(binding, route, runId);
                } else {
                    // 用例库未命中再调用意图服务。意图服务只给路由信号，不负责多步骤规划。
                    // 复杂任务、低置信任务、无法映射到 SubAgent 的任务统一交给 AgentRuntime。
                    intent = intentService.recognize(normalized, memory, user);
                    route = routingPolicy.decideFromIntent(normalized, memory, intent, user);
                    if (route.type() == RouteType.SUB_AGENT) {
                        binding = bindingService.createSubAgentBinding(user.tenantId(), user.userId(), session.id(), runId, route.selectedAgentCode());
                        taskCard = taskCardService.createForSubAgent(binding, route, runId);
                    } else if (route.type() == RouteType.AGENT_RUNTIME) {
                        binding = bindingService.createRuntimeBinding(user.tenantId(), user.userId(), session.id(), runId, agentRuntimeExecutor.configuredProvider());
                    }
                }
            }
            IntentDecision selectedIntent = intent;
            RouteTarget selectedRoute = route;
            AtomicReference<AgentBinding> bindingRef = new AtomicReference<>(binding);
            AtomicReference<TaskCard> taskCardRef = new AtomicReference<>(taskCard);
            AtomicBoolean taskStatusObserved = new AtomicBoolean(false);
            StringBuilder assistant = new StringBuilder();

            // Java 服务统一保存前端可见用户消息，AgentRuntime 内部记忆只负责自身运行状态。
            sessionService.saveUserMessage(normalized, session);

            // 根据路由结果选择 SubAgent、系统响应或统一 AgentRuntime。
            Flux<ChatEvent> body = switch (selectedRoute.type()) {
                case SUB_AGENT -> subAgentExecutor.execute(normalized, runId, memory, selectedRoute, user, bindingRef.get(), taskCardRef.get());
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
                        if (taskCardService.observeEvent(taskCardRef.get(), event)) {
                            taskStatusObserved.set(true);
                        }
                    })
                    .doOnComplete(() -> {
                        // 完整回复只保存一次，避免前端历史由运行时 provider 各自写入导致重复。
                        if (!assistant.isEmpty()) sessionService.saveAssistantMessage(user.tenantId(), user.userId(), session.id(), assistant.toString());
                        if (!taskStatusObserved.get()) {
                            if (taskCardRef.get() != null && selectedRoute.type() == RouteType.SUB_AGENT) {
                                // SubAgent 没有返回 taskStatus 时不能默认完成或继续粘住，转入用户确认。
                                taskCardService.markWaitingConfirmationIfNoStatus(taskCardRef.get(), runId);
                                bindingService.updateStatus(bindingRef.get(), AgentBindingStatus.WAITING_USER_CONFIRMATION);
                            } else {
                                // AgentRuntime 负责复杂任务规划，默认保持 active，便于复杂任务继续追问。
                                bindingService.completeIfNoTerminalStatus(bindingRef.get());
                            }
                        }
                        memoryService.updateAfterRun(normalized, Map.of("lastRunId", runId));
                    })
                    // 运行期异常转换为协议事件，避免直接中断前端流。
                    .onErrorResume(ex -> Flux.just(ErrorEvent.of(runId, session.id(), "RUN_ERROR", ex.getMessage())));
        });
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

    private RouteTarget shadowRoute(UserContext user, ChatSession session, ChatCommand command,
                                    List<AttachmentRef> attachments, MemoryContext memory) {
        UseCaseMatchResult match = matchUseCase(user, session, command, attachments, memory);
        RouteTarget useCaseRoute = routingPolicy.decideFromUseCase(match);
        if (useCaseRoute.type() == RouteType.SUB_AGENT) {
            return useCaseRoute;
        }
        try {
            IntentDecision shadowIntent = intentService.recognize(command, memory, user);
            return routingPolicy.decideFromIntent(command, memory, shadowIntent, user);
        } catch (RuntimeException ex) {
            return RouteTarget.agentRuntime("shadow-route", 0.0, "shadow route failed: " + ex.getMessage());
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
