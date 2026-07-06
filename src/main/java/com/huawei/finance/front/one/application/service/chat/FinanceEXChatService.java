package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.application.facade.DocumentFacade;
import com.huawei.finance.front.one.application.facade.FinanceChatFacade;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.finance.front.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.service.memory.MemoryApplicationService;
import com.huawei.finance.front.one.application.service.routing.IntentRecognitionRecordService;
import com.huawei.finance.front.one.application.service.routing.IntentRecognitionRecordSnapshot;
import com.huawei.finance.front.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.finance.front.one.application.service.routing.RouteSignalResult;
import com.huawei.finance.front.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.finance.front.one.application.service.runtime.DomainAgentExecutor;
import com.huawei.finance.front.one.application.service.runtime.DomainAgentExecutionContext;
import com.huawei.finance.front.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.finance.front.one.application.service.runtime.RuntimeBindingResolution;
import com.huawei.finance.front.one.application.service.runtime.RuntimeExecutionContext;
import com.huawei.finance.front.one.application.service.runtime.RuntimeHitlResponseContext;
import com.huawei.finance.front.one.application.service.runtime.SubAgentExecutor;
import com.huawei.finance.front.one.application.service.runtime.SubAgentExecutionContext;
import com.huawei.finance.front.one.application.service.runtime.SystemResponseExecutor;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatHitlRequest;
import com.huawei.finance.front.one.domain.chat.ChatHitlResponseStartResult;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.finance.front.one.domain.chat.ChatRunMessagePlan;
import com.huawei.finance.front.one.domain.chat.ChatRunMode;
import com.huawei.finance.front.one.domain.chat.ChatRunStartResult;
import com.huawei.finance.front.one.domain.chat.ChatRunStopResult;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatStreamTopics;
import com.huawei.finance.front.one.domain.chat.ErrorEvent;
import com.huawei.finance.front.one.domain.chat.RunCompletedEvent;
import com.huawei.finance.front.one.domain.chat.RunExecutionClaim;
import com.huawei.finance.front.one.domain.chat.RuntimeEvent;
import com.huawei.finance.front.one.domain.chat.RunStartedEvent;
import com.huawei.finance.front.one.domain.chat.RunWaitingUserEvent;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.routing.RouteType;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 聊天主编排服务：负责把一次前端请求串联成可追踪的 SuperAgent 运行。
 *
 * <p>这是 v3 架构的核心入口。这里不承载具体 SubAgent、AgentRuntime、Redis、数据库
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
    private final IntentRecognitionRecordService intentRecognitionRecordService;
    private final SubAgentExecutor subAgentExecutor;
    private final DomainAgentExecutor domainAgentExecutor;
    private final SystemResponseExecutor systemResponseExecutor;
    private final AgentRuntimeExecutor agentRuntimeExecutor;
    private final DocumentFacade documentFacade;
    private final ChatStreamApplicationService chatStreamService;
    private final ChatRunApplicationService chatRunService;
    private final ChatRunLeaseApplicationService chatRunLeaseService;
    private final ChatDeltaCoalescer chatDeltaCoalescer;
    private final LocalChatRunExecutionRegistry runExecutionRegistry;
    private final RunAdmissionControlService runAdmissionControl;
    private final ChatRunStopCoordinator stopCoordinator;
    private final ChatHitlApplicationService chatHitlService;
    private final ChatRunTerminalCommitService terminalCommitService;
    private final IdGenerator idGenerator;
    private final Scheduler eventIoScheduler;

    @Autowired
    public FinanceEXChatService(SessionApplicationService sessionService,
                                MemoryApplicationService memoryService, RuntimeBindingApplicationService runtimeBindingService,
                                RouteSignalApplicationService routeSignalService,
                                IntentRecognitionRecordService intentRecognitionRecordService,
                                SubAgentExecutor subAgentExecutor, DomainAgentExecutor domainAgentExecutor,
                                SystemResponseExecutor systemResponseExecutor,
                                AgentRuntimeExecutor agentRuntimeExecutor, DocumentFacade documentFacade, ChatStreamApplicationService chatStreamService,
                                ChatRunApplicationService chatRunService, ChatRunLeaseApplicationService chatRunLeaseService,
                                ChatDeltaCoalescer chatDeltaCoalescer, LocalChatRunExecutionRegistry runExecutionRegistry,
                                RunAdmissionControlService runAdmissionControl, ChatRunStopCoordinator stopCoordinator,
                                ChatHitlApplicationService chatHitlService,
                                ChatRunTerminalCommitService terminalCommitService,
                                IdGenerator idGenerator,
                                @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler) {
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.runtimeBindingService = runtimeBindingService;
        this.routeSignalService = routeSignalService;
        this.intentRecognitionRecordService = intentRecognitionRecordService;
        this.subAgentExecutor = subAgentExecutor;
        this.domainAgentExecutor = domainAgentExecutor;
        this.systemResponseExecutor = systemResponseExecutor;
        this.agentRuntimeExecutor = agentRuntimeExecutor;
        this.documentFacade = documentFacade;
        this.chatStreamService = chatStreamService;
        this.chatRunService = chatRunService;
        this.chatRunLeaseService = chatRunLeaseService;
        this.chatDeltaCoalescer = chatDeltaCoalescer;
        this.runExecutionRegistry = runExecutionRegistry;
        this.runAdmissionControl = runAdmissionControl;
        this.stopCoordinator = stopCoordinator;
        this.chatHitlService = chatHitlService;
        this.terminalCommitService = terminalCommitService;
        this.idGenerator = idGenerator;
        this.eventIoScheduler = eventIoScheduler == null ? Schedulers.boundedElastic() : eventIoScheduler;
    }

    FinanceEXChatService(SessionApplicationService sessionService,
                         MemoryApplicationService memoryService, RuntimeBindingApplicationService runtimeBindingService,
                         RouteSignalApplicationService routeSignalService,
                         IntentRecognitionRecordService intentRecognitionRecordService,
                         SubAgentExecutor subAgentExecutor, DomainAgentExecutor domainAgentExecutor,
                         SystemResponseExecutor systemResponseExecutor,
                         AgentRuntimeExecutor agentRuntimeExecutor, DocumentFacade documentFacade,
                         ChatStreamApplicationService chatStreamService,
                         ChatRunApplicationService chatRunService, ChatRunLeaseApplicationService chatRunLeaseService,
                         ChatDeltaCoalescer chatDeltaCoalescer, LocalChatRunExecutionRegistry runExecutionRegistry,
                         RunAdmissionControlService runAdmissionControl, IdGenerator idGenerator) {
        this(sessionService, memoryService, runtimeBindingService, routeSignalService, intentRecognitionRecordService,
                subAgentExecutor, domainAgentExecutor, systemResponseExecutor, agentRuntimeExecutor, documentFacade,
                chatStreamService, chatRunService, chatRunLeaseService, chatDeltaCoalescer, runExecutionRegistry,
                runAdmissionControl, new ChatRunStopCoordinator(sessionService, chatStreamService, chatRunService,
                        chatRunLeaseService, runExecutionRegistry, agentRuntimeExecutor, subAgentExecutor,
                        domainAgentExecutor, idGenerator), null, null, idGenerator, Schedulers.boundedElastic());
    }

    @Override
    public Mono<ChatRunStartResult> startRun(UserContext user, ChatCommand command, RuntimeForwardHeaders forwardHeaders) {
        return Mono.defer(() -> {
            RuntimeForwardHeaders headerSnapshot = normalizeForwardHeaders(forwardHeaders);
            RunAdmissionControlService.Permit runPermit = runAdmissionControl.acquire(user);
            Sinks.One<ChatEvent> firstEvent = Sinks.one();
            AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            AtomicReference<String> runIdRef = new AtomicReference<>();
            AtomicBoolean terminal = new AtomicBoolean(false);
            Flux<ChatEvent> runFlux = executeRun(user, command, headerSnapshot)
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
                    .doOnComplete(() -> {
                        if (runIdRef.get() == null) {
                            firstEvent.tryEmitError(new IllegalStateException("chat run finished before emitting any persisted event"));
                        }
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
    public Mono<ChatRunStopResult> stopRun(UserContext user, String runId, RuntimeForwardHeaders forwardHeaders) {
        return stopCoordinator.stopRun(user, runId, "USER_STOP", forwardHeaders);
    }

    @Override
    public Mono<ChatHitlResponseStartResult> submitHitlResponse(ChatHitlResponseCommand command,
                                                                RuntimeForwardHeaders forwardHeaders) {
        return Mono.defer(() -> {
            RuntimeForwardHeaders headerSnapshot = normalizeForwardHeaders(forwardHeaders);
            UserContext user = command.user();
            RunAdmissionControlService.Permit runPermit = runAdmissionControl.acquire(user);
            Sinks.One<ChatEvent> firstEvent = Sinks.one();
            AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            AtomicReference<String> runIdRef = new AtomicReference<>();
            AtomicBoolean terminal = new AtomicBoolean(false);
            String runId = idGenerator.newId("run",
                    IdGenerateContext.of(user.tenantId(), user.ownerUserId(), command.hitlRequestId()));
            ChatHitlClaimResult claim;
            try {
                claim = chatHitlService.claimResponse(command, runId);
            } catch (RuntimeException ex) {
                runPermit.close();
                return Mono.error(ex);
            }
            Flux<ChatEvent> runFlux;
            try {
                runFlux = executeHitlContinuation(user, claim, runId, headerSnapshot)
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
                        .doOnComplete(() -> {
                            if (runIdRef.get() == null) {
                                firstEvent.tryEmitError(new IllegalStateException("hitl continuation finished before emitting any event"));
                            }
                        })
                        .doFinally(signalType -> {
                            terminal.set(true);
                            runExecutionRegistry.complete(runIdRef.get());
                            runPermit.close();
                        });
            } catch (RuntimeException ex) {
                chatHitlService.markWaiting(claim.request());
                runPermit.close();
                return Mono.error(ex);
            }
            Disposable disposable = runFlux.subscribe(event -> {
            }, error -> {
                Sinks.EmitResult result = firstEvent.tryEmitError(error);
                if (result.isFailure()) {
                    log.warn("Background HITL continuation terminated after handoff. hitlRequestId={}, runId={}, reason={}",
                            command.hitlRequestId(), runId, error.getMessage(), error);
                }
            });
            disposableRef.set(disposable);
            if (runIdRef.get() != null && !terminal.get()) {
                runExecutionRegistry.register(runIdRef.get(), disposable);
            }
            return firstEvent.asMono()
                    .map(event -> new ChatHitlResponseStartResult(
                            claim.request().id(),
                            event.runId(),
                            event.sessionId(),
                            claim.request().assistantMessageId(),
                            ChatStreamTopics.runTopic(event.runId()),
                            event.sequence(),
                            "RESPONDING"));
        });
    }

    private Flux<ChatEvent> executeHitlContinuation(UserContext user, ChatHitlClaimResult claim, String runId,
                                                    RuntimeForwardHeaders forwardHeaders) {
        ChatHitlRequest hitl = claim.request();
        ChatSession session = sessionService.getSession(user, hitl.sessionId());
        RuntimeBinding binding = runtimeBindingService.resumeForHitl(hitl, runId);
        RouteTarget route = RouteTarget.agentRuntime("hitl-continuation", 1.0,
                "continue waiting user input");
        ChatMessage userMessage = new ChatMessage(hitl.userMessageId(), user.tenantId(), user.ownerUserId(),
                session.id(), "user", "", null, Instant.now());
        ChatRunMessagePlan messagePlan = new ChatRunMessagePlan(ChatRunMode.NEXT,
                hitl.userMessageId(), userMessage, null);
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>(binding);
        AssistantAssembly assistant = new AssistantAssembly();
        ChatRun run = chatRunService.createRunning(new CreateChatRunContext(
                runId,
                user,
                session.id(),
                route,
                binding,
                Map.of("hitlRequestId", hitl.id(), "waitingType", hitl.waitingType().name()),
                ChatRunMode.NEXT,
                hitl.userMessageId(),
                hitl.userMessageId()
        ));
        RunExecutionClaim executionClaim;
        try {
            executionClaim = chatRunLeaseService.startRun(run);
        } catch (RuntimeException ex) {
            ChatEvent failed = chatStreamService.appendAndPublish(
                    ErrorEvent.of(runId, session.id(), "RUN_EXECUTION_INIT_FAILED", ex.getMessage()));
            chatRunService.observeEvent(failed);
            chatHitlService.markWaiting(hitl);
            return Flux.just(failed);
        }
        runExecutionRegistry.registerClaim(executionClaim);
        Flux<ChatEvent> responsePart = Flux.just(clarificationResponseEvent(runId, session.id(), hitl, claim.responsePayload()));
        Flux<ChatEvent> body = agentRuntimeExecutor.continueWithUserResponse(new RuntimeHitlResponseContext(
                user,
                session.id(),
                runId,
                binding.provider(),
                binding.runtimeSessionId(),
                hitl.id(),
                hitl.waitingType().name(),
                hitl.approvalId(),
                claim.responsePayload(),
                forwardHeaders
        ));
        return persistAndPublishRunEvents(
                Flux.concat(Flux.just(RunStartedEvent.of(runId, session.id())), responsePart, body,
                                Flux.just(RunCompletedEvent.of(runId, session.id(), runCompletedPayload(route, binding))))
                        .onErrorResume(ex -> Flux.just(runtimeErrorEvent(runId, session.id(), ex))),
                new RunEventPipelineContext(user, session, messagePlan, bindingRef, assistant, runId,
                        executionClaim, new AtomicReference<>(), hitl)
        );
    }

    @Override
    public Flux<ChatEvent> executeRun(UserContext user, ChatCommand command, RuntimeForwardHeaders forwardHeaders) {
        // defer 确保每个订阅都会生成独立 runId，避免热流复用导致事件串线。
        return Flux.defer(() -> {
            RuntimeForwardHeaders headerSnapshot = normalizeForwardHeaders(forwardHeaders);
            // 进入 application 后统一以 UserContext 为准；原始前端请求只保留会话、消息、附件和元数据。
            ChatCommand identified = new ChatCommand(command.commandId(), user.tenantId(), user.ownerUserId(),
                    command.sessionId(), command.conversationId(), command.channel(), command.message(),
                    command.attachments(), command.metadata(), command.runMode(), command.parentMessageId(),
                    command.editedMessageId(), command.regeneratedMessageId());

            // 会话不存在时创建会话；历史 Memory 先排除本轮输入，避免 Runtime 再接收用户消息时重复。
            ChatSession session = sessionService.loadOrCreate(identified);
            // 同一会话同一时刻只允许一个 active run。这里在写入用户消息前快速拒绝，
            // 避免多页签重复提交时先污染消息树；createRunning 仍会再做一次 Redis 原子声明。
            if (chatHitlService != null) {
                chatHitlService.rejectIfWaiting(user, session.id());
            }
            chatRunService.rejectIfActiveRunExists(user, session.id());

            List<AttachmentRef> attachments = documentFacade.resolveAttachmentsForUser(user,
                    identified.attachments() == null ? List.of() : identified.attachments());
            ChatCommand normalized = new ChatCommand(identified.commandId(), user.tenantId(), user.ownerUserId(),
                    session.id(), identified.conversationId(), identified.channel(), identified.message(),
                    attachments, identified.metadata(), identified.runMode(), identified.parentMessageId(),
                    identified.editedMessageId(), identified.regeneratedMessageId());
            String runId = idGenerator.newId("run", IdGenerateContext.of(user.tenantId(), user.ownerUserId(), session.id()));

            // MemoryContext 是可选 SuperAgent 记忆增强。长短期记忆都关闭时这里返回空上下文，
            // 且不会查询 Redis、历史消息或长期记忆服务；当前用户输入也不会进入本轮上下文，避免重复。
            MemoryContext memory = memoryService.loadForRun(normalized);
            ChatRunMessagePlan messagePlan = sessionService.prepareRunMessage(user, normalized, session, runId, attachments);
            ChatCommand runCommand = commandForExecution(normalized, messagePlan);
            String runtimeBindingLeafId = runtimeBindingLeafId(messagePlan);
            IntentDecision intent = null;
            Long intentLatencyMs = null;
            Double intentConfidenceThreshold = null;
            RouteTarget route = null;
            RuntimeBinding binding = null;
            RuntimeSessionMode runtimeSessionMode = RuntimeSessionMode.RESUME;
            String selectedDomainAgentId = selectedDomainAgentId(normalized.metadata());
            if (selectedDomainAgentId != null) {
                // 用户显式选择 DomainAgent 时优先进入指定调用路由，不被 active RuntimeBinding 抢走。
                // 该路径不创建 RuntimeBinding，避免把非 ChatService Runtime 契约的领域 Agent 误当成多轮 Runtime。
                route = RouteTarget.domainAgent(selectedDomainAgentId, "front selected domain agent");
            } else {
                var activeRuntimeBinding = runtimeBindingService.findActiveBySession(user.tenantId(),
                        user.ownerUserId(), session.id());
                if (activeRuntimeBinding.isPresent()) {
                    // Runtime 是唯一允许多轮续接的执行主体；命中 active binding 后直接继续 Relay Runtime。
                    binding = runtimeBindingService.touchForRun(activeRuntimeBinding.get(), runId);
                    runtimeSessionMode = RuntimeSessionMode.RESUME;
                    route = RouteTarget.agentRuntime("runtime-binding", 1.0, "active relay runtime binding");
                }
            }
            if (route == null) {
                // 首轮路由只读取已启用的外部路由信号。默认用例库和意图服务都关闭，
                // 此时不会发生外部 HTTP 调用，请求直接进入 AgentRuntime。
                RouteSignalResult routeSignal = routeSignalService.routeInitial(user, session, runCommand, attachments, memory);
                route = routeSignal.route();
                intent = routeSignal.intentDecision();
                intentLatencyMs = routeSignal.intentLatencyMs();
                intentConfidenceThreshold = routeSignal.intentConfidenceThreshold();
                if (route.type() == RouteType.SUB_AGENT) {
                    binding = null;
                } else if (route.type() == RouteType.AGENT_RUNTIME) {
                    RuntimeBindingResolution resolution = runtimeBindingService.resolveForRun(user.tenantId(),
                            user.ownerUserId(), session.id(), runId, runtimeBindingLeafId);
                    binding = resolution.binding();
                    runtimeSessionMode = resolution.sessionMode();
                }
            }
            IntentDecision selectedIntent = intent;
            Long selectedIntentLatencyMs = intentLatencyMs;
            Double selectedIntentConfidenceThreshold = intentConfidenceThreshold;
            RouteTarget selectedRoute = route;
            RuntimeSessionMode selectedRuntimeSessionMode = runtimeSessionMode;
            AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>(binding);
            AtomicReference<Map<String, Object>> pendingHitlPayloadRef = new AtomicReference<>();
            AssistantAssembly assistant = new AssistantAssembly();
            ChatRun run = chatRunService.createRunning(new CreateChatRunContext(
                    runId,
                    user,
                    session.id(),
                    selectedRoute,
                    bindingRef.get(),
                    normalized.metadata(),
                    messagePlan.runMode(),
                    messagePlan.parentMessageId(),
                    messagePlan.userMessage().id()
            ));
            if (selectedIntent != null) {
                intentRecognitionRecordService.recordAsync(IntentRecognitionRecordSnapshot.of(
                        new IntentRecognitionRecordSnapshot.IntentRecognitionRecordInput(
                                user,
                                runCommand,
                                run.id(),
                                selectedIntent,
                                selectedRoute,
                                selectedIntentConfidenceThreshold == null ? 0.0 : selectedIntentConfidenceThreshold,
                                selectedIntentLatencyMs)));
            }
            RunExecutionClaim executionClaim;
            try {
                executionClaim = chatRunLeaseService.startRun(run);
            } catch (RuntimeException ex) {
                /*
                 * run 已经作为业务事实创建后，execution 控制面初始化失败也必须把本轮 run 闭合。
                 * 此时还没有 claim，不能进入统一 fencing 写入链路；直接写 run.failed 并交给
                 * ChatRunApplicationService 释放 active run，避免会话永久卡在 RUNNING。
                 */
                ChatEvent failed = chatStreamService.appendAndPublish(
                        ErrorEvent.of(runId, session.id(), "RUN_EXECUTION_INIT_FAILED", ex.getMessage()));
                chatRunService.observeEvent(failed);
                return Flux.just(failed);
            }
            runExecutionRegistry.registerClaim(executionClaim);
            try {
                // 根据路由结果选择 SubAgent、系统响应或统一 AgentRuntime。
                Flux<ChatEvent> body = switch (selectedRoute.type()) {
                    case SUB_AGENT -> subAgentExecutor.execute(new SubAgentExecutionContext(
                            runCommand, runId, memory, selectedRoute, user));
                    case DOMAIN_AGENT -> domainAgentExecutor.execute(new DomainAgentExecutionContext(
                            runCommand, runId, selectedRoute, user, headerSnapshot));
                    case SYSTEM_RESPONSE -> systemResponseExecutor.execute(runCommand, runId, selectedIntent, selectedRoute);
                    case AGENT_RUNTIME -> agentRuntimeExecutor.execute(new RuntimeExecutionContext(
                            runCommand, runId, memory, selectedIntent, selectedRoute, user, bindingRef.get(),
                            selectedRuntimeSessionMode, headerSnapshot));
                };

                // 外层统一补齐 run.started/run.completed，接口层只需要转发事件流。
                // onErrorResume 必须放在持久化之前，确保运行异常转换出的 run.failed 事件也会落库。
                return persistAndPublishRunEvents(
                        Flux.concat(Flux.just(RunStartedEvent.of(runId, session.id())), body,
                                        Flux.just(RunCompletedEvent.of(runId, session.id(), runCompletedPayload(selectedRoute, bindingRef.get()))))
                                .onErrorResume(ex -> Flux.just(runtimeErrorEvent(runId, session.id(), ex))),
                        new RunEventPipelineContext(user, session, messagePlan, bindingRef, assistant, runId,
                                executionClaim, pendingHitlPayloadRef, null)
                );
            } catch (RuntimeException ex) {
                // run 已创建后同步步骤失败时，也必须写入 run.failed 并释放 active run，避免前端看到永远 RUNNING。
                return persistAndPublishRunEvents(
                        Flux.just(runtimeErrorEvent(runId, session.id(), ex)),
                        new RunEventPipelineContext(user, session, messagePlan, bindingRef, assistant, runId,
                                executionClaim, pendingHitlPayloadRef, null)
                );
            }
        });
    }

    private Flux<ChatEvent> persistAndPublishRunEvents(Flux<ChatEvent> events, RunEventPipelineContext context) {
        AtomicBoolean writeRejected = new AtomicBoolean(false);
        String runId = context.runId();
        ChatSession session = context.session();
        AssistantAssembly assistant = context.assistant();
        AtomicReference<RuntimeBinding> bindingRef = context.bindingRef();
        ChatRunMessagePlan messagePlan = context.messagePlan();
        UserContext user = context.user();
        return chatDeltaCoalescer.coalesce(events)
                .publishOn(eventIoScheduler)
                .<ChatEvent>handle((event, sink) -> {
                    if (writeRejected.get()) {
                        sink.complete();
                        return;
                    }
                    if (!eventBelongsToCurrentRun(event, runId, session.id())) {
                        /*
                         * 下游 Runtime/SubAgent 的输出不是身份事实。任何 runId/sessionId 不匹配的事件
                         * 都必须在落库前阻断，否则会污染数据库事件事实源并经由 Event Resume/WS 串到其他会话。
                         */
                        log.error("Dropped mismatched chat event before persistence. expectedRunId={}, actualRunId={}, expectedSessionId={}, actualSessionId={}, type={}",
                                runId,
                                event == null ? null : event.runId(),
                                session.id(),
                                event == null ? null : event.sessionId(),
                                event == null ? null : event.type());
                        sink.next(ErrorEvent.of(runId, session.id(), "RUN_EVENT_IDENTITY_MISMATCH",
                                "下游返回的事件身份与当前 run/session 不一致，已终止本轮回答"));
                        sink.complete();
                        return;
                    }
                    if (!chatRunService.shouldAcceptEvent(event)) {
                        sink.complete();
                        return;
                    }
                    sink.next(event);
                })
                .concatMap(event -> {
                    try {
                        return Mono.just(persistAndPublishOneEvent(event, context));
                    } catch (ChatEventAppendRejectedException ex) {
                        writeRejected.set(true);
                        log.info("Stop chat run event stream after guarded insert rejection. runId={}, reason={}",
                                runId, ex.getMessage());
                        return Mono.empty();
                    } catch (RuntimeException ex) {
                        if ("run.failed".equals(event.type()) || terminalCommitService == null) {
                            return Mono.error(ex);
                        }
                        return Mono.just(commitTerminalFailure(context, ex));
                    }
                });
    }

    private ChatEvent persistAndPublishOneEvent(ChatEvent event, RunEventPipelineContext context) {
        String runId = context.runId();
        ChatSession session = context.session();
        AssistantAssembly assistant = context.assistant();
        AtomicReference<RuntimeBinding> bindingRef = context.bindingRef();
        ChatRunMessagePlan messagePlan = context.messagePlan();
        UserContext user = context.user();
        CompletionMessageTarget completionTarget = completionMessageTarget(event, context);
        ChatHitlRequest waitingRequest = waitingRequest(event, completionTarget, context);
        ChatEvent eventToPersist = waitingRequest == null
                ? withCompletionFeedbackPayload(event, completionTarget)
                : withWaitingUserPayload(event, completionTarget, waitingRequest);
        if (terminalCommitService != null && waitingRequest != null) {
            return commitWaitingUser(eventToPersist, context, completionTarget, waitingRequest);
        }
        if (terminalCommitService != null && "run.completed".equals(eventToPersist.type())
                && completionTarget.messageReady()) {
            return commitCompleted(eventToPersist, context, completionTarget);
        }
        if (terminalCommitService != null && context.continuationHitlRequest() != null
                && ("run.failed".equals(eventToPersist.type()) || "run.cancelled".equals(eventToPersist.type()))) {
            return commitTerminalOnly(eventToPersist, context);
        }
        /*
         * 只有 DB guarded insert 成功后，事件才算事实成立。assistant 文本累积、
         * 历史消息写入、run 状态推进和 Redis/WebSocket 发布都以该持久化结果为准，
         * 避免 stop/watchdog 后的迟到 delta 进入用户可见历史。
         */
        ChatEvent stored = chatStreamService.appendWithExecutionGuard(eventToPersist, context.executionClaim());
        assistant.observe(stored);
        rememberPendingHitlRequest(stored, context);
        /*
         * run.completed 是前端、Event Resume 和跨设备续接共同认可的“本轮回答已经闭合”信号。
         * 因此在发布该终态事件之前，必须先把完整 assistant 消息写入历史消息树，
         * 避免客户端收到 completed 后立即查询历史时只能看到 user 节点。
         */
        if ("run.completed".equals(stored.type()) && completionTarget.messageReady()) {
            ChatMessage savedAssistant = context.continuationHitlRequest() == null
                    ? sessionService.saveAssistantMessage(new AssistantMessageSaveCommand(
                            user.tenantId(),
                            user.ownerUserId(),
                            session,
                            assistant.finalContent(),
                            runId,
                            messagePlan.userMessage().id(),
                            messagePlan.regeneratedFromMessageId(),
                            assistant.parts(),
                            null,
                            completionTarget.assistantMessageId()
                    ))
                    : sessionService.updateAssistantMessage(new AssistantMessageUpdateCommand(
                            user.tenantId(),
                            user.ownerUserId(),
                            session,
                            context.continuationHitlRequest().assistantMessageId(),
                            assistant.finalContent(),
                            runId,
                            assistant.parts(),
                            null
            ));
            chatRunService.bindAssistantMessage(runId, savedAssistant.id());
            bindingRef.set(runtimeBindingService.touchAndMoveToLeaf(bindingRef.get(), runId, savedAssistant.id()));
            if (context.continuationHitlRequest() != null) {
                chatHitlService.markAnswered(context.continuationHitlRequest());
            }
        }
        if ("run.waiting_user".equals(stored.type()) && completionTarget.messageReady() && waitingRequest != null) {
            ChatMessage savedAssistant = sessionService.saveAssistantMessage(new AssistantMessageSaveCommand(
                    user.tenantId(),
                    user.ownerUserId(),
                    session,
                    assistant.finalContent(),
                    runId,
                    messagePlan.userMessage().id(),
                    messagePlan.regeneratedFromMessageId(),
                    assistant.parts(),
                    "{\"finishReason\":\"WAITING_USER\"}",
                    completionTarget.assistantMessageId()
            ));
            chatRunService.bindAssistantMessage(runId, savedAssistant.id());
            bindingRef.set(runtimeBindingService.touchAndMoveToLeaf(bindingRef.get(), runId, savedAssistant.id()));
            chatHitlService.saveWaiting(waitingRequest);
            if (context.continuationHitlRequest() != null) {
                chatHitlService.markAnswered(context.continuationHitlRequest());
            }
        }
        // 事件已经带有数据库持久化 seq，实时输出与断线补发看到的是同一份顺序。
        chatRunService.observeEvent(stored);
        if (context.continuationHitlRequest() != null && ("run.failed".equals(stored.type())
                || "run.cancelled".equals(stored.type()))) {
            chatHitlService.markWaiting(context.continuationHitlRequest());
        }
        markExecutionTerminalIfNeeded(stored);
        bindingRef.set(runtimeBindingService.observeEvent(bindingRef.get(), stored));
        chatStreamService.publishPersisted(stored);
        return stored;
    }

    private ChatEvent commitWaitingUser(ChatEvent eventToPersist, RunEventPipelineContext context,
                                        CompletionMessageTarget completionTarget, ChatHitlRequest waitingRequest) {
        try {
            ChatRunTerminalCommitService.CommitResult result = terminalCommitService.commitWaitingUser(
                    new ChatRunTerminalCommitService.WaitingUserCommitCommand(
                            eventToPersist,
                            terminalCommitContext(context),
                            terminalMessageTarget(completionTarget),
                            waitingRequest
                    ));
            return publishCommitted(result, context);
        } catch (RuntimeException ex) {
            return commitTerminalFailure(context, ex);
        }
    }

    private ChatEvent commitCompleted(ChatEvent eventToPersist, RunEventPipelineContext context,
                                      CompletionMessageTarget completionTarget) {
        try {
            ChatRunTerminalCommitService.CommitResult result = terminalCommitService.commitCompleted(
                    new ChatRunTerminalCommitService.CompletedCommitCommand(
                            eventToPersist,
                            terminalCommitContext(context),
                            terminalMessageTarget(completionTarget)
                    ));
            return publishCommitted(result, context);
        } catch (RuntimeException ex) {
            return commitTerminalFailure(context, ex);
        }
    }

    private ChatEvent commitTerminalOnly(ChatEvent eventToPersist, RunEventPipelineContext context) {
        ChatRunTerminalCommitService.CommitResult result = terminalCommitService.commitTerminalOnly(
                new ChatRunTerminalCommitService.TerminalOnlyCommitCommand(
                        eventToPersist,
                        terminalCommitContext(context)
                ));
        return publishCommitted(result, context);
    }

    private ChatEvent commitTerminalFailure(RunEventPipelineContext context, RuntimeException ex) {
        log.warn("Chat run terminal commit failed, fallback to run.failed. runId={}, reason={}",
                context.runId(), ex.getMessage(), ex);
        ChatEvent failed = runtimeErrorEvent(context.runId(), context.session().id(), ex);
        return commitTerminalOnly(failed, context);
    }

    private ChatEvent publishCommitted(ChatRunTerminalCommitService.CommitResult result,
                                       RunEventPipelineContext context) {
        context.bindingRef().set(result.binding());
        chatStreamService.publishPersisted(result.event());
        return result.event();
    }

    private ChatRunTerminalCommitService.TerminalCommitContext terminalCommitContext(RunEventPipelineContext context) {
        return new ChatRunTerminalCommitService.TerminalCommitContext(
                context.user(),
                context.session(),
                context.messagePlan(),
                context.bindingRef(),
                context.assistant(),
                context.runId(),
                context.executionClaim(),
                context.continuationHitlRequest()
        );
    }

    private ChatRunTerminalCommitService.MessageTarget terminalMessageTarget(CompletionMessageTarget target) {
        return new ChatRunTerminalCommitService.MessageTarget(target.messageReady(), target.assistantMessageId());
    }

    private boolean eventBelongsToCurrentRun(ChatEvent event, String runId, String sessionId) {
        return event != null && runId.equals(event.runId()) && sessionId.equals(event.sessionId());
    }

    private CompletionMessageTarget completionMessageTarget(ChatEvent event, RunEventPipelineContext context) {
        if (event == null || !"run.completed".equals(event.type())) {
            return CompletionMessageTarget.notRunCompleted();
        }
        if (context.continuationHitlRequest() != null) {
            // HITL 续接复用等待态 assistant，即使 Relay 只返回终态也要把同一条消息标记为可反馈。
            return CompletionMessageTarget.ready(context.continuationHitlRequest().assistantMessageId());
        }
        if (!context.assistant().shouldPersistMessage()) {
            return CompletionMessageTarget.notReady();
        }
        String assistantMessageId = idGenerator.newId("msg",
                IdGenerateContext.of(context.user().tenantId(), context.user().ownerUserId(),
                        context.session().id(), context.runId()));
        return CompletionMessageTarget.ready(assistantMessageId);
    }

    private ChatEvent withCompletionFeedbackPayload(ChatEvent event, CompletionMessageTarget completionTarget) {
        if (event == null || !completionTarget.runCompleted()) {
            return event;
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>(
                event.payload() == null ? Map.of() : event.payload());
        payload.put("messageReady", completionTarget.messageReady());
        if (completionTarget.messageReady()) {
            payload.put("assistantMessageId", completionTarget.assistantMessageId());
            payload.put("feedbackTargetMessageId", completionTarget.assistantMessageId());
        }
        return new RunCompletedEvent(event.runId(), event.sessionId(), event.sequence(),
                event.createdAt(), java.util.Collections.unmodifiableMap(payload));
    }

    private ChatHitlRequest waitingRequest(ChatEvent event, CompletionMessageTarget target,
                                           RunEventPipelineContext context) {
        if (chatHitlService == null || event == null || !"run.completed".equals(event.type())) {
            return null;
        }
        Map<String, Object> requestPayload = context.pendingHitlPayloadRef().get();
        if (requestPayload == null) {
            return null;
        }
        if (!target.messageReady()) {
            return null;
        }
        RuntimeBinding binding = context.bindingRef().get();
        if (binding == null || binding.id() == null || binding.id().isBlank()) {
            throw new IllegalStateException("HITL 等待态缺少 RuntimeBinding，无法续接 Runtime");
        }
        String runtimeProvider = binding == null ? null : binding.provider();
        String runtimeSessionId = runtimeSessionId(requestPayload, binding);
        return chatHitlService.prepareWaiting(new ChatHitlCreateContext(
                context.user(),
                context.session(),
                context.runId(),
                context.messagePlan().userMessage(),
                target.assistantMessageId(),
                runtimeProvider,
                binding.id(),
                runtimeSessionId,
                requestPayload
        ));
    }

    private ChatEvent withWaitingUserPayload(ChatEvent event, CompletionMessageTarget target,
                                             ChatHitlRequest waitingRequest) {
        Map<String, Object> payload = new LinkedHashMap<>(event.payload() == null ? Map.of() : event.payload());
        payload.put("status", "WAITING_USER");
        payload.put("waitingType", waitingRequest.waitingType().name());
        payload.put("hitlRequestId", waitingRequest.id());
        payload.put("messageReady", target.messageReady());
        payload.put("assistantMessageId", target.assistantMessageId());
        payload.put("feedbackTargetMessageId", target.assistantMessageId());
        if (waitingRequest.expiresAt() != null) {
            payload.put("expiresAt", waitingRequest.expiresAt().toString());
        }
        return new RunWaitingUserEvent(event.runId(), event.sessionId(), event.sequence(),
                event.createdAt(), Map.copyOf(payload));
    }

    private void rememberPendingHitlRequest(ChatEvent stored, RunEventPipelineContext context) {
        if (!questionnaireApprovalRequest(stored)) {
            return;
        }
        RuntimeBinding binding = context.bindingRef().get();
        String runtimeProvider = binding == null ? null : binding.provider();
        if (!agentRuntimeExecutor.supportsWaitingUserResponse(runtimeProvider)) {
            return;
        }
        context.pendingHitlPayloadRef().compareAndSet(null,
                stored.payload() == null ? Map.of() : Map.copyOf(stored.payload()));
    }

    private boolean questionnaireApprovalRequest(ChatEvent event) {
        if (event == null || !"runtime.card".equals(event.type()) || event.payload() == null) {
            return false;
        }
        return "approval-request".equals(String.valueOf(event.payload().get("sourceType")))
                && "questionnaire".equalsIgnoreCase(String.valueOf(event.payload().get("operation_type")));
    }

    private String runtimeSessionId(Map<String, Object> payload, RuntimeBinding binding) {
        Object fromPayload = payload == null ? null : payload.get("runtimeSessionId");
        if (fromPayload != null && !String.valueOf(fromPayload).isBlank()) {
            return String.valueOf(fromPayload);
        }
        return binding == null ? null : binding.runtimeSessionId();
    }

    private RuntimeEvent clarificationResponseEvent(String runId, String sessionId, ChatHitlRequest hitl,
                                                    Map<String, Object> responsePayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "clarification-response");
        payload.put("hitlRequestId", hitl.id());
        payload.put("waitingType", hitl.waitingType().name());
        payload.put("approval_id", hitl.approvalId());
        payload.put("approved", responsePayload.get("approved"));
        payload.put("scope", responsePayload.get("scope"));
        payload.put("questionnaireAnswers", responsePayload.get("questionnaireAnswers"));
        Object answers = responsePayload.get("questionnaireAnswers");
        if (answers instanceof Map<?, ?> answerMap && !answerMap.isEmpty()) {
            payload.put("answerText", answerMap.values().stream()
                    .findFirst()
                    .map(String::valueOf)
                    .orElse(""));
        }
        payload.put("metadata", responsePayload.get("metadata"));
        return RuntimeEvent.card(runId, sessionId, Map.copyOf(payload));
    }

    private record RunEventPipelineContext(
            UserContext user,
            ChatSession session,
            ChatRunMessagePlan messagePlan,
            AtomicReference<RuntimeBinding> bindingRef,
            AssistantAssembly assistant,
            String runId,
            RunExecutionClaim executionClaim,
            AtomicReference<Map<String, Object>> pendingHitlPayloadRef,
            ChatHitlRequest continuationHitlRequest
    ) {
    }

    private record CompletionMessageTarget(
            boolean runCompleted,
            boolean messageReady,
            String assistantMessageId
    ) {
        private static CompletionMessageTarget notRunCompleted() {
            return new CompletionMessageTarget(false, false, null);
        }

        private static CompletionMessageTarget notReady() {
            return new CompletionMessageTarget(true, false, null);
        }

        private static CompletionMessageTarget ready(String assistantMessageId) {
            return new CompletionMessageTarget(true, true, assistantMessageId);
        }
    }

    private void markExecutionTerminalIfNeeded(ChatEvent event) {
        ChatRunExecutionStatus terminalStatus = switch (event.type()) {
            case "run.completed" -> ChatRunExecutionStatus.COMPLETED;
            case "run.waiting_user" -> ChatRunExecutionStatus.WAITING_USER;
            case "run.failed" -> ChatRunExecutionStatus.FAILED;
            case "run.cancelled" -> ChatRunExecutionStatus.CANCELLED;
            default -> null;
        };
        if (terminalStatus != null) {
            chatRunLeaseService.markTerminal(event.runId(), terminalStatus);
        }
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

    private String selectedDomainAgentId(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("selectedDomainAgentId");
        if (value == null) {
            return null;
        }
        String domainAgentId = String.valueOf(value).trim();
        return domainAgentId.isBlank() ? null : domainAgentId;
    }

    private RuntimeForwardHeaders normalizeForwardHeaders(RuntimeForwardHeaders forwardHeaders) {
        return forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
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

    private ErrorEvent runtimeErrorEvent(String runId, String sessionId, Throwable ex) {
        String code = relayWebSocketConfigTimeout(ex)
                ? "RELAY_WS_CONFIG_TIMEOUT"
                : isTimeout(ex) ? "RUNTIME_STREAM_TIMEOUT" : "RUN_ERROR";
        String message = ex == null || ex.getMessage() == null || ex.getMessage().isBlank()
                ? "Runtime execution failed"
                : ex.getMessage();
        return ErrorEvent.of(runId, sessionId, code, message);
    }

    private boolean relayWebSocketConfigTimeout(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("RELAY_WS_CONFIG_TIMEOUT")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isTimeout(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (className.contains("TimeoutException")
                    || (message != null && message.contains("Did not observe any item or terminal signal within"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

}
