package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.application.facade.DocumentFacade;
import com.huawei.finance.front.one.application.facade.FinanceChatFacade;
import com.huawei.finance.front.one.application.config.DomainAgentProperties;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.finance.front.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.service.memory.MemoryApplicationService;
import com.huawei.finance.front.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.finance.front.one.application.service.routing.IntentRecognitionRecordService;
import com.huawei.finance.front.one.application.service.routing.IntentRecognitionRecordSnapshot;
import com.huawei.finance.front.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.finance.front.one.application.service.routing.RouteSignalFrame;
import com.huawei.finance.front.one.application.service.routing.RouteSignalProgress;
import com.huawei.finance.front.one.application.service.routing.RouteSignalResult;
import com.huawei.finance.front.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.finance.front.one.application.service.runtime.DomainAgentBindingCommand;
import com.huawei.finance.front.one.application.service.runtime.DomainAgentExecutionContext;
import com.huawei.finance.front.one.application.service.runtime.DomainAgentExecutor;
import com.huawei.finance.front.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.finance.front.one.application.service.runtime.RuntimeBindingResolution;
import com.huawei.finance.front.one.application.service.runtime.RuntimeExecutionContext;
import com.huawei.finance.front.one.application.service.runtime.RuntimeHitlResponseContext;
import com.huawei.finance.front.one.application.service.runtime.SystemResponseExecutor;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatHitlRequest;
import com.huawei.finance.front.one.domain.chat.ChatHitlResponseStartResult;
import com.huawei.finance.front.one.domain.chat.ChatHitlWaitingType;
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
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.RunCompletedEvent;
import com.huawei.finance.front.one.domain.chat.RunExecutionClaim;
import com.huawei.finance.front.one.domain.chat.RuntimeEvent;
import com.huawei.finance.front.one.domain.chat.RunStartedEvent;
import com.huawei.finance.front.one.domain.chat.RunWaitingUserEvent;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.routing.RouteType;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>这是 v3 架构的核心入口。这里不承载具体 DomainAgent、AgentRuntime、Redis、数据库
 * 或外部路由信号协议细节，只负责把稳定的业务顺序串起来：
 * 身份校验 -> 会话归一化 -> 上下文装配 -> Runtime 续接 -> 可选路由信号 -> Agent 调用。</p>
 */
@Service
public class FinanceEXChatService implements FinanceChatFacade {
    private static final Logger log = LoggerFactory.getLogger(FinanceEXChatService.class);

    private final SessionApplicationService sessionService;
    private final MemoryApplicationService memoryService;
    private final RuntimeBindingApplicationService runtimeBindingService;
    private final RouteSignalApplicationService routeSignalService;
    private final IntentRecognitionRecordService intentRecognitionRecordService;
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
    private final DomainAgentProperties domainAgentProperties;
    private final RouteMemoryApplicationService routeMemoryService;

    @Autowired
    public FinanceEXChatService(SessionApplicationService sessionService,
                                MemoryApplicationService memoryService, RuntimeBindingApplicationService runtimeBindingService,
                                RouteSignalApplicationService routeSignalService,
                                IntentRecognitionRecordService intentRecognitionRecordService,
                                SystemResponseExecutor systemResponseExecutor,
                                AgentRuntimeExecutor agentRuntimeExecutor, DocumentFacade documentFacade, ChatStreamApplicationService chatStreamService,
                                ChatRunApplicationService chatRunService, ChatRunLeaseApplicationService chatRunLeaseService,
                                ChatDeltaCoalescer chatDeltaCoalescer, LocalChatRunExecutionRegistry runExecutionRegistry,
                                RunAdmissionControlService runAdmissionControl, ChatRunStopCoordinator stopCoordinator,
                                ChatHitlApplicationService chatHitlService,
                                ChatRunTerminalCommitService terminalCommitService,
                                IdGenerator idGenerator,
                                @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler,
                                DomainAgentProperties domainAgentProperties,
                                RouteMemoryApplicationService routeMemoryService) {
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.runtimeBindingService = runtimeBindingService;
        this.routeSignalService = routeSignalService;
        this.intentRecognitionRecordService = intentRecognitionRecordService;
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
        this.domainAgentProperties = domainAgentProperties == null ? new DomainAgentProperties() : domainAgentProperties;
        this.routeMemoryService = routeMemoryService;
    }

    FinanceEXChatService(SessionApplicationService sessionService,
                         MemoryApplicationService memoryService, RuntimeBindingApplicationService runtimeBindingService,
                         RouteSignalApplicationService routeSignalService,
                         IntentRecognitionRecordService intentRecognitionRecordService,
                         SystemResponseExecutor systemResponseExecutor,
                         AgentRuntimeExecutor agentRuntimeExecutor, DocumentFacade documentFacade,
                         ChatStreamApplicationService chatStreamService,
                         ChatRunApplicationService chatRunService, ChatRunLeaseApplicationService chatRunLeaseService,
                         ChatDeltaCoalescer chatDeltaCoalescer, LocalChatRunExecutionRegistry runExecutionRegistry,
                         RunAdmissionControlService runAdmissionControl, IdGenerator idGenerator) {
        this(sessionService, memoryService, runtimeBindingService, routeSignalService, intentRecognitionRecordService,
                systemResponseExecutor, agentRuntimeExecutor, documentFacade,
                chatStreamService, chatRunService, chatRunLeaseService, chatDeltaCoalescer, runExecutionRegistry,
                runAdmissionControl, new ChatRunStopCoordinator(sessionService, chatStreamService, chatRunService,
                        chatRunLeaseService, runExecutionRegistry, agentRuntimeExecutor,
                        idGenerator), null, null, idGenerator, Schedulers.boundedElastic(),
                new DomainAgentProperties(), null);
    }

    FinanceEXChatService(SessionApplicationService sessionService,
                         MemoryApplicationService memoryService, RuntimeBindingApplicationService runtimeBindingService,
                         RouteSignalApplicationService routeSignalService,
                         IntentRecognitionRecordService intentRecognitionRecordService,
                         DomainAgentExecutor domainAgentExecutor,
                         SystemResponseExecutor systemResponseExecutor,
                         AgentRuntimeExecutor agentRuntimeExecutor, DocumentFacade documentFacade,
                         ChatStreamApplicationService chatStreamService,
                         ChatRunApplicationService chatRunService, ChatRunLeaseApplicationService chatRunLeaseService,
                         ChatDeltaCoalescer chatDeltaCoalescer, LocalChatRunExecutionRegistry runExecutionRegistry,
                         RunAdmissionControlService runAdmissionControl, IdGenerator idGenerator) {
        this(sessionService, memoryService, runtimeBindingService, routeSignalService, intentRecognitionRecordService,
                systemResponseExecutor, legacyCompatibleExecutor(domainAgentExecutor, agentRuntimeExecutor),
                documentFacade, chatStreamService, chatRunService,
                chatRunLeaseService, chatDeltaCoalescer, runExecutionRegistry, runAdmissionControl, idGenerator);
    }

    FinanceEXChatService(SessionApplicationService sessionService,
                         MemoryApplicationService memoryService, RuntimeBindingApplicationService runtimeBindingService,
                         RouteSignalApplicationService routeSignalService,
                         IntentRecognitionRecordService intentRecognitionRecordService,
                         DomainAgentExecutor domainAgentExecutor,
                         SystemResponseExecutor systemResponseExecutor,
                         AgentRuntimeExecutor agentRuntimeExecutor, DocumentFacade documentFacade,
                         ChatStreamApplicationService chatStreamService,
                         ChatRunApplicationService chatRunService, ChatRunLeaseApplicationService chatRunLeaseService,
                         ChatDeltaCoalescer chatDeltaCoalescer, LocalChatRunExecutionRegistry runExecutionRegistry,
                         RunAdmissionControlService runAdmissionControl, ChatRunStopCoordinator stopCoordinator,
                         ChatHitlApplicationService chatHitlService,
                         ChatRunTerminalCommitService terminalCommitService,
                         IdGenerator idGenerator,
                         Scheduler eventIoScheduler,
                         DomainAgentProperties domainAgentProperties) {
        this(sessionService, memoryService, runtimeBindingService, routeSignalService, intentRecognitionRecordService,
                systemResponseExecutor, legacyCompatibleExecutor(domainAgentExecutor, agentRuntimeExecutor),
                documentFacade, chatStreamService, chatRunService,
                chatRunLeaseService, chatDeltaCoalescer, runExecutionRegistry, runAdmissionControl, stopCoordinator,
                chatHitlService, terminalCommitService, idGenerator, eventIoScheduler, domainAgentProperties, null);
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
        if (hitl.waitingType() == ChatHitlWaitingType.INTENT_CLARIFICATION) {
            return executeIntentClarificationContinuation(user, claim, runId, session, forwardHeaders);
        }
        if (hitl.waitingType() == ChatHitlWaitingType.DOMAIN_AGENT_SWITCH_CONFIRMATION) {
            return executeDomainAgentSwitchContinuation(user, claim, runId, session, forwardHeaders);
        }
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

    private Flux<ChatEvent> executeIntentClarificationContinuation(UserContext user, ChatHitlClaimResult claim,
                                                                   String runId, ChatSession session,
                                                                   RuntimeForwardHeaders forwardHeaders) {
        ChatHitlRequest hitl = claim.request();
        String originalQuery = firstText(hitl.requestPayload().get("originalQuery"));
        ChatCommand command = commandWithIntentClarificationContext(user, session, originalQuery, hitl, claim.responsePayload());
        ChatMessage userMessage = new ChatMessage(hitl.userMessageId(), user.tenantId(), user.ownerUserId(),
                session.id(), "user", command.message(), null, Instant.now());
        ChatRunMessagePlan messagePlan = new ChatRunMessagePlan(ChatRunMode.NEXT,
                hitl.userMessageId(), userMessage, null);

        MemoryContext memory = MemoryContext.empty();
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>();
        AtomicReference<RouteTarget> routeRef = new AtomicReference<>();
        AtomicReference<RuntimeSessionMode> runtimeSessionModeRef = new AtomicReference<>(RuntimeSessionMode.RESUME);

        ChatRun run = chatRunService.createRunning(new CreateChatRunContext(
                runId,
                user,
                session.id(),
                null,
                null,
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
        Flux<ChatEvent> body = Flux.concat(responsePart, routeAndExecute(new RoutePipelineRequest(
                user, session, command, List.of(), List.of(), memory, runId, hitl.assistantMessageId(),
                forwardHeaders, routeRef, bindingRef, runtimeSessionModeRef, run)));
        RunEventPipelineContext context = new RunEventPipelineContext(user, session, messagePlan, bindingRef,
                new AssistantAssembly(), runId, executionClaim, new AtomicReference<>(), hitl);
        return persistAndPublishRunEvents(Flux.just(RunStartedEvent.of(runId, session.id())), context)
                .concatWith(Flux.defer(() -> persistAndPublishRunEvents(
                        Flux.concat(body,
                                        Flux.defer(() -> Flux.just(RunCompletedEvent.of(runId, session.id(),
                                                runCompletedPayload(routeRef.get(), bindingRef.get())))))
                                .onErrorResume(ex -> Flux.just(runtimeErrorEvent(runId, session.id(), ex))),
                        context)));
    }

    private Flux<ChatEvent> executeDomainAgentSwitchContinuation(UserContext user, ChatHitlClaimResult claim,
                                                                 String runId, ChatSession session,
                                                                 RuntimeForwardHeaders forwardHeaders) {
        ChatHitlRequest hitl = claim.request();
        boolean approved = Boolean.TRUE.equals(claim.responsePayload().get("approved"));
        String candidateDomainAgentId = firstText(hitl.requestPayload().get("candidateDomainAgentId"));
        String currentDomainAgentId = firstText(hitl.requestPayload().get("currentDomainAgentId"));
        String originalQuery = firstText(hitl.requestPayload().get("originalQuery"));
        RouteTarget route = RouteTarget.domainAgent(
                approved ? candidateDomainAgentId : currentDomainAgentId,
                approved ? "intent-confirmed" : "front-selected",
                1.0,
                approved ? "confirmed domain agent switch" : "declined domain agent switch");
        RuntimeBinding binding = approved
                ? runtimeBindingService.bindDomainAgentForRun(new DomainAgentBindingCommand(
                        user.tenantId(), user.ownerUserId(), session.id(), runId,
                        hitl.assistantMessageId(), candidateDomainAgentId,
                        "intent-confirmed", domainAgentSwitchBindingMetadata(hitl)))
                : runtimeBindingService.resumeForHitl(hitl, runId);
        ChatMessage userMessage = new ChatMessage(hitl.userMessageId(), user.tenantId(), user.ownerUserId(),
                session.id(), "user", originalQuery == null ? "" : originalQuery, null, Instant.now());
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
        Flux<ChatEvent> responsePart = Flux.just(domainAgentSwitchResponseEvent(runId, session.id(), hitl,
                claim.responsePayload()));
        Flux<ChatEvent> body;
        if (approved) {
            ChatCommand command = new ChatCommand(null, user.tenantId(), user.ownerUserId(), session.id(), null,
                    null, originalQuery == null ? "" : originalQuery, List.of(), Map.of(),
                    "DOMAIN_AGENT", candidateDomainAgentId, ChatRunMode.NEXT, hitl.assistantMessageId(), null, null);
            body = executeDomainAgentWithReroute(new DomainAgentRunContext(
                    command, runId, session, MemoryContext.empty(), route, user, bindingRef, forwardHeaders,
                    null, List.of(), new HashSet<>(), 0));
        } else {
            body = Flux.just(domainAgentSwitchDeclinedEvent(runId, session.id(), hitl));
        }
        return persistAndPublishRunEvents(
                Flux.concat(Flux.just(RunStartedEvent.of(runId, session.id())), responsePart, body,
                                Flux.just(RunCompletedEvent.of(runId, session.id(), runCompletedPayload(route, bindingRef.get()))))
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
                    command.attachments(), command.metadata(), command.targetType(), command.targetId(),
                    command.runMode(), command.parentMessageId(),
                    command.editedMessageId(), command.regeneratedMessageId());
            String explicitDomainAgentId = explicitDomainAgentId(identified);

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
            List<UploadedDocument> documents = attachments.isEmpty()
                    ? List.of()
                    : documentFacade.resolveDocumentsForUser(user, attachments);
            ChatCommand normalized = new ChatCommand(identified.commandId(), user.tenantId(), user.ownerUserId(),
                    session.id(), identified.conversationId(), identified.channel(), identified.message(),
                    attachments, identified.metadata(), identified.targetType(), identified.targetId(),
                    identified.runMode(), identified.parentMessageId(),
                    identified.editedMessageId(), identified.regeneratedMessageId());
            String runId = idGenerator.newId("run", IdGenerateContext.of(user.tenantId(), user.ownerUserId(), session.id()));

            // MemoryContext 是可选 SuperAgent 记忆增强。长短期记忆都关闭时这里返回空上下文，
            // 且不会查询 Redis、历史消息或长期记忆服务；当前用户输入也不会进入本轮上下文，避免重复。
            MemoryContext memory = memoryService.loadForRun(normalized);
            ChatRunMessagePlan messagePlan = sessionService.prepareRunMessage(user, normalized, session, runId, attachments);
            ChatCommand runCommand = commandForExecution(normalized, messagePlan);
            String runtimeBindingLeafId = runtimeBindingLeafId(messagePlan);
            RouteTarget route = null;
            RuntimeBinding binding = null;
            RuntimeSessionMode runtimeSessionMode = RuntimeSessionMode.RESUME;
            if (explicitDomainAgentId != null) {
                route = RouteTarget.domainAgent(explicitDomainAgentId, "front-selected", 1.0,
                        "front selected domain agent");
                binding = runtimeBindingService.bindDomainAgentForRun(new DomainAgentBindingCommand(
                        user.tenantId(), user.ownerUserId(), session.id(), runId,
                        runtimeBindingLeafId, explicitDomainAgentId, "front-selected",
                        domainAgentBindingMetadata(route, null)));
            } else {
                var activeRuntimeBinding = runtimeBindingService.findActiveBySession(user.tenantId(),
                        user.ownerUserId(), session.id());
                if (activeRuntimeBinding.isPresent()) {
                    binding = runtimeBindingService.touchForRun(activeRuntimeBinding.get(), runId);
                    runtimeSessionMode = RuntimeSessionMode.RESUME;
                    if (RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(binding.provider())) {
                        String domainAgentId = domainAgentId(binding);
                        route = RouteTarget.domainAgent(domainAgentId, "runtime-binding", 1.0,
                                "active domain agent binding");
                    } else {
                        route = RouteTarget.agentRuntime("runtime-binding", 1.0, "active relay runtime binding");
                    }
                }
            }
            AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>(binding);
            AtomicReference<RouteTarget> routeRef = new AtomicReference<>(route);
            AtomicReference<RuntimeSessionMode> runtimeSessionModeRef = new AtomicReference<>(runtimeSessionMode);
            AtomicReference<Map<String, Object>> pendingHitlPayloadRef = new AtomicReference<>();
            AssistantAssembly assistant = new AssistantAssembly();
            ChatRun run = chatRunService.createRunning(new CreateChatRunContext(
                    runId,
                    user,
                    session.id(),
                    routeRef.get(),
                    bindingRef.get(),
                    normalized.metadata(),
                    messagePlan.runMode(),
                    messagePlan.parentMessageId(),
                    messagePlan.userMessage().id()
            ));
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
                /*
                 * 外部用例库/意图路由放在 run pipeline 内执行。这样 run.started 会先落库和发布，
                 * 慢意图服务只影响后续输出，不会阻塞前端获得 runId 与首个事件。
                 */
                Flux<ChatEvent> body = routeAndExecute(new RoutePipelineRequest(
                        user, session, runCommand, attachments, documents, memory, runId, runtimeBindingLeafId,
                        headerSnapshot, routeRef, bindingRef, runtimeSessionModeRef, run));

                // 外层统一补齐 run.started/run.completed，接口层只需要转发事件流。
                // onErrorResume 必须放在持久化之前，确保运行异常转换出的 run.failed 事件也会落库。
                RunEventPipelineContext context = new RunEventPipelineContext(user, session, messagePlan,
                        bindingRef, assistant, runId, executionClaim, pendingHitlPayloadRef, null);
                return persistAndPublishRunEvents(Flux.just(RunStartedEvent.of(runId, session.id())), context)
                        .concatWith(Flux.defer(() -> persistAndPublishRunEvents(
                                Flux.concat(body,
                                                Flux.defer(() -> Flux.just(RunCompletedEvent.of(runId, session.id(),
                                                        runCompletedPayload(routeRef.get(), bindingRef.get())))))
                                        .onErrorResume(ex -> Flux.just(runtimeErrorEvent(runId, session.id(), ex))),
                                context)));
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

    private Flux<ChatEvent> executeDomainAgentWithReroute(DomainAgentRunContext context) {
        if (context.route() == null || context.route().selectedAgentCode() == null
                || context.route().selectedAgentCode().isBlank()) {
            return Flux.error(new IllegalStateException("DomainAgent 路由缺少目标 ID"));
        }
        AtomicReference<DomainAgentRefusal> refusalRef = new AtomicReference<>();
        Flux<ChatEvent> current = agentRuntimeExecutor.execute(new RuntimeExecutionContext(
                        context.command(),
                        context.runId(),
                        context.memory(),
                        context.intentDecision(),
                        context.route(),
                        context.user(),
                        context.bindingRef().get(),
                        RuntimeSessionMode.RESUME,
                        context.forwardHeaders(),
                        context.documents()))
                .doOnNext(event -> {
                    DomainAgentRefusal refusal = domainAgentRefusal(event);
                    if (refusal != null) {
                        refusalRef.compareAndSet(null, refusal);
                    }
                })
                // 被拒答的 DomainAgent 通常会输出 endFlag；本 run 还可能继续切换到新 Agent，
                // 因此这里吞掉旧 Agent 的 message.completed，避免前端误以为回答已最终闭合。
                .filter(event -> !("message.completed".equals(event.type()) && refusalRef.get() != null));
        return current.concatWith(Flux.defer(() -> continueAfterDomainAgentRefusal(context, refusalRef.get())));
    }

    private Flux<ChatEvent> continueAfterDomainAgentRefusal(DomainAgentRunContext context,
                                                           DomainAgentRefusal refusal) {
        if (refusal == null) {
            return Flux.empty();
        }
        String currentDomainAgentId = context.route().selectedAgentCode();
        Set<String> rejected = new HashSet<>(context.rejectedDomainAgentIds());
        rejected.add(currentDomainAgentId);
        if (context.rerouteCount() >= domainAgentProperties.normalizedMaxReroutes()) {
            return Flux.just(domainAgentRerouteMetadata(context, refusal, null, "MAX_REROUTES_REACHED"));
        }
        ChatCommand rerouteCommand = commandWithDomainRejectContext(context.command(), currentDomainAgentId, refusal);
        DomainAgentRerouteContext rerouteContext = new DomainAgentRerouteContext(
                context, refusal, currentDomainAgentId, rejected);
        return routeSignalService.routeInitialWithProgress(
                        context.user(),
                        context.session(),
                        rerouteCommand,
                        rerouteCommand.attachments(),
                        context.memory())
                .concatMap(frame -> {
                    if (frame.progressFrame()) {
                        return Flux.just(routeProgressEvent(context.runId(), context.session().id(), frame.progress()));
                    }
                    return continueAfterDomainAgentReroute(rerouteContext, frame.result());
                });
    }

    private Flux<ChatEvent> continueAfterDomainAgentReroute(DomainAgentRerouteContext reroute,
                                                            RouteSignalResult nextSignal) {
        DomainAgentRunContext context = reroute.context();
        DomainAgentRefusal refusal = reroute.refusal();
        if (nextSignal.waitingIntentClarification()) {
            return Flux.concat(
                    Flux.just(domainAgentRerouteMetadata(context, refusal, nextSignal.route(), "INTENT_CLARIFICATION_REQUIRED")),
                    intentClarificationWaitingBody(context.runId(), context.session().id(),
                            nextSignal.intentClarificationPayload()));
        }
        RouteTarget nextRoute = nextSignal.route();
        if (nextRoute == null || nextRoute.type() != RouteType.DOMAIN_AGENT
                || nextRoute.selectedAgentCode() == null || nextRoute.selectedAgentCode().isBlank()
                || reroute.rejectedDomainAgentIds().contains(nextRoute.selectedAgentCode())) {
            return Flux.just(domainAgentRerouteMetadata(context, refusal, nextRoute, "NO_AVAILABLE_DOMAIN_AGENT"));
        }
        recordIntentIfPresent(context, nextSignal.intentDecision(), nextRoute);
        String routeSource = routeSource(context.bindingRef().get());
        if ("front-selected".equals(routeSource)
                && !reroute.currentDomainAgentId().equals(nextRoute.selectedAgentCode())) {
            return Flux.just(domainAgentSwitchConfirmationRequest(context, refusal, nextSignal));
        }
        runtimeBindingService.markNotRoutable(context.bindingRef().get(), refusal.code());
        RuntimeBinding nextBinding = runtimeBindingService.bindDomainAgentForRun(new DomainAgentBindingCommand(
                context.user().tenantId(),
                context.user().ownerUserId(),
                context.session().id(),
                context.runId(),
                runtimeBindingLeafIdForCommand(context.command()),
                nextRoute.selectedAgentCode(),
                nextRoute.routeSource(),
                domainAgentBindingMetadata(nextRoute, nextSignal.intentDecision())));
        context.bindingRef().set(nextBinding);
        DomainAgentRunContext nextContext = new DomainAgentRunContext(
                context.command(),
                context.runId(),
                context.session(),
                context.memory(),
                nextRoute,
                context.user(),
                context.bindingRef(),
                context.forwardHeaders(),
                nextSignal.intentDecision(),
                context.documents(),
                reroute.rejectedDomainAgentIds(),
                context.rerouteCount() + 1);
        return Flux.concat(Flux.just(domainAgentRerouteMetadata(context, refusal, nextRoute, "AUTO_SWITCH")),
                executeDomainAgentWithReroute(nextContext));
    }

    private RuntimeEvent domainAgentSwitchConfirmationRequest(DomainAgentRunContext context,
                                                              DomainAgentRefusal refusal,
                                                              RouteSignalResult nextSignal) {
        RouteTarget candidate = nextSignal.route();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "domain-agent-switch-confirmation-request");
        payload.put("waitingType", ChatHitlWaitingType.DOMAIN_AGENT_SWITCH_CONFIRMATION.name());
        payload.put("currentDomainAgentId", context.route().selectedAgentCode());
        payload.put("candidateDomainAgentId", candidate.selectedAgentCode());
        payload.put("candidateIntentCode", nextSignal.intentDecision() == null ? null : nextSignal.intentDecision().intentCode());
        payload.put("candidateIntentName", nextSignal.intentDecision() == null ? null : nextSignal.intentDecision().intentName());
        payload.put("rejectCode", refusal.code());
        payload.put("rejectMessage", refusal.message());
        payload.put("originalQuery", context.command().message());
        payload.put("routeSource", "front-selected");
        payload.put("candidateRouteSource", candidate.routeSource());
        return RuntimeEvent.card(context.runId(), context.session().id(), payload);
    }

    private Flux<ChatEvent> intentClarificationWaitingBody(String runId, String sessionId,
                                                           Map<String, Object> requestPayload) {
        return Flux.just(intentClarificationRequestEvent(runId, sessionId, requestPayload),
                MessageCompletedEvent.of(runId, sessionId));
    }

    private RuntimeEvent intentClarificationRequestEvent(String runId, String sessionId,
                                                         Map<String, Object> requestPayload) {
        Map<String, Object> payload = new LinkedHashMap<>(requestPayload == null ? Map.of() : requestPayload);
        payload.put("source", "intent-service");
        payload.put("sourceType", "intent-clarification-request");
        payload.put("waitingType", ChatHitlWaitingType.INTENT_CLARIFICATION.name());
        return RuntimeEvent.card(runId, sessionId, Map.copyOf(payload));
    }

    private RuntimeEvent domainAgentRerouteMetadata(DomainAgentRunContext context, DomainAgentRefusal refusal,
                                                    RouteTarget nextRoute, String action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "domain-agent-reroute");
        payload.put("metadataType", "domain_agent_reroute");
        payload.put("action", action);
        payload.put("currentDomainAgentId", context.route().selectedAgentCode());
        payload.put("rejectCode", refusal.code());
        payload.put("rejectMessage", refusal.message());
        if (nextRoute != null) {
            payload.put("candidateDomainAgentId", nextRoute.selectedAgentCode());
            payload.put("candidateRouteSource", nextRoute.routeSource());
        }
        return RuntimeEvent.metadata(context.runId(), context.session().id(), payload);
    }

    private DomainAgentRefusal domainAgentRefusal(ChatEvent event) {
        if (event == null || event.payload() == null) {
            return null;
        }
        String code = firstText(event.payload().get("code"), event.payload().get("errorCode"),
                event.payload().get("reasonCode"), event.payload().get("rejectCode"));
        if (code == null) {
            Object sourcePayload = event.payload().get("sourcePayload");
            if (sourcePayload instanceof Map<?, ?> map) {
                code = firstText(map.get("code"), map.get("errorCode"), map.get("reasonCode"), map.get("rejectCode"));
            }
        }
        if (code == null || !domainAgentProperties.normalizedRefusalCodes()
                .contains(code.trim().toUpperCase(java.util.Locale.ROOT))) {
            return null;
        }
        String message = firstText(event.payload().get("message"), event.payload().get("reason"),
                event.payload().get("text"));
        return new DomainAgentRefusal(code.trim(), message);
    }

    private ChatCommand commandWithDomainRejectContext(ChatCommand command, String domainAgentId,
                                                       DomainAgentRefusal refusal) {
        Map<String, Object> metadata = new LinkedHashMap<>(command.metadata() == null ? Map.of() : command.metadata());
        metadata.put("routeTrigger", "domain_reject");
        metadata.put("lastIntentRejectReason", Map.of(
                "lastDomainAgentId", domainAgentId,
                "domainRejectCode", refusal.code(),
                "domainRejectMessage", refusal.message() == null ? "" : refusal.message()
        ));
        return new ChatCommand(command.commandId(), command.tenantId(), command.userId(), command.sessionId(),
                command.conversationId(), command.channel(), command.message(), command.attachments(), metadata,
                command.targetType(), command.targetId(), command.runMode(), command.parentMessageId(),
                command.editedMessageId(), command.regeneratedMessageId());
    }

    private ChatCommand commandWithIntentClarificationContext(UserContext user, ChatSession session,
                                                              String originalQuery, ChatHitlRequest hitl,
                                                              Map<String, Object> responsePayload) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, Object> requestPayload = hitl.requestPayload() == null ? Map.of() : hitl.requestPayload();
        String clarifyAnswer = intentClarificationAnswer(responsePayload);
        String resolvedOriginalQuery = blankToDefault(
                firstText(originalQuery, requestPayload.get("originalQuery"), clarifyAnswer), "");
        List<Map<String, Object>> clarificationHistory = appendClarificationHistory(
                requestPayload, responsePayload, clarifyAnswer, resolvedOriginalQuery);
        Map<String, Object> intentClarification = new LinkedHashMap<>();
        intentClarification.put("hitlRequestId", hitl.id());
        intentClarification.put("intentSessionId", hitl.runtimeSessionId() == null ? "" : hitl.runtimeSessionId());
        intentClarification.put("intentRequestId", requestPayload.getOrDefault("intentRequestId", ""));
        intentClarification.put("originalQuery", resolvedOriginalQuery);
        putNonNull(intentClarification, "clarifyQuestion", clarifyQuestion(requestPayload));
        putNonNull(intentClarification, "clarificationType", clarificationType(requestPayload));
        intentClarification.put("answerText", clarifyAnswer == null ? "" : clarifyAnswer);
        intentClarification.put("clarificationHistory", clarificationHistory);
        intentClarification.put("request", requestPayload);
        intentClarification.put("response", responsePayload == null ? Map.of() : responsePayload);
        metadata.put("routeTrigger", "clarify_answer");
        metadata.put("intentClarification", Map.copyOf(intentClarification));
        return new ChatCommand(null, user.tenantId(), user.ownerUserId(), session.id(), null,
                null, clarifyAnswer == null ? "" : clarifyAnswer, List.of(), Map.copyOf(metadata),
                null, null, ChatRunMode.NEXT, hitl.assistantMessageId(), null, null);
    }

    private List<Map<String, Object>> appendClarificationHistory(Map<String, Object> requestPayload,
                                                                 Map<String, Object> responsePayload,
                                                                 String answerText,
                                                                 String originalQuery) {
        List<Map<String, Object>> history = new java.util.ArrayList<>(clarificationHistory(requestPayload));
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("type", "clarify");
        putNonNull(current, "query", firstText(requestPayload.get("clarifyTriggerQuery"),
                requestPayload.get("originalQuery"), originalQuery));
        String question = clarifyQuestion(requestPayload);
        if (question != null) {
            current.put("clarifyQuestion", question);
        }
        String type = clarificationType(requestPayload);
        if (type != null) {
            current.put("clarificationType", type);
        }
        if (answerText != null && !answerText.isBlank()) {
            current.put("answer", answerText);
        }
        if (responsePayload != null && !responsePayload.isEmpty()) {
            current.put("response", responsePayload);
        }
        if (current.size() > 1) {
            history.add(Map.copyOf(current));
        }
        return List.copyOf(history);
    }

    private String intentClarificationAnswer(Map<String, Object> responsePayload) {
        if (responsePayload == null || responsePayload.isEmpty()) {
            return "";
        }
        Object answers = responsePayload.get("questionnaireAnswers");
        if (answers instanceof Map<?, ?> answerMap && !answerMap.isEmpty()) {
            return answerMap.values().stream()
                    .findFirst()
                    .map(String::valueOf)
                    .orElse("");
        }
        return firstText(responsePayload.get("answerText"), responsePayload.get("answer"),
                responsePayload.get("content"), responsePayload.get("message"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> clarificationHistory(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("clarificationHistory");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    ((Map<String, Object>) item).forEach((key, itemValue) -> {
                        if (key != null && itemValue != null) {
                            copy.put(key, itemValue);
                        }
                    });
                    return Map.copyOf(copy);
                })
                .toList();
    }

    private String clarifyQuestion(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        Object clarification = payload.get("clarification");
        String nested = clarification instanceof Map<?, ?> map ? firstText(map.get("clarifyQuestion")) : null;
        return firstText(payload.get("clarifyQuestion"), payload.get("question"), nested);
    }

    private String clarificationType(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        Object clarification = payload.get("clarification");
        String nested = clarification instanceof Map<?, ?> map ? firstText(map.get("type")) : null;
        return firstText(payload.get("clarificationType"), payload.get("type"), nested);
    }

    private void putNonNull(Map<String, Object> target, String key, Object value) {
        if (target != null && key != null && value != null) {
            target.put(key, value);
        }
    }

    private void recordIntentIfPresent(DomainAgentRunContext context, IntentDecision intent, RouteTarget route) {
        if (intent == null) {
            return;
        }
        intentRecognitionRecordService.recordAsync(IntentRecognitionRecordSnapshot.of(
                new IntentRecognitionRecordSnapshot.IntentRecognitionRecordInput(
                        context.user(), context.command(), context.runId(), intent, route,
                        0.0, null)));
    }

    private String runtimeBindingLeafIdForCommand(ChatCommand command) {
        return command == null ? null : command.parentMessageId();
    }

    private Map<String, Object> domainAgentBindingMetadata(RouteTarget route, IntentDecision intent) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (route != null) {
            metadata.put("domainAgentId", route.selectedAgentCode());
            metadata.put("routeSource", route.routeSource());
        }
        if (intent != null) {
            metadata.put("intentCode", intent.intentCode());
            metadata.put("intentName", intent.intentName());
            metadata.put("intentConfidence", intent.confidence());
        }
        return Map.copyOf(metadata);
    }

    private String domainAgentId(RuntimeBinding binding) {
        if (binding == null || binding.metadata() == null) {
            return null;
        }
        Object value = binding.metadata().get("domainAgentId");
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private String routeSource(RuntimeBinding binding) {
        if (binding == null || binding.metadata() == null) {
            return null;
        }
        Object value = binding.metadata().get("routeSource");
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
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
                         * 下游 Runtime/DomainAgent 的输出不是身份事实。任何 runId/sessionId 不匹配的事件
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

    private Flux<ChatEvent> routeAndExecute(RoutePipelineRequest request) {
        Flux<RouteSignalFrame> frames = request.routeRef().get() == null
                ? routeSignalService.routeInitialWithProgress(request.user(), request.session(), request.runCommand(),
                request.attachments(), request.memory())
                : Flux.just(RouteSignalFrame.result(RouteSignalResult.of(request.routeRef().get())));
        return frames.concatMap(frame -> {
            if (frame.progressFrame()) {
                return Flux.just(routeProgressEvent(request.runId(), request.session().id(), frame.progress()));
            }
            RouteExecutionResolution resolution = resolveRouteForRun(new RouteResolutionRequest(
                    request.user(), request.session(), request.runCommand(), request.attachments(), request.memory(),
                    request.runId(), request.runtimeBindingLeafId(), request.routeRef().get(),
                    request.bindingRef().get(), request.runtimeSessionModeRef().get()), frame.result());
            request.routeRef().set(resolution.route());
            request.bindingRef().set(resolution.binding());
            request.runtimeSessionModeRef().set(resolution.runtimeSessionMode());
            bestEffortBindResolvedRoute(request.runId(), resolution.route(), resolution.binding());
            if (resolution.intent() != null) {
                intentRecognitionRecordService.recordAsync(IntentRecognitionRecordSnapshot.of(
                        new IntentRecognitionRecordSnapshot.IntentRecognitionRecordInput(
                                request.user(),
                                request.runCommand(),
                                request.run().id(),
                                resolution.intent(),
                                resolution.route(),
                                resolution.intentConfidenceThreshold() == null ? 0.0 : resolution.intentConfidenceThreshold(),
                                resolution.intentLatencyMs())));
            }
            if (resolution.waitingIntentClarification()) {
                return intentClarificationWaitingBody(request.runId(), request.session().id(),
                        resolution.intentClarificationPayload());
            }
            return switch (resolution.route().type()) {
                case DOMAIN_AGENT -> executeDomainAgentWithReroute(new DomainAgentRunContext(
                        request.runCommand(), request.runId(), request.session(), request.memory(),
                        resolution.route(), request.user(), request.bindingRef(), request.forwardHeaders(),
                        resolution.intent(), request.documents(), new HashSet<>(), 0));
                case SYSTEM_RESPONSE -> systemResponseExecutor.execute(request.runCommand(), request.runId(),
                        resolution.intent(), resolution.route());
                case AGENT_RUNTIME -> agentRuntimeExecutor.execute(new RuntimeExecutionContext(
                        request.runCommand(), request.runId(), request.memory(), resolution.intent(),
                        resolution.route(), request.user(), request.bindingRef().get(),
                        resolution.runtimeSessionMode(), request.forwardHeaders(), request.documents()));
            };
        });
    }

    private void bestEffortBindResolvedRoute(String runId, RouteTarget route, RuntimeBinding binding) {
        try {
            chatRunService.bindResolvedRoute(runId, route, binding);
        } catch (RuntimeException ex) {
            log.warn("ChatRun resolved route diagnostic update failed and was ignored. runId={}, routeType={}, agentCode={}, reason={}",
                    runId,
                    route == null || route.type() == null ? null : route.type().name(),
                    route == null ? null : route.selectedAgentCode(),
                    ex.getMessage());
        }
    }

    private RuntimeEvent routeProgressEvent(String runId, String sessionId, RouteSignalProgress progress) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "route-progress");
        payload.put("stage", progress == null ? "route_progress" : blankToDefault(progress.stage(), "route_progress"));
        payload.put("message", progress == null ? "正在选择合适能力" : blankToDefault(progress.message(), "正在选择合适能力"));
        if (progress != null && progress.attributes() != null) {
            progress.attributes().forEach((key, value) -> {
                if (key != null && value != null && !payload.containsKey(key)) {
                    payload.put(key, value);
                }
            });
        }
        return RuntimeEvent.progress(runId, sessionId, Map.copyOf(payload));
    }

    private RouteExecutionResolution resolveRouteForRun(RouteResolutionRequest request, RouteSignalResult routeSignalResult) {
        UserContext user = request.user();
        ChatSession session = request.session();
        String runId = request.runId();
        String runtimeBindingLeafId = request.runtimeBindingLeafId();
        RouteTarget route = request.currentRoute();
        RuntimeBinding binding = request.currentBinding();
        RuntimeSessionMode runtimeSessionMode = request.currentRuntimeSessionMode() == null
                ? RuntimeSessionMode.RESUME
                : request.currentRuntimeSessionMode();
        IntentDecision intent = null;
        Long intentLatencyMs = null;
        Double intentConfidenceThreshold = null;
        boolean waitingIntentClarification = false;
        Map<String, Object> intentClarificationPayload = Map.of();

        if (route == null) {
            // 首轮路由只读取已启用的外部路由信号。现在该调用位于 run.started 之后，
            // 因此慢意图服务不会阻塞前端拿到 runId 和首个事件。
            RouteSignalResult routeSignal = routeSignalResult == null
                    ? routeSignalService.routeInitial(user, session, request.runCommand(),
                    request.attachments(), request.memory())
                    : routeSignalResult;
            route = routeSignal.route();
            intent = routeSignal.intentDecision();
            intentLatencyMs = routeSignal.intentLatencyMs();
            intentConfidenceThreshold = routeSignal.intentConfidenceThreshold();
            waitingIntentClarification = routeSignal.waitingIntentClarification();
            intentClarificationPayload = routeSignal.intentClarificationPayload();
            if (route == null) {
                route = RouteTarget.agentRuntime("fallback", 0.0, "route resolution returned empty route");
            } else if (!waitingIntentClarification && route.type() == RouteType.DOMAIN_AGENT) {
                binding = runtimeBindingService.bindDomainAgentForRun(new DomainAgentBindingCommand(
                        user.tenantId(), user.ownerUserId(), session.id(), runId,
                        runtimeBindingLeafId, route.selectedAgentCode(), route.routeSource(),
                        domainAgentBindingMetadata(route, intent)));
            } else if (!waitingIntentClarification && route.type() == RouteType.AGENT_RUNTIME) {
                RuntimeBindingResolution resolution = runtimeBindingService.resolveForRun(user.tenantId(),
                        user.ownerUserId(), session.id(), runId, runtimeBindingLeafId);
                binding = resolution.binding();
                runtimeSessionMode = resolution.sessionMode();
            }
        }
        if (route == null) {
            route = RouteTarget.agentRuntime("fallback", 0.0, "route resolution returned empty route");
        }
        return new RouteExecutionResolution(route, binding, runtimeSessionMode, intent, intentLatencyMs,
                intentConfidenceThreshold, waitingIntentClarification, intentClarificationPayload);
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
        recordRouteMemoryAfterCommitted(stored, context);
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
        recordRouteMemoryAfterCommitted(result.event(), context);
        return result.event();
    }

    private void recordRouteMemoryAfterCommitted(ChatEvent stored, RunEventPipelineContext context) {
        if (routeMemoryService == null || stored == null || context == null) {
            return;
        }
        if ("run.waiting_user".equals(stored.type())) {
            recordIntentClarificationAfterWaiting(stored, context);
            return;
        }
        if (!"run.completed".equals(stored.type())) {
            return;
        }
        /*
         * RouteMemory 是后续意图的上下文增强。只有本轮 run 已经成功闭合后才折叠澄清链、
         * 写入成功 route，避免 run 失败或等待确认时污染下一轮路由历史。
         */
        RuntimeBinding binding = context.bindingRef().get();
        if (binding == null || !RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(binding.provider())) {
            routeMemoryService.completeWithoutRoute(context.user(), context.session().id());
            return;
        }
        if (hasDomainAgentRefusalWithoutSuccessfulSwitch(context)) {
            routeMemoryService.completeWithoutRoute(context.user(), context.session().id());
            return;
        }
        String domainAgentId = domainAgentId(binding);
        if (domainAgentId == null || domainAgentId.isBlank()) {
            routeMemoryService.completeWithoutRoute(context.user(), context.session().id());
            return;
        }
        RouteTarget route = RouteTarget.domainAgent(domainAgentId,
                blankToDefault(routeSource(binding), "domain-agent"),
                1.0,
                "completed domain agent route");
        routeMemoryService.completeRoute(new RouteMemoryApplicationService.RouteMemoryRouteCommand(
                context.user(), context.session().id(), context.runId(), routeMemoryQuery(context), null, route));
    }

    private boolean hasDomainAgentRefusalWithoutSuccessfulSwitch(RunEventPipelineContext context) {
        if (context == null || context.assistant() == null) {
            return false;
        }
        return context.assistant().parts().stream()
                .anyMatch(part -> "DOMAIN_AGENT_REFUSAL".equals(part.partType()));
    }

    private void recordIntentClarificationAfterWaiting(ChatEvent stored, RunEventPipelineContext context) {
        Map<String, Object> payload = stored.payload() == null ? Map.of() : stored.payload();
        if (!ChatHitlWaitingType.INTENT_CLARIFICATION.name().equals(String.valueOf(payload.get("waitingType")))) {
            return;
        }
        Map<String, Object> requestPayload = context.pendingHitlPayloadRef().get();
        if (requestPayload == null || requestPayload.isEmpty()) {
            requestPayload = payload;
        }
        Object hitlRequestId = payload.get("hitlRequestId");
        routeMemoryService.appendClarification(context.user(), context.session().id(), stored.runId(),
                hitlRequestId == null ? null : String.valueOf(hitlRequestId), requestPayload);
    }

    private String routeMemoryQuery(RunEventPipelineContext context) {
        if (context == null || context.messagePlan() == null || context.messagePlan().userMessage() == null) {
            return "";
        }
        if (context.continuationHitlRequest() != null
                && context.continuationHitlRequest().waitingType() == ChatHitlWaitingType.INTENT_CLARIFICATION) {
            String folded = foldedIntentClarificationQuery(context.continuationHitlRequest(),
                    context.messagePlan().userMessage().content());
            if (folded != null && !folded.isBlank()) {
                return folded;
            }
        }
        String content = context.messagePlan().userMessage().content();
        return content == null ? "" : content;
    }

    private String foldedIntentClarificationQuery(ChatHitlRequest hitl, String fallbackAnswer) {
        Map<String, Object> payload = hitl.requestPayload() == null ? Map.of() : hitl.requestPayload();
        String originalQuery = firstText(payload.get("originalQuery"), fallbackAnswer);
        List<Map<String, Object>> history = new java.util.ArrayList<>(clarificationHistory(payload));
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("type", "clarify");
        putNonNull(current, "query", firstText(payload.get("clarifyTriggerQuery"), payload.get("originalQuery"), originalQuery));
        putNonNull(current, "clarifyQuestion", clarifyQuestion(payload));
        putNonNull(current, "clarificationType", clarificationType(payload));
        Map<String, Object> responsePayload = hitl.responsePayload() == null ? Map.of() : hitl.responsePayload();
        String answer = firstText(responsePayload.get("answerText"), fallbackAnswer);
        Object questionnaireAnswers = responsePayload.get("questionnaireAnswers");
        if (questionnaireAnswers instanceof Map<?, ?> answers && !answers.isEmpty()) {
            answer = answers.values().stream().findFirst().map(String::valueOf).orElse(answer);
        }
        putNonNull(current, "answer", answer);
        if (current.size() > 1) {
            history.add(Map.copyOf(current));
        }
        StringBuilder builder = new StringBuilder();
        builder.append("user:").append(originalQuery == null ? "" : originalQuery);
        for (Map<String, Object> item : history) {
            String question = firstText(item.get("clarifyQuestion"), item.get("question"));
            String itemAnswer = firstText(item.get("answer"), item.get("answerText"));
            if (question != null) {
                builder.append("；澄清问:").append(question);
            }
            if (itemAnswer != null) {
                builder.append("；用户:").append(itemAnswer);
            }
        }
        return builder.toString();
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
        boolean intentClarification = ChatHitlWaitingType.INTENT_CLARIFICATION.name()
                .equals(String.valueOf(requestPayload.get("waitingType")));
        RuntimeBinding binding = context.bindingRef().get();
        if (!intentClarification && (binding == null || binding.id() == null || binding.id().isBlank())) {
            throw new IllegalStateException("HITL 等待态缺少 RuntimeBinding，无法续接 Runtime");
        }
        String runtimeProvider = intentClarification ? "intent-service" : binding.provider();
        String runtimeSessionId = runtimeSessionId(requestPayload, binding);
        return chatHitlService.prepareWaiting(new ChatHitlCreateContext(
                context.user(),
                context.session(),
                context.runId(),
                context.messagePlan().userMessage(),
                target.assistantMessageId(),
                runtimeProvider,
                intentClarification || binding == null ? null : binding.id(),
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
        copyIfPresent(waitingRequest.requestPayload(), payload,
                "currentDomainAgentId", "candidateDomainAgentId", "candidateIntentCode",
                "candidateIntentName", "rejectCode", "rejectMessage",
                "intentSessionId", "intentRequestId", "originalQuery");
        return new RunWaitingUserEvent(event.runId(), event.sessionId(), event.sequence(),
                event.createdAt(), Map.copyOf(payload));
    }

    private void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String... keys) {
        if (from == null || to == null || keys == null) {
            return;
        }
        for (String key : keys) {
            Object value = from.get(key);
            if (value != null) {
                to.put(key, value);
            }
        }
    }

    private void rememberPendingHitlRequest(ChatEvent stored, RunEventPipelineContext context) {
        if (!questionnaireApprovalRequest(stored) && !intentClarificationRequest(stored)
                && !domainAgentSwitchConfirmationRequest(stored)) {
            return;
        }
        RuntimeBinding binding = context.bindingRef().get();
        String runtimeProvider = binding == null ? null : binding.provider();
        if (!domainAgentSwitchConfirmationRequest(stored) && !intentClarificationRequest(stored)
                && !agentRuntimeExecutor.supportsWaitingUserResponse(runtimeProvider)) {
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

    private boolean domainAgentSwitchConfirmationRequest(ChatEvent event) {
        if (event == null || !"runtime.card".equals(event.type()) || event.payload() == null) {
            return false;
        }
        return "domain-agent-switch-confirmation-request".equals(String.valueOf(event.payload().get("sourceType")));
    }

    private boolean intentClarificationRequest(ChatEvent event) {
        if (event == null || !"runtime.card".equals(event.type()) || event.payload() == null) {
            return false;
        }
        return "intent-clarification-request".equals(String.valueOf(event.payload().get("sourceType")));
    }

    private String runtimeSessionId(Map<String, Object> payload, RuntimeBinding binding) {
        Object fromPayload = payload == null ? null : payload.get("runtimeSessionId");
        if (fromPayload != null && !String.valueOf(fromPayload).isBlank()) {
            return String.valueOf(fromPayload);
        }
        Object intentSessionId = payload == null ? null : payload.get("intentSessionId");
        if (intentSessionId != null && !String.valueOf(intentSessionId).isBlank()) {
            return String.valueOf(intentSessionId);
        }
        return binding == null ? null : binding.runtimeSessionId();
    }

    private RuntimeEvent clarificationResponseEvent(String runId, String sessionId, ChatHitlRequest hitl,
                                                    Map<String, Object> responsePayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", hitl.waitingType() == ChatHitlWaitingType.INTENT_CLARIFICATION
                ? "intent-clarification-response"
                : "clarification-response");
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

    private RuntimeEvent domainAgentSwitchResponseEvent(String runId, String sessionId, ChatHitlRequest hitl,
                                                        Map<String, Object> responsePayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "domain-agent-switch-response");
        payload.put("hitlRequestId", hitl.id());
        payload.put("waitingType", hitl.waitingType().name());
        payload.put("approved", responsePayload.get("approved"));
        payload.put("currentDomainAgentId", hitl.requestPayload().get("currentDomainAgentId"));
        payload.put("candidateDomainAgentId", hitl.requestPayload().get("candidateDomainAgentId"));
        payload.put("candidateIntentName", hitl.requestPayload().get("candidateIntentName"));
        payload.put("metadata", responsePayload.get("metadata"));
        return RuntimeEvent.card(runId, sessionId, Map.copyOf(payload));
    }

    private RuntimeEvent domainAgentSwitchDeclinedEvent(String runId, String sessionId, ChatHitlRequest hitl) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "domain-agent-switch-declined");
        payload.put("hitlRequestId", hitl.id());
        payload.put("currentDomainAgentId", hitl.requestPayload().get("currentDomainAgentId"));
        payload.put("candidateDomainAgentId", hitl.requestPayload().get("candidateDomainAgentId"));
        payload.put("rejectCode", hitl.requestPayload().get("rejectCode"));
        payload.put("rejectMessage", hitl.requestPayload().get("rejectMessage"));
        return RuntimeEvent.card(runId, sessionId, Map.copyOf(payload));
    }

    private Map<String, Object> domainAgentSwitchBindingMetadata(ChatHitlRequest hitl) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("domainAgentId", hitl.requestPayload().get("candidateDomainAgentId"));
        metadata.put("routeSource", "intent-confirmed");
        metadata.put("intentCode", hitl.requestPayload().get("candidateIntentCode"));
        metadata.put("intentName", hitl.requestPayload().get("candidateIntentName"));
        metadata.put("confirmedFromDomainAgentId", hitl.requestPayload().get("currentDomainAgentId"));
        metadata.put("confirmedHitlRequestId", hitl.id());
        return Map.copyOf(metadata);
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

    private record RouteExecutionResolution(
            RouteTarget route,
            RuntimeBinding binding,
            RuntimeSessionMode runtimeSessionMode,
            IntentDecision intent,
            Long intentLatencyMs,
            Double intentConfidenceThreshold,
            boolean waitingIntentClarification,
            Map<String, Object> intentClarificationPayload
    ) {
    }

    private record RoutePipelineRequest(
            UserContext user,
            ChatSession session,
            ChatCommand runCommand,
            List<AttachmentRef> attachments,
            List<UploadedDocument> documents,
            MemoryContext memory,
            String runId,
            String runtimeBindingLeafId,
            RuntimeForwardHeaders forwardHeaders,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AtomicReference<RuntimeSessionMode> runtimeSessionModeRef,
            ChatRun run
    ) {
    }

    private record RouteResolutionRequest(
            UserContext user,
            ChatSession session,
            ChatCommand runCommand,
            List<AttachmentRef> attachments,
            MemoryContext memory,
            String runId,
            String runtimeBindingLeafId,
            RouteTarget currentRoute,
            RuntimeBinding currentBinding,
            RuntimeSessionMode currentRuntimeSessionMode
    ) {
    }

    private record DomainAgentRunContext(
            ChatCommand command,
            String runId,
            ChatSession session,
            MemoryContext memory,
            RouteTarget route,
            UserContext user,
            AtomicReference<RuntimeBinding> bindingRef,
            RuntimeForwardHeaders forwardHeaders,
            IntentDecision intentDecision,
            List<UploadedDocument> documents,
            Set<String> rejectedDomainAgentIds,
            int rerouteCount
    ) {
    }

    private record DomainAgentRerouteContext(
            DomainAgentRunContext context,
            DomainAgentRefusal refusal,
            String currentDomainAgentId,
            Set<String> rejectedDomainAgentIds
    ) {
    }

    private record DomainAgentRefusal(String code, String message) {
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

    private static AgentRuntimeExecutor legacyCompatibleExecutor(DomainAgentExecutor domainAgentExecutor,
                                                                 AgentRuntimeExecutor delegate) {
        if (domainAgentExecutor == null) {
            return delegate;
        }
        return new LegacyDomainAgentAwareExecutor(delegate, domainAgentExecutor);
    }

    private static final class LegacyDomainAgentAwareExecutor extends AgentRuntimeExecutor {
        private final AgentRuntimeExecutor delegate;
        private final DomainAgentExecutor domainAgentExecutor;

        private LegacyDomainAgentAwareExecutor(AgentRuntimeExecutor delegate, DomainAgentExecutor domainAgentExecutor) {
            super((com.huawei.finance.front.one.application.integration.agent.AgentRuntime) null,
                    new com.huawei.finance.front.one.application.service.runtime.WorkloadConcurrencyLimiter(
                            new com.huawei.finance.front.one.application.config.ResourceIsolationProperties()));
            this.delegate = delegate;
            this.domainAgentExecutor = domainAgentExecutor;
        }

        @Override
        public Flux<ChatEvent> execute(RuntimeExecutionContext context) {
            if (context != null && context.route() != null && context.route().type() == RouteType.DOMAIN_AGENT) {
                return domainAgentExecutor.execute(new DomainAgentExecutionContext(
                        context.command(),
                        context.runId(),
                        context.route(),
                        context.user(),
                        context.binding(),
                        context.forwardHeaders()));
            }
            return delegate.execute(context);
        }

        @Override
        public Flux<ChatEvent> continueWithUserResponse(RuntimeHitlResponseContext context) {
            return delegate.continueWithUserResponse(context);
        }

        @Override
        public boolean supportsWaitingUserResponse(String runtimeProvider) {
            return delegate.supportsWaitingUserResponse(runtimeProvider);
        }

        @Override
        public Mono<Void> cancel(ChatRun run, UserContext user, RuntimeForwardHeaders forwardHeaders) {
            if (run != null && RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(run.runtimeProvider())) {
                return domainAgentExecutor.cancel(run, user, forwardHeaders);
            }
            return delegate.cancel(run, user, forwardHeaders);
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
     * 将消息树写入计划转换为真正下发给 Runtime/DomainAgent 的命令。
     *
     * <p>普通提问和编辑历史问题使用本轮新用户消息；重新生成回答时不创建新的 user 消息，
     * 因此要把原 user 消息内容作为本轮 query 传给下游，保证 Runtime 看到的输入和消息树一致。</p>
     */
    private ChatCommand commandForExecution(ChatCommand normalized, ChatRunMessagePlan messagePlan) {
        ChatMessage userMessage = messagePlan.userMessage();
        return new ChatCommand(normalized.commandId(), normalized.tenantId(), normalized.userId(),
                normalized.sessionId(), normalized.conversationId(), normalized.channel(), userMessage.content(),
                normalized.attachments(), normalized.metadata(), normalized.targetType(), normalized.targetId(),
                messagePlan.runMode(), messagePlan.parentMessageId(),
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

    private String explicitDomainAgentId(ChatCommand command) {
        String targetType = command == null ? null : command.targetType();
        if (targetType == null || targetType.isBlank()) {
            return null;
        }
        if (!"DOMAIN_AGENT".equalsIgnoreCase(targetType)) {
            throw new IllegalArgumentException("targetType 仅支持 DOMAIN_AGENT，当前值: " + targetType);
        }
        String domainAgentId = command.targetId();
        if (domainAgentId == null || domainAgentId.isBlank()) {
            throw new IllegalArgumentException("targetType=DOMAIN_AGENT 时 targetId 不能为空");
        }
        return domainAgentId.trim();
    }

    private RuntimeForwardHeaders normalizeForwardHeaders(RuntimeForwardHeaders forwardHeaders) {
        return forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }

    private Map<String, Object> runCompletedPayload(RouteTarget route, RuntimeBinding binding) {
        // run.completed 带出标准 status 和 v3 路由诊断字段，方便前端展示和排障。
        Map<String, Object> base = new java.util.LinkedHashMap<>();
        base.put("status", "COMPLETED");
        if (route != null && route.type() != null) {
            base.put("routeType", route.type().name());
        }
        if (route != null && route.routeSource() != null) {
            base.put("routeSource", route.routeSource());
        }
        if (route != null && route.selectedAgentCode() != null) {
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
