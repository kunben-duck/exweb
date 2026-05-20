package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.facade.FinanceChatFacade;
import com.huawei.finance.front.one.application.facade.DocumentFacade;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunMessagePlan;
import com.huawei.finance.front.one.domain.chat.ChatRunStartResult;
import com.huawei.finance.front.one.domain.chat.ChatRunStopDecision;
import com.huawei.finance.front.one.domain.chat.ChatRunStopResult;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatStreamTopics;
import com.huawei.finance.front.one.domain.chat.ErrorEvent;
import com.huawei.finance.front.one.domain.chat.RunCancelledEvent;
import com.huawei.finance.front.one.domain.chat.RunCompletedEvent;
import com.huawei.finance.front.one.domain.chat.RunStartedEvent;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.routing.RouteType;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.Sinks;

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
    private static final Logger log = LoggerFactory.getLogger(FinanceEXChatService.class);

    private final SessionApplicationService sessionService;
    private final MemoryApplicationService memoryService;
    private final RuntimeBindingApplicationService runtimeBindingService;
    private final RouteSignalApplicationService routeSignalService;
    private final SubAgentExecutor subAgentExecutor;
    private final SystemResponseExecutor systemResponseExecutor;
    private final AgentRuntimeExecutor agentRuntimeExecutor;
    private final DocumentFacade documentFacade;
    private final ChatStreamApplicationService chatStreamService;
    private final ChatRunApplicationService chatRunService;
    private final LocalChatRunExecutionRegistry runExecutionRegistry;
    private final RunAdmissionControlService runAdmissionControl;
    private final IdGenerator idGenerator;

    public FinanceEXChatService(SessionApplicationService sessionService,
                                MemoryApplicationService memoryService, RuntimeBindingApplicationService runtimeBindingService,
                                RouteSignalApplicationService routeSignalService,
                                SubAgentExecutor subAgentExecutor, SystemResponseExecutor systemResponseExecutor,
                                AgentRuntimeExecutor agentRuntimeExecutor, DocumentFacade documentFacade, ChatStreamApplicationService chatStreamService,
                                ChatRunApplicationService chatRunService, LocalChatRunExecutionRegistry runExecutionRegistry,
                                RunAdmissionControlService runAdmissionControl, IdGenerator idGenerator) {
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.runtimeBindingService = runtimeBindingService;
        this.routeSignalService = routeSignalService;
        this.subAgentExecutor = subAgentExecutor;
        this.systemResponseExecutor = systemResponseExecutor;
        this.agentRuntimeExecutor = agentRuntimeExecutor;
        this.documentFacade = documentFacade;
        this.chatStreamService = chatStreamService;
        this.chatRunService = chatRunService;
        this.runExecutionRegistry = runExecutionRegistry;
        this.runAdmissionControl = runAdmissionControl;
        this.idGenerator = idGenerator;
    }

    @Override
    public Mono<ChatRunStartResult> startRun(UserContext user, ChatCommand command) {
        return Mono.defer(() -> {
            RunAdmissionControlService.Permit runPermit = runAdmissionControl.acquire(user);
            Sinks.One<ChatEvent> firstEvent = Sinks.one();
            AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            AtomicReference<String> runIdRef = new AtomicReference<>();
            AtomicBoolean terminal = new AtomicBoolean(false);
            Flux<ChatEvent> runFlux = executeRun(user, command)
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(event -> {
                        if (runIdRef.compareAndSet(null, event.runId())) {
                            Disposable disposable = disposableRef.get();
                            if (disposable != null && !terminal.get()) {
                                runExecutionRegistry.register(event.runId(), disposable);
                            }
                        }
                        firstEvent.tryEmitValue(event);
                    })
                    .doFinally(signalType -> {
                        terminal.set(true);
                        runExecutionRegistry.complete(runIdRef.get());
                        runPermit.close();
                    });
            Disposable disposable = runFlux
                    // 异步 run 由服务端订阅并持续执行；前端通过 resume 接口按 seq 读取事件。
                    // 这里不把浏览器连接作为 Runtime 生命周期，避免刷新页面导致运行中断。
                    .subscribe(
                            event -> {
                                // 事件持久化、发布和 firstEvent handoff 都在上游 doOnNext 中完成。
                            },
                            error -> {
                                Sinks.EmitResult result = firstEvent.tryEmitError(error);
                                if (result.isFailure() && runIdRef.get() != null) {
                                    log.warn("Background chat run terminated after handoff. runId={}, reason={}",
                                            runIdRef.get(), error.getMessage(), error);
                                }
                            }
                    );
            disposableRef.set(disposable);
            if (runIdRef.get() != null && !terminal.get()) {
                runExecutionRegistry.register(runIdRef.get(), disposable);
            }
            return firstEvent.asMono()
                    .map(event -> new ChatRunStartResult(event.runId(), event.sessionId(), event.sequence(),
                            event.createdAt(), ChatStreamTopics.runTopic(event.runId())));
        });
    }

    @Override
    public Mono<ChatRunStopResult> stopRun(UserContext user, String runId) {
        return Mono.defer(() -> {
            ChatRunStopDecision decision = chatRunService.requestStop(user, runId, "USER_STOP");
            ChatRun run = decision.run();
            if (!decision.appendCancelledEvent()) {
                return Mono.just(chatRunService.toStopResult(run));
            }
            // 本地 JVM 优先中断当前流订阅；跨 JVM 场景依靠 Redis cancel flag 与下游 cancel API 尽力收敛。
            runExecutionRegistry.cancel(run.id());
            cancelDownstream(run, user)
                    .onErrorResume(ex -> Mono.empty())
                    .subscribe();
            ChatEvent cancelEvent = RunCancelledEvent.of(run.id(), run.sessionId(), run.cancelReason());
            if (!chatRunService.shouldAcceptEvent(cancelEvent)) {
                return Mono.just(chatRunService.toStopResult(run));
            }
            ChatEvent cancelled = chatStreamService.appendAndPublish(cancelEvent);
            ChatRun latest = chatRunService.observeEvent(cancelled);
            return Mono.just(chatRunService.toStopResult(latest == null ? run : latest));
        });
    }

    @Override
    public Flux<ChatEvent> executeRun(UserContext user, ChatCommand command) {
        // defer 确保每个订阅都会生成独立 runId，避免热流复用导致事件串线。
        return Flux.defer(() -> {
            // 进入 application 后统一以 UserContext 为准；原始前端请求只保留会话、消息、附件和元数据。
            ChatCommand identified = new ChatCommand(command.commandId(), user.tenantId(), user.userId(),
                    command.sessionId(), command.conversationId(), command.channel(), command.message(),
                    command.attachments(), command.metadata(), command.runMode(), command.parentMessageId(),
                    command.editedMessageId(), command.regeneratedMessageId());

            // 会话不存在时创建会话；历史 Memory 先排除本轮输入，避免 Runtime 再接收用户消息时重复。
            ChatSession session = sessionService.loadOrCreate(identified);
            // 同一会话同一时刻只允许一个 active run。这里在写入用户消息前快速拒绝，
            // 避免多页签重复提交时先污染消息树；createRunning 仍会再做一次 Redis 原子声明。
            chatRunService.rejectIfActiveRunExists(user, session.id());

            List<AttachmentRef> attachments = documentFacade.resolveAttachmentsForUser(user,
                    identified.attachments() == null ? List.of() : identified.attachments());
            ChatCommand normalized = new ChatCommand(identified.commandId(), user.tenantId(), user.userId(),
                    session.id(), identified.conversationId(), identified.channel(), identified.message(),
                    attachments, identified.metadata(), identified.runMode(), identified.parentMessageId(),
                    identified.editedMessageId(), identified.regeneratedMessageId());
            String runId = idGenerator.newId("run", IdGenerateContext.of(user.tenantId(), user.userId(), session.id()));

            // MemoryContext 是可选 SuperAgent 记忆增强。长短期记忆都关闭时这里返回空上下文，
            // 且不会查询 Redis、历史消息或长期记忆服务；当前用户输入也不会进入本轮上下文，避免重复。
            MemoryContext memory = memoryService.loadForRun(normalized);
            ChatRunMessagePlan messagePlan = sessionService.prepareRunMessage(user, normalized, session, runId, attachments);
            ChatCommand runCommand = commandForExecution(normalized, messagePlan);
            String runtimeBindingLeafId = runtimeBindingLeafId(messagePlan);
            if (forceNewTask(normalized.metadata())) {
                // 前端显式要求开启新任务时，仅取消 Relay Runtime 续接绑定。
                // SubAgent 不创建绑定，所以不存在需要释放的简单任务粘性会话。
                runtimeBindingService.cancelActive(user.tenantId(), user.userId(), session.id());
            }
            IntentDecision intent = null;
            RouteTarget route = null;
            RuntimeBinding binding = null;
            Optional<RuntimeBinding> activeRuntimeBinding = runtimeBindingService.findActive(user.tenantId(),
                    user.userId(), session.id(), runtimeBindingLeafId);
            if (activeRuntimeBinding.isPresent()) {
                // Runtime 是唯一允许多轮续接的执行主体；命中 active binding 后直接继续 Relay Runtime。
                binding = runtimeBindingService.touchForRun(activeRuntimeBinding.get(), runId);
                route = RouteTarget.agentRuntime("runtime-binding", 1.0, "active relay runtime binding");
            }
            if (route == null) {
                // 首轮路由只读取已启用的外部路由信号。默认用例库和意图服务都关闭，
                // 此时不会发生外部 HTTP 调用，请求直接进入 AgentRuntime。
                RouteSignalResult routeSignal = routeSignalService.routeInitial(user, session, runCommand, attachments, memory);
                route = routeSignal.route();
                intent = routeSignal.intentDecision();
                if (route.type() == RouteType.SUB_AGENT) {
                    binding = null;
                } else if (route.type() == RouteType.AGENT_RUNTIME) {
                    binding = runtimeBindingService.create(user.tenantId(), user.userId(), session.id(), runId,
                            runtimeBindingLeafId);
                }
            }
            IntentDecision selectedIntent = intent;
            RouteTarget selectedRoute = route;
            AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>(binding);
            StringBuilder assistant = new StringBuilder();
            chatRunService.createRunning(runId, user, session.id(), selectedRoute, bindingRef.get(),
                    normalized.metadata(), messagePlan.runMode(), messagePlan.parentMessageId(), messagePlan.userMessage().id());
            try {
                // 根据路由结果选择 SubAgent、系统响应或统一 AgentRuntime。
                Flux<ChatEvent> body = switch (selectedRoute.type()) {
                    case SUB_AGENT -> subAgentExecutor.execute(runCommand, runId, memory, selectedRoute, user);
                    case SYSTEM_RESPONSE -> systemResponseExecutor.execute(runCommand, runId, selectedIntent, selectedRoute);
                    case AGENT_RUNTIME -> agentRuntimeExecutor.execute(runCommand, runId, memory, selectedIntent, selectedRoute, user, bindingRef.get());
                };

                // 外层统一补齐 run.started/run.completed，接口层只需要转发事件流。
                // onErrorResume 必须放在持久化之前，确保运行异常转换出的 run.failed 事件也会落库。
                return persistAndPublishRunEvents(
                        Flux.concat(Flux.just(RunStartedEvent.of(runId, session.id())), body,
                                        Flux.just(RunCompletedEvent.of(runId, session.id(), runCompletedPayload(selectedRoute, bindingRef.get()))))
                                .onErrorResume(ex -> Flux.just(ErrorEvent.of(runId, session.id(), "RUN_ERROR", ex.getMessage()))),
                        user,
                        session,
                        runCommand,
                        messagePlan,
                        bindingRef,
                        assistant,
                        runId
                );
            } catch (RuntimeException ex) {
                // run 已创建后同步步骤失败时，也必须写入 run.failed 并释放 active run，避免前端看到永远 RUNNING。
                return persistAndPublishRunEvents(
                        Flux.just(ErrorEvent.of(runId, session.id(), "RUN_ERROR", ex.getMessage())),
                        user,
                        session,
                        runCommand,
                        messagePlan,
                        bindingRef,
                        assistant,
                        runId
                );
            }
        });
    }

    private Flux<ChatEvent> persistAndPublishRunEvents(Flux<ChatEvent> events, UserContext user, ChatSession session,
                                                       ChatCommand normalized, ChatRunMessagePlan messagePlan,
                                                       AtomicReference<RuntimeBinding> bindingRef,
                                                       StringBuilder assistant, String runId) {
        return events
                .takeWhile(chatRunService::shouldAcceptEvent)
                .concatMap(event -> {
                    appendAssistantDelta(assistant, event);
                    /*
                     * run.completed 是前端、SSE resume 和跨设备续接共同认可的“本轮回答已经闭合”信号。
                     * 因此在发布该终态事件之前，必须先把完整 assistant 消息写入历史消息树，
                     * 避免客户端收到 completed 后立即查询历史时只能看到 user 节点。
                     */
                    if ("run.completed".equals(event.type()) && !assistant.isEmpty()) {
                        ChatMessage savedAssistant = sessionService.saveAssistantMessage(user.tenantId(), user.userId(),
                                session, assistant.toString(), runId, messagePlan.userMessage().id(),
                                messagePlan.regeneratedFromMessageId());
                        chatRunService.bindAssistantMessage(runId, savedAssistant.id());
                        bindingRef.set(runtimeBindingService.moveToLeaf(bindingRef.get(), savedAssistant.id()));
                    }
                    ChatEvent stored = chatStreamService.appendAndPublish(event);
                    // 事件已经带有 openGauss 持久化 seq，实时输出与断线补发看到的是同一份顺序。
                    chatRunService.observeEvent(stored);
                    bindingRef.set(runtimeBindingService.observeEvent(bindingRef.get(), stored));
                    return Mono.just(stored);
                });
    }

    /**
     * 将消息树写入计划转换为真正下发给 Runtime/SubAgent 的命令。
     *
     * <p>普通提问和编辑历史问题使用本轮新用户消息；重新生成回答时不创建新的 user 消息，
     * 因此要把原 user 消息内容作为本轮 query 传给下游，保证 Runtime 看到的输入和消息树一致。</p>
     */
    private ChatCommand commandForExecution(ChatCommand normalized, ChatRunMessagePlan messagePlan) {
        ChatMessage userMessage = messagePlan.userMessage();
        return new ChatCommand(normalized.commandId(), normalized.tenantId(), normalized.userId(),
                normalized.sessionId(), normalized.conversationId(), normalized.channel(), userMessage.content(),
                normalized.attachments(), normalized.metadata(), messagePlan.runMode(), messagePlan.parentMessageId(),
                normalized.editedMessageId(), normalized.regeneratedMessageId());
    }

    /**
     * 计算 RuntimeBinding 的查询 leaf。
     *
     * <p>普通继续提问应复用“提问前 active leaf”上的 Runtime session；编辑历史问题和重新生成回答
     * 会从历史路径产生新的候选分支，因此先绑定到本轮 user leaf，完成后再移动到新 assistant leaf。</p>
     */
    private String runtimeBindingLeafId(ChatRunMessagePlan messagePlan) {
        return switch (messagePlan.runMode()) {
            case NEXT -> messagePlan.parentMessageId();
            case EDIT_USER, REGENERATE_ASSISTANT -> messagePlan.userMessage().id();
        };
    }

    private boolean forceNewTask(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("forceNewTask");
        return value instanceof Boolean bool && bool || value instanceof String text && Boolean.parseBoolean(text);
    }

    private Mono<Void> cancelDownstream(ChatRun run, UserContext user) {
        if (run == null || run.routeType() == null) {
            return Mono.empty();
        }
        if (RouteType.AGENT_RUNTIME.name().equals(run.routeType())) {
            return agentRuntimeExecutor.cancel(run, user);
        }
        if (RouteType.SUB_AGENT.name().equals(run.routeType())) {
            return subAgentExecutor.cancel(run, user);
        }
        return Mono.empty();
    }

    private Map<String, Object> runCompletedPayload(RouteTarget route, RuntimeBinding binding) {
        // run.completed 带出标准 status 和 v3 路由诊断字段，方便前端展示和排障。
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

    private void appendAssistantDelta(StringBuilder assistant, ChatEvent event) {
        if (!"message.delta".equals(event.type()) || event.payload() == null) {
            return;
        }
        Object delta = event.payload().get("delta");
        if (delta != null) {
            assistant.append(delta);
        }
    }
}
