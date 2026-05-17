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
    private final IdGenerator idGenerator;

    public FinanceEXChatService(SessionApplicationService sessionService,
                                MemoryApplicationService memoryService, RuntimeBindingApplicationService runtimeBindingService,
                                RouteSignalApplicationService routeSignalService,
                                SubAgentExecutor subAgentExecutor, SystemResponseExecutor systemResponseExecutor,
                                AgentRuntimeExecutor agentRuntimeExecutor, DocumentFacade documentFacade, ChatStreamApplicationService chatStreamService,
                                ChatRunApplicationService chatRunService, LocalChatRunExecutionRegistry runExecutionRegistry,
                                IdGenerator idGenerator) {
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
        this.idGenerator = idGenerator;
    }

    @Override
    public Mono<ChatRunStartResult> startRun(UserContext user, ChatCommand command) {
        return Mono.defer(() -> {
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
                    .doOnError(firstEvent::tryEmitError)
                    .doFinally(signalType -> {
                        terminal.set(true);
                        runExecutionRegistry.complete(runIdRef.get());
                    });
            Disposable disposable = runFlux
                    // 异步 run 由服务端订阅并持续执行；前端通过 resume 接口按 seq 读取事件。
                    // 这里不把浏览器连接作为 Runtime 生命周期，避免刷新页面导致运行中断。
                    .subscribe();
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
    public Mono<ChatRunStartResult> retryRun(UserContext user, String runId, ChatCommand command) {
        return Mono.defer(() -> {
            ChatRun previous = chatRunService.requireOwnedRun(user, runId);
            String message = command == null ? null : command.message();
            if (message == null || message.isBlank()) {
                message = sessionService.latestUserMessage(user.tenantId(), user.userId(), previous.sessionId())
                        .map(ChatMessage::content)
                        .orElseThrow(() -> new IllegalStateException("原会话不存在可重试的用户消息"));
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (command != null && command.metadata() != null) {
                metadata.putAll(command.metadata());
            }
            metadata.put("retryOfRunId", runId);
            ChatCommand retryCommand = new ChatCommand(
                    command == null ? null : command.commandId(),
                    null,
                    null,
                    previous.sessionId(),
                    command == null ? null : command.conversationId(),
                    command == null ? null : command.channel(),
                    message,
                    command == null ? List.of() : command.attachments(),
                    metadata
            );
            return startRun(user, retryCommand);
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
                    command.attachments(), command.metadata());

            // 会话不存在时创建会话；历史 Memory 先排除本轮输入，避免 Runtime 再接收用户消息时重复。
            ChatSession session = sessionService.loadOrCreate(identified);

            List<AttachmentRef> attachments = documentFacade.resolveAttachmentsForUser(user,
                    identified.attachments() == null ? List.of() : identified.attachments());
            ChatCommand normalized = new ChatCommand(identified.commandId(), user.tenantId(), user.userId(),
                    session.id(), identified.conversationId(), identified.channel(), identified.message(),
                    attachments, identified.metadata());
            String runId = idGenerator.newId("run", IdGenerateContext.of(user.tenantId(), user.userId(), session.id()));

            // MemoryContext 是可选 SuperAgent 记忆增强。长短期记忆都关闭时这里返回空上下文，
            // 且不会查询 Redis、历史消息或长期记忆服务；当前用户输入也不会进入本轮上下文，避免重复。
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
            AtomicBoolean completed = new AtomicBoolean(false);
            StringBuilder assistant = new StringBuilder();
            chatRunService.createRunning(runId, user, session.id(), selectedRoute, bindingRef.get(), normalized.metadata());
            try {
                // Java 服务统一保存前端可见用户消息，AgentRuntime 内部记忆只负责自身运行状态。
                sessionService.saveUserMessage(normalized, session);

                // 根据路由结果选择 SubAgent、系统响应或统一 AgentRuntime。
                Flux<ChatEvent> body = switch (selectedRoute.type()) {
                    case SUB_AGENT -> subAgentExecutor.execute(normalized, runId, memory, selectedRoute, user);
                    case SYSTEM_RESPONSE -> systemResponseExecutor.execute(normalized, runId, selectedIntent, selectedRoute);
                    case AGENT_RUNTIME -> agentRuntimeExecutor.execute(normalized, runId, memory, selectedIntent, selectedRoute, user, bindingRef.get());
                };

                // 外层统一补齐 run.started/run.completed，接口层只需要转发事件流。
                // onErrorResume 必须放在持久化之前，确保运行异常转换出的 run.failed 事件也会落库。
                return persistAndPublishRunEvents(
                        Flux.concat(Flux.just(RunStartedEvent.of(runId, session.id())), body,
                                        Flux.just(RunCompletedEvent.of(runId, session.id(), runCompletedPayload(selectedRoute, bindingRef.get()))))
                                .onErrorResume(ex -> Flux.just(ErrorEvent.of(runId, session.id(), "RUN_ERROR", ex.getMessage()))),
                        user,
                        session,
                        normalized,
                        bindingRef,
                        completed,
                        assistant,
                        runId
                );
            } catch (RuntimeException ex) {
                // run 已创建后同步步骤失败时，也必须写入 run.failed 并释放 active run，避免前端看到永远 RUNNING。
                return persistAndPublishRunEvents(
                        Flux.just(ErrorEvent.of(runId, session.id(), "RUN_ERROR", ex.getMessage())),
                        user,
                        session,
                        normalized,
                        bindingRef,
                        completed,
                        assistant,
                        runId
                );
            }
        });
    }

    private Flux<ChatEvent> persistAndPublishRunEvents(Flux<ChatEvent> events, UserContext user, ChatSession session,
                                                       ChatCommand normalized, AtomicReference<RuntimeBinding> bindingRef,
                                                       AtomicBoolean completed, StringBuilder assistant, String runId) {
        return events
                .takeWhile(chatRunService::shouldAcceptEvent)
                .map(chatStreamService::appendAndPublish)
                .doOnNext(event -> {
                    // 事件已经带有 openGauss 持久化 seq，实时输出与断线补发看到的是同一份顺序。
                    chatRunService.observeEvent(event);
                    if ("run.completed".equals(event.type())) {
                        completed.set(true);
                    }
                    appendAssistantDelta(assistant, event);
                    bindingRef.set(runtimeBindingService.observeEvent(bindingRef.get(), event));
                })
                .doOnComplete(() -> {
                    // 只有 run.completed 才代表完整回答可进入历史；stop/failed 的半截 delta 只保留在事件事实源中。
                    if (completed.get()) {
                        if (!assistant.isEmpty()) {
                            sessionService.saveAssistantMessage(user.tenantId(), user.userId(), session.id(), assistant.toString());
                        }
                    }
                });
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
