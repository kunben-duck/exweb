package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.facade.FinanceChatFacade;
import com.huawei.it.ex.one.application.facade.ResolvedChatAttachments;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeSessionUnavailable;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.service.memory.MemoryApplicationService;
import com.huawei.it.ex.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.it.ex.one.application.service.routing.IntentRecognitionRecordService;
import com.huawei.it.ex.one.application.service.routing.IntentRecognitionRecordSnapshot;
import com.huawei.it.ex.one.application.service.routing.IntentRoutingFailedException;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalFrame;
import com.huawei.it.ex.one.application.service.routing.RouteSignalProgress;
import com.huawei.it.ex.one.application.service.routing.RouteSignalRequest;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.DomainAgentBindingCommand;
import com.huawei.it.ex.one.application.service.runtime.DomainAgentExecutionContext;
import com.huawei.it.ex.one.application.service.runtime.DomainAgentExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingResolution;
import com.huawei.it.ex.one.application.service.runtime.RuntimeExecutionContext;
import com.huawei.it.ex.one.application.service.runtime.RuntimeInteractionResponseContext;
import com.huawei.it.ex.one.application.service.runtime.SystemResponseExecutor;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatInteractionUnavailableException;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStopResult;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ChatStreamTopics;
import com.huawei.it.ex.one.domain.chat.ErrorEvent;
import com.huawei.it.ex.one.domain.chat.MessageCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RunCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RunStartedEvent;
import com.huawei.it.ex.one.domain.chat.RunWaitingUserEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.memory.RouteMemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.routing.RouteType;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentControlEventMapper;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 聊天主编排服务：负责把一次前端请求串联成可追踪的 SuperAgent 运行。
 *
 * <p>这是 v3 架构的核心入口。这里不承载具体 DomainAgent、AgentRuntime、Redis、数据库
 * 或外部路由信号协议细节，只负责把稳定的业务顺序串起来：
 * 身份校验 -> 会话归一化 -> 上下文装配 -> Runtime 续接 -> 可选路由信号 -> Agent 调用。</p>
 */
@Service
public class FinanceEXChatService implements FinanceChatFacade {
    private static final AppLogger log = AppLoggerFactory.getLogger(FinanceEXChatService.class);
    private static final String INTERACTION_ASSISTANT_MESSAGE_ID_METADATA = "interactionAssistantMessageId";
    private static final String DOMAIN_AGENT_REROUTE_CONTEXT_METADATA = "domainAgentRerouteContext";
    private static final String INTENT_CLARIFICATION_DOCUMENT_IDS = "_intentClarificationDocumentIds";
    private static final Set<String> BATCHABLE_RUNTIME_EVENT_TYPES = Set.of(
            "message.delta",
            "message.snapshot",
            "runtime.progress",
            "runtime.metadata",
            "runtime.agent",
            "runtime.thinking",
            "runtime.tool",
            "runtime.reference",
            "runtime.card",
            "runtime.event"
    );

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
    private final ChatInteractionApplicationService chatInteractionService;
    private final ChatRunTerminalCommitService terminalCommitService;
    private final IdGenerator idGenerator;
    private final Scheduler eventIoScheduler;
    private Scheduler domainAgentControlIoScheduler;
    private final DomainAgentProperties domainAgentProperties;
    private final RouteMemoryApplicationService routeMemoryService;
    private final ChatRunOperationalProperties runOperationalProperties;
    private ChatRunAdmissionCommitService runAdmissionCommitService;
    private ChatEventBatcher chatEventBatcher;

    @Autowired
    void setRunAdmissionCommitService(ChatRunAdmissionCommitService runAdmissionCommitService) {
        this.runAdmissionCommitService = runAdmissionCommitService;
    }

    @Autowired
    void setDomainAgentControlIoScheduler(
            @Qualifier("domainAgentControlIoScheduler") Scheduler domainAgentControlIoScheduler) {
        if (domainAgentControlIoScheduler != null) {
            this.domainAgentControlIoScheduler = domainAgentControlIoScheduler;
        }
    }

    @Autowired
    void setChatEventBatcher(ChatEventBatcher chatEventBatcher) {
        this.chatEventBatcher = chatEventBatcher;
    }

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
                                ChatInteractionApplicationService chatInteractionService,
                                ChatRunTerminalCommitService terminalCommitService,
                                IdGenerator idGenerator,
                                @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler,
                                DomainAgentProperties domainAgentProperties,
                                RouteMemoryApplicationService routeMemoryService,
                                ChatRunOperationalProperties runOperationalProperties) {
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
        this.chatInteractionService = chatInteractionService;
        this.terminalCommitService = terminalCommitService;
        this.idGenerator = idGenerator;
        this.eventIoScheduler = eventIoScheduler == null ? Schedulers.boundedElastic() : eventIoScheduler;
        this.domainAgentControlIoScheduler = this.eventIoScheduler;
        this.domainAgentProperties = domainAgentProperties == null ? new DomainAgentProperties() : domainAgentProperties;
        this.routeMemoryService = routeMemoryService;
        this.runOperationalProperties = runOperationalProperties == null
                ? new ChatRunOperationalProperties()
                : runOperationalProperties;
    }

    public FinanceEXChatService(SessionApplicationService sessionService,
                                MemoryApplicationService memoryService,
                                RuntimeBindingApplicationService runtimeBindingService,
                                RouteSignalApplicationService routeSignalService,
                                IntentRecognitionRecordService intentRecognitionRecordService,
                                SystemResponseExecutor systemResponseExecutor,
                                AgentRuntimeExecutor agentRuntimeExecutor,
                                DocumentFacade documentFacade,
                                ChatStreamApplicationService chatStreamService,
                                ChatRunApplicationService chatRunService,
                                ChatRunLeaseApplicationService chatRunLeaseService,
                                ChatDeltaCoalescer chatDeltaCoalescer,
                                LocalChatRunExecutionRegistry runExecutionRegistry,
                                RunAdmissionControlService runAdmissionControl,
                                ChatRunStopCoordinator stopCoordinator,
                                ChatInteractionApplicationService chatInteractionService,
                                ChatRunTerminalCommitService terminalCommitService,
                                IdGenerator idGenerator,
                                Scheduler eventIoScheduler,
                                DomainAgentProperties domainAgentProperties,
                                RouteMemoryApplicationService routeMemoryService) {
        this(sessionService, memoryService, runtimeBindingService, routeSignalService,
                intentRecognitionRecordService, systemResponseExecutor, agentRuntimeExecutor, documentFacade,
                chatStreamService, chatRunService, chatRunLeaseService, chatDeltaCoalescer, runExecutionRegistry,
                runAdmissionControl, stopCoordinator, chatInteractionService, terminalCommitService, idGenerator,
                eventIoScheduler, domainAgentProperties, routeMemoryService, new ChatRunOperationalProperties());
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
                new DomainAgentProperties(), null, new ChatRunOperationalProperties());
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
                         ChatInteractionApplicationService chatInteractionService,
                         ChatRunTerminalCommitService terminalCommitService,
                         IdGenerator idGenerator,
                         Scheduler eventIoScheduler,
                         DomainAgentProperties domainAgentProperties) {
        this(sessionService, memoryService, runtimeBindingService, routeSignalService, intentRecognitionRecordService,
                systemResponseExecutor, legacyCompatibleExecutor(domainAgentExecutor, agentRuntimeExecutor),
                documentFacade, chatStreamService, chatRunService,
                chatRunLeaseService, chatDeltaCoalescer, runExecutionRegistry, runAdmissionControl, stopCoordinator,
                chatInteractionService, terminalCommitService, idGenerator, eventIoScheduler, domainAgentProperties, null,
                new ChatRunOperationalProperties());
    }

    @Override
    public Mono<ChatRunStartResult> startRun(UserContext user, ChatCommand command, RuntimeForwardHeaders forwardHeaders) {
        return startRun(user, TraceContext.empty(), command, forwardHeaders);
    }

    @Override
    public Mono<ChatRunStartResult> startRun(UserContext user, TraceContext traceContext, ChatCommand command,
                                             RuntimeForwardHeaders forwardHeaders) {
        TraceContext traceSnapshot = normalizeTraceContext(traceContext);
        if (command != null && command.runMode() == ChatRunMode.CONTINUE_INTERACTION) {
            return Mono.defer(() -> startInteractionContinuation(
                    user, traceSnapshot, interactionResponseCommand(user, command), forwardHeaders));
        }
        return Mono.defer(() -> {
            validateStandardRunCommand(command);
            RuntimeForwardHeaders headerSnapshot = normalizeForwardHeaders(forwardHeaders);
            String runId = idGenerator.newId("run",
                    IdGenerateContext.of(user.tenantId(), user.ownerUserId(), command.sessionId()));
            RunStartAttempt startAttempt = new RunStartAttempt(user, runId, null);
            RunPermitGuard runPermit = new RunPermitGuard(runAdmissionControl.acquire(user));
            Sinks.One<ChatEvent> firstEvent = Sinks.one();
            AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            AtomicReference<String> runIdRef = new AtomicReference<>();
            AtomicBoolean terminal = new AtomicBoolean(false);
            Flux<ChatEvent> runFlux = executeRun(user, traceSnapshot, command, headerSnapshot, startAttempt)
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(event -> {
                        if (!startAttempt.beginFirstEventHandoff()) {
                            return;
                        }
                        if (runIdRef.compareAndSet(null, event.runId())) {
                            Disposable disposable = disposableRef.get();
                            if (disposable != null && !terminal.get()) {
                                runExecutionRegistry.register(event.runId(), disposable);
                            }
                        }
                        Sinks.EmitResult emitted = firstEvent.tryEmitValue(event);
                        if (emitted.isFailure() && startAttempt.abortFailedHandoff()) {
                            abortStartAttempt(disposableRef, runPermit, startAttempt, "chat run");
                        }
                    })
                    .doOnComplete(() -> {
                        if (runIdRef.get() == null) {
                            firstEvent.tryEmitError(new IllegalStateException("chat run finished before emitting any persisted event"));
                        }
                    })
                    .doFinally(signalType -> {
                        terminal.set(true);
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
                                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                                                    "Background chat run terminated after first-event handoff")
                                            .traceId(traceSnapshot.traceId())
                                            .runId(runIdRef.get())
                                            .operation("chat-run.background")
                                            .build(), error);
                                }
                            }
                    );
            disposableRef.set(disposable);
            if (runIdRef.get() != null && !terminal.get()) {
                runExecutionRegistry.register(runIdRef.get(), disposable);
            }
            return awaitFirstEvent(firstEvent, disposableRef, runPermit, startAttempt, "chat run")
                    .map(event -> new ChatRunStartResult(event.runId(), event.sessionId(), event.sequence(),
                            event.createdAt(), ChatStreamTopics.runTopic(event.runId())));
        });
    }

    @Override
    public Mono<ChatRunStopResult> stopRun(UserContext user, String runId, RuntimeForwardHeaders forwardHeaders) {
        return stopRun(user, TraceContext.empty(), runId, forwardHeaders);
    }

    @Override
    public Mono<ChatRunStopResult> stopRun(UserContext user, TraceContext traceContext, String runId,
                                           RuntimeForwardHeaders forwardHeaders) {
        return stopCoordinator.stopRun(user, normalizeTraceContext(traceContext), runId, "USER_STOP", forwardHeaders);
    }

    private Mono<ChatRunStartResult> startInteractionContinuation(UserContext user, TraceContext traceContext,
                                                                  ChatInteractionResponseCommand command,
                                                                  RuntimeForwardHeaders forwardHeaders) {
        return Mono.defer(() -> {
            RuntimeForwardHeaders headerSnapshot = normalizeForwardHeaders(forwardHeaders);
            RunPermitGuard runPermit = new RunPermitGuard(runAdmissionControl.acquire(user));
            Sinks.One<ChatEvent> firstEvent = Sinks.one();
            AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            AtomicReference<String> runIdRef = new AtomicReference<>();
            AtomicBoolean terminal = new AtomicBoolean(false);
            String runId = idGenerator.newId("run",
                    IdGenerateContext.of(user.tenantId(), user.ownerUserId(), command.interactionId()));
            RunStartAttempt startAttempt = new RunStartAttempt(user, runId, command.interactionId());
            AtomicReference<IntentClarificationContinuationInput> clarificationInputRef = new AtomicReference<>();
            Flux<ChatEvent> runFlux = Flux.defer(() -> {
                        ChatInteractionClaimResult claim = chatInteractionService.claimPreparedInteractionResponse(
                                command, runId, interaction -> prepareInteractionResponse(
                                        user, command, interaction, clarificationInputRef));
                        startAttempt.recordInteraction(claim.request());
                        if (startAttempt.aborted()) {
                            chatInteractionService.markWaiting(claim.request());
                            return Flux.empty();
                        }
                        try {
                            return executeInteractionContinuation(
                                    user, claim, runId,
                                    new InteractionContinuationOptions(
                                            headerSnapshot, traceContext, startAttempt, null, command.agentMode()),
                                    clarificationInputRef.get());
                        } catch (RuntimeException ex) {
                            chatInteractionService.markWaiting(claim.request());
                            return Flux.error(ex);
                        }
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(event -> {
                        if (!startAttempt.beginFirstEventHandoff()) {
                            return;
                        }
                        if (runIdRef.compareAndSet(null, event.runId())) {
                            Disposable disposable = disposableRef.get();
                            if (disposable != null && !terminal.get()) {
                                runExecutionRegistry.register(event.runId(), disposable);
                            }
                        }
                        Sinks.EmitResult emitted = firstEvent.tryEmitValue(event);
                        if (emitted.isFailure() && startAttempt.abortFailedHandoff()) {
                            abortStartAttempt(disposableRef, runPermit, startAttempt,
                                    "interaction continuation");
                        }
                    })
                    .doOnComplete(() -> {
                        if (runIdRef.get() == null) {
                            firstEvent.tryEmitError(new IllegalStateException(
                                    "interaction continuation finished before emitting any event"));
                        }
                    })
                    .doFinally(signalType -> {
                        terminal.set(true);
                        runPermit.close();
                    });
            Disposable disposable = runFlux.subscribe(event -> {
            }, error -> {
                Sinks.EmitResult result = firstEvent.tryEmitError(error);
                if (result.isFailure()) {
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                                    "Background Interaction continuation terminated after first-event handoff")
                            .traceId(traceContext.traceId())
                            .runId(runId)
                            .operation("interaction.background")
                            .attribute("interactionId", command.interactionId())
                            .build(), error);
                }
            });
            disposableRef.set(disposable);
            if (runIdRef.get() != null && !terminal.get()) {
                runExecutionRegistry.register(runIdRef.get(), disposable);
            }
            return awaitFirstEvent(firstEvent, disposableRef, runPermit, startAttempt, "interaction continuation")
                    .map(event -> new ChatRunStartResult(
                            event.runId(),
                            event.sessionId(),
                            event.sequence(),
                            event.createdAt(),
                            ChatStreamTopics.runTopic(event.runId())));
        });
    }

    private Mono<ChatEvent> awaitFirstEvent(Sinks.One<ChatEvent> firstEvent,
                                            AtomicReference<Disposable> disposableRef,
                                            RunPermitGuard runPermit,
                                            RunStartAttempt startAttempt,
                                            String operation) {
        return withFirstEventTimeout(
                firstEvent.asMono(),
                runOperationalProperties.normalizedFirstEventTimeout(),
                () -> abortBeforeFirstEvent(disposableRef, runPermit, startAttempt, operation));
    }

    static <T> Mono<T> withFirstEventTimeout(Mono<T> source, Duration timeout, Runnable abort) {
        AtomicBoolean aborted = new AtomicBoolean(false);
        Runnable abortOnce = () -> {
            if (aborted.compareAndSet(false, true)) {
                abort.run();
            }
        };
        Mono<T> handoff = source.doOnCancel(abortOnce);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return handoff;
        }
        return handoff.timeout(timeout, Mono.error(new IllegalStateException(
                "RUN_FIRST_EVENT_TIMEOUT: 等待首个持久化事件超时: " + timeout)));
    }

    private void abortBeforeFirstEvent(AtomicReference<Disposable> disposableRef,
                                       RunPermitGuard runPermit,
                                       RunStartAttempt startAttempt,
                                       String operation) {
        if (startAttempt == null || !startAttempt.abort()) {
            return;
        }
        abortStartAttempt(disposableRef, runPermit, startAttempt, operation);
    }

    private void abortStartAttempt(AtomicReference<Disposable> disposableRef,
                                   RunPermitGuard runPermit,
                                   RunStartAttempt startAttempt,
                                   String operation) {
        boolean firstAbort = runPermit.closeOnce();
        Disposable disposable = disposableRef.get();
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
        RunExecutionClaim executionClaim = startAttempt.executionClaim();
        if (executionClaim != null) {
            runExecutionRegistry.complete(executionClaim);
        }
        scheduleFirstEventTimeoutCompensation(startAttempt);
        if (firstAbort) {
            log.warn("Abort {} before first-event handoff. runId={}", operation, startAttempt.runId());
        }
    }

    private void scheduleFirstEventTimeoutCompensation(RunStartAttempt startAttempt) {
        if (startAttempt == null || !startAttempt.aborted()) {
            return;
        }
        if (!startAttempt.beginCompensation()) {
            startAttempt.requestCompensationRetry();
            return;
        }
        Mono.defer(() -> Mono.fromCallable(() -> compensateFirstEventTimeout(startAttempt))
                        .subscribeOn(eventIoScheduler))
                .flatMap(outcome -> outcome == FirstEventCompensationOutcome.RETRY
                        ? Mono.error(new FirstEventCompensationPendingException(startAttempt.runId()))
                        : Mono.<Void>empty())
                .retryWhen(Retry.backoff(2, Duration.ofMillis(250))
                        .maxBackoff(Duration.ofSeconds(1)))
                .doFinally(ignored -> {
                    startAttempt.finishCompensation();
                    if (startAttempt.consumeCompensationRetry()) {
                        scheduleFirstEventTimeoutCompensation(startAttempt);
                    }
                })
                .subscribe(
                        ignored -> {
                        },
                        error -> log.error(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_TRANSACTION_FAILED,
                                        "First-event timeout compensation did not converge")
                                .runId(startAttempt.runId())
                                .operation("chat-run.first-event-timeout-compensation")
                                .attribute("interactionId", startAttempt.interactionId())
                                .build(), error)
                );
    }

    private FirstEventCompensationOutcome compensateFirstEventTimeout(RunStartAttempt startAttempt) {
        log.debug("Run first-event timeout compensation attempt. runId={}, interactionId={}, hasRun={}, hasExecution={}",
                startAttempt.runId(), startAttempt.interactionId(), startAttempt.run() != null,
                startAttempt.executionClaim() != null);
        if (!startAttempt.aborted()) {
            return FirstEventCompensationOutcome.DONE;
        }
        ChatRun run = startAttempt.run();
        if (run == null) {
            if (startAttempt.interactionId() == null) {
                return FirstEventCompensationOutcome.DONE;
            }
            int released = chatInteractionService.markWaitingForRun(
                    startAttempt.user().tenantId(), startAttempt.user().ownerUserId(),
                    startAttempt.interactionId(), startAttempt.runId());
            if (released > 0) {
                return FirstEventCompensationOutcome.DONE;
            }
            return FirstEventCompensationOutcome.RETRY;
        }
        RunExecutionClaim executionClaim = startAttempt.executionClaim();
        if (executionClaim == null && !startAttempt.executionInitializationSkipped()) {
            return FirstEventCompensationOutcome.RETRY;
        }
        if (terminalCommitService == null) {
            ChatInteractionRequest interaction = startAttempt.interactionRequest();
            if (interaction != null) {
                chatInteractionService.markWaitingForRun(
                        interaction.tenantId(), interaction.userId(), interaction.id(), startAttempt.runId());
            }
            log.error(SystemErrorLogEntry.builder(SystemErrorCode.CONFIGURATION_INVALID,
                            "Terminal commit service is unavailable for first-event timeout compensation")
                    .runId(startAttempt.runId())
                    .operation("chat-run.first-event-timeout-compensation")
                    .build());
            return FirstEventCompensationOutcome.DONE;
        }
        String message = "等待首个持久化事件超时，本轮执行已终止";
        ChatEvent failed = ErrorEvent.of(
                run.id(),
                run.sessionId(),
                "RUN_FIRST_EVENT_TIMEOUT",
                message,
                Map.of(
                        "code", "RUN_FIRST_EVENT_TIMEOUT",
                        "message", message,
                        "source", "chat-run-start"
                ));
        ChatRunTerminalCommitService.ExternalTerminalCommitResult result =
                terminalCommitService.commitExternalTerminal(
                        ChatRunTerminalCommitService.ExternalTerminalCommitCommand.firstEventTimeout(
                                failed, run, startAttempt.interactionId(), executionClaim));
        if (!result.committed()) {
            if (result.run() != null && (result.run().status().terminal()
                    || result.run().status() == ChatRunStatus.CANCELLING)) {
                chatRunService.synchronizeCommittedRunCache(result.run());
                completeStartAttemptExecution(startAttempt);
                return FirstEventCompensationOutcome.DONE;
            }
            return FirstEventCompensationOutcome.RETRY;
        }
        chatRunService.synchronizeCommittedRunCache(result.run());
        try {
            chatStreamService.publishPersisted(result.event());
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.WEBSOCKET_SEND_FAILED,
                            "First-event timeout terminal committed but realtime publish failed")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.first-event-timeout-publish")
                    .build());
        }
        completeStartAttemptExecution(startAttempt);
        return FirstEventCompensationOutcome.DONE;
    }

    private void trackStartAttemptRun(RunStartAttempt startAttempt, ChatRun run, String stage) {
        if (startAttempt == null) {
            return;
        }
        startAttempt.recordRun(run);
        if (startAttempt.aborted()) {
            startAttempt.markExecutionInitializationSkipped();
            scheduleFirstEventTimeoutCompensation(startAttempt);
            throw startAttemptRejected(startAttempt, stage);
        }
    }

    private void trackStartAttemptExecution(RunStartAttempt startAttempt, RunExecutionClaim executionClaim,
                                            String stage) {
        if (startAttempt != null) {
            startAttempt.recordExecutionClaim(executionClaim);
        }
        runExecutionRegistry.registerClaim(executionClaim);
        if (startAttempt != null && startAttempt.aborted()) {
            runExecutionRegistry.complete(executionClaim);
            scheduleFirstEventTimeoutCompensation(startAttempt);
            throw startAttemptRejected(startAttempt, stage);
        }
    }

    private void ensureStartAttemptActive(RunStartAttempt startAttempt, String stage) {
        if (startAttempt != null && startAttempt.aborted()) {
            if (startAttempt.run() != null && startAttempt.executionClaim() == null) {
                startAttempt.markExecutionInitializationSkipped();
                scheduleFirstEventTimeoutCompensation(startAttempt);
            }
            throw startAttemptRejected(startAttempt, stage);
        }
    }

    private ChatEventAppendRejectedException startAttemptRejected(RunStartAttempt startAttempt, String stage) {
        return new ChatEventAppendRejectedException(
                "run start attempt 已在首事件交接前终止: runId=" + startAttempt.runId() + ", stage=" + stage);
    }

    private void completeStartAttemptExecution(RunStartAttempt startAttempt) {
        RunExecutionClaim executionClaim = startAttempt.executionClaim();
        if (executionClaim == null) {
            runExecutionRegistry.complete(startAttempt.runId());
        } else {
            runExecutionRegistry.complete(executionClaim);
        }
    }

    private ChatInteractionResponseCommand interactionResponseCommand(UserContext user, ChatCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("创建 run 请求体不能为空");
        }
        if (command.interactionId() == null) {
            throw new IllegalArgumentException("CONTINUE_INTERACTION 模式 interactionId 不能为空");
        }
        if (hasText(command.message())) {
            throw new IllegalArgumentException("CONTINUE_INTERACTION 模式不支持 message，请使用 questionnaireAnswers/approved/scope");
        }
        if (command.targetType() != null || command.targetId() != null
                || command.parentMessageId() != null || command.editedMessageId() != null
                || command.regeneratedMessageId() != null || command.routeTrigger() != null) {
            throw new IllegalArgumentException("CONTINUE_INTERACTION 模式不支持普通 run 路由或消息树字段");
        }
        return new ChatInteractionResponseCommand(user, command.interactionId(), command.approved(), command.scope(),
                command.questionnaireAnswers(), command.metadata(), command.sessionId(), command.appId(), command.appName(),
                command.attachments(), command.agentMode());
    }

    private Map<String, Object> prepareInteractionResponse(
            UserContext user,
            ChatInteractionResponseCommand command,
            ChatInteractionRequest interaction,
            AtomicReference<IntentClarificationContinuationInput> clarificationInputRef) {
        validateInteractionSessionContext(user, command, interaction);
        if (interaction.interactionType() != ChatInteractionType.INTENT_CLARIFICATION) {
            if (!command.attachments().isEmpty()) {
                throw new IllegalArgumentException("仅 INTENT_CLARIFICATION 支持在续接时提交附件");
            }
            return chatInteractionService.prepareResponsePayload(command, interaction.interactionType(), null);
        }
        IntentClarificationContinuationInput input = prepareIntentClarificationInput(user, command, interaction);
        clarificationInputRef.set(input);
        Map<String, Object> payload = new LinkedHashMap<>(chatInteractionService.prepareResponsePayload(
                command, interaction.interactionType(), input.intentQuery()));
        payload.put("answerText", input.messageText());
        return Map.copyOf(payload);
    }

    private IntentClarificationContinuationInput prepareIntentClarificationInput(
            UserContext user,
            ChatInteractionResponseCommand command,
            ChatInteractionRequest interaction) {
        List<String> previousDocumentIds = intentClarificationDocumentIds(interaction.requestPayload());
        LinkedHashMap<String, AttachmentRef> currentRequests = new LinkedHashMap<>();
        for (AttachmentRef attachment : command.attachments()) {
            if (attachment == null || attachment.documentId() == null || attachment.documentId().isBlank()) {
                throw new IllegalArgumentException("文档 ID 不能为空");
            }
            String documentId = attachment.documentId().trim();
            currentRequests.putIfAbsent(documentId, attachment);
        }

        // 当前轮附件由用户本次提交，可以修正后重试；因此先严格解析，失败时保持 Interaction WAITING。
        ResolvedChatAttachments currentResolved = currentRequests.isEmpty()
                ? ResolvedChatAttachments.empty()
                : documentFacade.resolveChatAttachmentsForUser(user, List.copyOf(currentRequests.values()));

        List<AttachmentRef> historicalRequests = previousDocumentIds.stream()
                .filter(documentId -> !currentRequests.containsKey(documentId))
                .map(documentId -> new AttachmentRef(documentId, null, null, null))
                .toList();
        ResolvedChatAttachments historicalResolved;
        try {
            historicalResolved = historicalRequests.isEmpty()
                    ? ResolvedChatAttachments.empty()
                    : documentFacade.resolveChatAttachmentsForUser(user, historicalRequests);
        } catch (SecurityException | IllegalStateException ex) {
            boolean cancelled = chatInteractionService.cancelWaitingForUnavailableAttachment(interaction);
            if (!cancelled) {
                throw ChatInteractionUnavailableException.alreadyHandled(interaction.id());
            }
            throw ChatInteractionUnavailableException.attachmentUnavailable(interaction.id());
        }

        Map<String, AttachmentRef> currentAttachmentsById = attachmentsByDocumentId(currentResolved.attachments());
        Map<String, UploadedDocument> currentDocumentsById = documentsById(currentResolved.documents());
        Map<String, AttachmentRef> historicalAttachmentsById = attachmentsByDocumentId(historicalResolved.attachments());
        Map<String, UploadedDocument> historicalDocumentsById = documentsById(historicalResolved.documents());

        LinkedHashMap<String, Boolean> cumulativeOrder = new LinkedHashMap<>();
        previousDocumentIds.forEach(documentId -> cumulativeOrder.putIfAbsent(documentId, Boolean.TRUE));
        currentRequests.keySet().forEach(documentId -> cumulativeOrder.putIfAbsent(documentId, Boolean.TRUE));
        List<AttachmentRef> cumulativeAttachments = new ArrayList<>();
        List<UploadedDocument> cumulativeDocuments = new ArrayList<>();
        for (String documentId : cumulativeOrder.keySet()) {
            AttachmentRef attachment = currentAttachmentsById.containsKey(documentId)
                    ? currentAttachmentsById.get(documentId)
                    : historicalAttachmentsById.get(documentId);
            UploadedDocument document = currentDocumentsById.containsKey(documentId)
                    ? currentDocumentsById.get(documentId)
                    : historicalDocumentsById.get(documentId);
            if (attachment == null || document == null) {
                throw new IllegalStateException("澄清附件解析结果不完整: " + documentId);
            }
            cumulativeAttachments.add(attachment);
            cumulativeDocuments.add(document);
        }

        List<AttachmentRef> currentAttachments = currentRequests.keySet().stream()
                .map(currentAttachmentsById::get)
                .toList();
        String textAnswer = chatInteractionService.optionalIntentClarificationAnswer(
                command.questionnaireAnswers());
        String messageText = textAnswer == null ? "" : textAnswer;
        String intentQuery = clarificationAnswerWithAttachments(textAnswer, currentAttachments);
        return new IntentClarificationContinuationInput(
                messageText,
                intentQuery,
                currentAttachments,
                cumulativeAttachments,
                cumulativeDocuments,
                List.copyOf(cumulativeOrder.keySet()),
                command.metadata(),
                command.agentMode());
    }

    private Map<String, AttachmentRef> attachmentsByDocumentId(List<AttachmentRef> attachments) {
        Map<String, AttachmentRef> byId = new LinkedHashMap<>();
        if (attachments != null) {
            attachments.stream()
                    .filter(java.util.Objects::nonNull)
                    .forEach(attachment -> byId.putIfAbsent(attachment.documentId(), attachment));
        }
        return byId;
    }

    private Map<String, UploadedDocument> documentsById(List<UploadedDocument> documents) {
        Map<String, UploadedDocument> byId = new LinkedHashMap<>();
        if (documents != null) {
            documents.stream()
                    .filter(java.util.Objects::nonNull)
                    .forEach(document -> byId.putIfAbsent(document.id(), document));
        }
        return byId;
    }

    static String clarificationAnswerWithAttachments(String answerText, List<AttachmentRef> attachments) {
        String normalizedAnswer = answerText == null || answerText.isBlank() ? null : answerText.trim();
        String fileText = uploadedDocumentText(attachments);
        if (normalizedAnswer == null) {
            return fileText;
        }
        return fileText == null ? normalizedAnswer : normalizedAnswer + " " + fileText;
    }

    static String nextMessageWithAttachments(ChatRunMode runMode, String message,
                                             List<AttachmentRef> attachments) {
        if (runMode != ChatRunMode.NEXT || (message != null && !message.isBlank())) {
            return message;
        }
        return attachments == null || attachments.isEmpty() ? message : "";
    }

    private static String uploadedDocumentText(List<AttachmentRef> attachments) {
        return attachments == null || attachments.isEmpty()
                ? null
                : "[用户上传文档] " + attachments.stream()
                .map(AttachmentRef::name)
                .collect(java.util.stream.Collectors.joining("，"));
    }

    private void validateInteractionSessionContext(UserContext user, ChatInteractionResponseCommand command,
                                                   ChatInteractionRequest interaction) {
        if (hasText(command.sessionId()) && !command.sessionId().equals(interaction.sessionId())) {
            throw new IllegalArgumentException("sessionId 与 Interaction 所属会话不一致");
        }
        if (command.appId() == null && command.appName() == null) {
            return;
        }
        if (!hasText(command.sessionId())) {
            throw new IllegalArgumentException("CONTINUE_INTERACTION 携带 App Tag 时 sessionId 不能为空");
        }
        sessionService.validateAppTag(user, interaction.sessionId(), command.appId(), command.appName());
    }

    private void validateStandardRunCommand(ChatCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("创建 run 请求体不能为空");
        }
        if (command.interactionId() != null || command.approved() != null || command.scope() != null
                || !command.questionnaireAnswers().isEmpty()) {
            throw new IllegalArgumentException("Interaction 续接字段仅支持 runMode=CONTINUE_INTERACTION");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Map<String, Object> interactionRunMetadata(ChatInteractionRequest interaction) {
        String assistantMessageId = interaction == null ? null : firstText(interaction.assistantMessageId());
        if (interaction == null || !hasText(assistantMessageId)) {
            throw new IllegalStateException("Interaction continuation 缺少 assistantMessageId");
        }
        return Map.of(
                "interactionId", interaction.id(),
                "interactionType", interaction.interactionType().name(),
                InteractionMessageStrategy.METADATA_KEY,
                InteractionMessageStrategy.forInteraction(interaction).name(),
                INTERACTION_ASSISTANT_MESSAGE_ID_METADATA, assistantMessageId
        );
    }

    private Flux<ChatEvent> executeInteractionContinuation(UserContext user, ChatInteractionClaimResult claim, String runId,
                                                    InteractionContinuationOptions options,
                                                    IntentClarificationContinuationInput clarificationInput) {
        ChatInteractionRequest interaction = claim.request();
        ChatSession session = sessionService.getSession(user, interaction.sessionId());
        RuntimeForwardHeaders forwardHeaders = options.forwardHeaders();
        RunStartAttempt startAttempt = options.startAttempt();
        if (startAttempt != null && startAttempt.aborted()) {
            chatInteractionService.markWaiting(interaction);
            return Flux.empty();
        }
        InteractionContinuationOptions executionOptions = options.withSession(session);
        if (interaction.interactionType() == ChatInteractionType.INTENT_CLARIFICATION) {
            if (clarificationInput == null) {
                throw new IllegalStateException("意图澄清 continuation 缺少可信附件解析结果");
            }
            return executeIntentClarificationContinuation(user, claim, runId, executionOptions, clarificationInput);
        }
        if (interaction.interactionType() == ChatInteractionType.ROUTE_SWITCH_CONFIRMATION) {
            return executeRouteSwitchContinuation(user, claim, runId, session, executionOptions);
        }
        RouteTarget route = RouteTarget.agentRuntime("interaction-continuation", 1.0,
                "continue waiting user input");
        RuntimeEvent responseEvent = clarificationResponseEvent(runId, session.id(), interaction, claim.responsePayload());
        ChatMessage userMessage = new ChatMessage(interaction.userMessageId(), user.tenantId(), user.ownerUserId(),
                session.id(), "user", "", null, Instant.now());
        ChatRunMessagePlan messagePlan = new ChatRunMessagePlan(ChatRunMode.NEXT,
                interaction.userMessageId(), userMessage, null);
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>();
        AssistantAssembly assistant = new AssistantAssembly();
        ChatRun run = chatRunService.createInteractionRunning(new CreateChatRunContext(
                runId,
                user,
                session.id(),
                route,
                null,
                interactionRunMetadata(interaction),
                ChatRunMode.NEXT,
                interaction.userMessageId(),
                interaction.userMessageId()
        ), interaction.id());
        trackStartAttemptRun(startAttempt, run, "after-interaction-run-create");
        RunExecutionClaim executionClaim;
        try {
            executionClaim = chatRunLeaseService.startInteractionRun(run, interaction.id());
        } catch (RuntimeException ex) {
            return failExecutionInitialization(run, interaction, ex);
        }
        trackStartAttemptExecution(startAttempt, executionClaim, "after-interaction-execution-create");
        RunEventPipelineContext context = new RunEventPipelineContext(user, session, messagePlan,
                new AtomicReference<>(route), bindingRef, assistant, runId, executionClaim, new AtomicReference<>(),
                interaction, startAttempt, List.of());
        try {
            return executeAfterRunStarted(context, () -> {
                RuntimeBinding binding = runtimeBindingService.resumeForInteraction(
                        interaction, runId, options.agentMode());
                bindingRef.set(binding);
                bestEffortBindResolvedRoute(runId, route, binding);
                return Flux.concat(
                        Flux.just(responseEvent),
                        requireCurrentOwnerRunning(executionClaim, "before-runtime-interaction")
                                .thenMany(Flux.defer(() -> agentRuntimeExecutor.continueWithUserResponse(
                                        new RuntimeInteractionResponseContext(
                                                user,
                                                session.id(),
                                                runId,
                                                binding.provider(),
                                                binding.runtimeSessionId(),
                                                interaction.id(),
                                                interaction.interactionType().name(),
                                                interaction.approvalId(),
                                                claim.responsePayload(),
                                                forwardHeaders,
                                                options.traceContext()
                                        ))))
                );
            });
        } catch (RuntimeException ex) {
            return failInteractionContinuationRun(context, ex);
        }
    }

    private Flux<ChatEvent> executeIntentClarificationContinuation(UserContext user, ChatInteractionClaimResult claim,
                                                                   String runId, InteractionContinuationOptions options,
                                                                   IntentClarificationContinuationInput input) {
        RuntimeForwardHeaders forwardHeaders = options.forwardHeaders();
        RunStartAttempt startAttempt = options.startAttempt();
        ChatSession session = options.session();
        ChatInteractionRequest interaction = claim.request();
        ChatCommand command = commandWithIntentClarificationContext(
                user, session, interaction, claim.responsePayload(), input);
        RuntimeEvent responseEvent = clarificationResponseEvent(runId, session.id(), interaction, claim.responsePayload());

        MemoryContext memory = MemoryContext.empty();
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>();
        AtomicReference<RouteTarget> routeRef = new AtomicReference<>();
        AtomicReference<RuntimeSessionMode> runtimeSessionModeRef = new AtomicReference<>(RuntimeSessionMode.RESUME);

        ChatRunAdmissionCommitService.AdmissionResult admission = runAdmissionCommitService == null
                ? legacyIntentClarificationAdmission(
                        user, session, runId, interaction, input)
                : runAdmissionCommitService.commitIntentClarification(
                        new ChatRunAdmissionCommitService.IntentClarificationAdmissionCommand(
                                user, session, runId, interaction, input.messageText(),
                                input.currentAttachments(),
                                interactionRunMetadata(interaction)));
        ChatRunMessagePlan messagePlan = admission.messagePlan();
        ChatRun run = admission.run();
        trackStartAttemptRun(startAttempt, run, "after-intent-interaction-run-create");
        chatRunService.synchronizeCommittedRunCache(run);
        RunExecutionClaim executionClaim;
        try {
            executionClaim = chatRunLeaseService.startInteractionRun(run, interaction.id());
        } catch (RuntimeException ex) {
            return failExecutionInitialization(run, interaction, ex);
        }
        trackStartAttemptExecution(startAttempt, executionClaim, "after-intent-interaction-execution-create");

        RunEventPipelineContext context = new RunEventPipelineContext(user, session, messagePlan, routeRef, bindingRef,
                new AssistantAssembly(), runId, executionClaim, new AtomicReference<>(), interaction, startAttempt,
                input.cumulativeDocumentIds());
        String foldedRouteQuery = routeMemoryQuery(messagePlan, interaction, input.intentQuery());
        try {
            return executeAfterRunStarted(context, () -> Flux.concat(
                    Flux.just(responseEvent),
                    routeAndExecute(new RoutePipelineRequest(
                            user, session, command, input.cumulativeAttachments(), input.cumulativeDocuments(), memory, runId,
                            messagePlan.parentMessageId(), forwardHeaders, options.traceContext(), routeRef, bindingRef,
                            runtimeSessionModeRef, executionClaim, run,
                            foldedRouteQuery, input.intentQuery(), foldedRouteQuery,
                            input.runtimeMetadata(), input.agentMode()))));
        } catch (RuntimeException ex) {
            return failInteractionContinuationRun(context, ex);
        }
    }

    private ChatRunAdmissionCommitService.AdmissionResult legacyIntentClarificationAdmission(
            UserContext user, ChatSession session, String runId, ChatInteractionRequest interaction,
            IntentClarificationContinuationInput input) {
        ChatRunMessagePlan messagePlan = sessionService.prepareIntentClarificationAnswer(
                user, session, runId, interaction.assistantMessageId(), input.messageText(),
                input.currentAttachments());
        ChatRun run = chatRunService.createInteractionRunning(new CreateChatRunContext(
                runId,
                user,
                session.id(),
                null,
                null,
                interactionRunMetadata(interaction),
                ChatRunMode.NEXT,
                messagePlan.parentMessageId(),
                messagePlan.userMessage().id()
        ), interaction.id());
        if (chatInteractionService.markAnsweredForRun(interaction, runId) != 1) {
            throw new IllegalStateException("意图澄清 Interaction 已不再由当前 continuation run 持有");
        }
        return new ChatRunAdmissionCommitService.AdmissionResult(messagePlan, run);
    }

    private Flux<ChatEvent> executeRouteSwitchContinuation(UserContext user, ChatInteractionClaimResult claim,
                                                           String runId, ChatSession session,
                                                           InteractionContinuationOptions options) {
        RuntimeForwardHeaders forwardHeaders = options.forwardHeaders();
        RunStartAttempt startAttempt = options.startAttempt();
        ChatInteractionRequest interaction = claim.request();
        boolean approved = Boolean.TRUE.equals(claim.responsePayload().get("approved"));
        String candidateProvider = blankToDefault(
                firstText(interaction.requestPayload().get("candidateProvider")),
                RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER);
        String candidateTargetId = firstText(interaction.requestPayload().get("candidateTargetId"));
        String currentProvider = blankToDefault(
                firstText(interaction.requestPayload().get("currentProvider")),
                RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER);
        String currentTargetId = firstText(interaction.requestPayload().get("currentTargetId"));
        if (!RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(currentProvider)
                || currentTargetId == null || currentTargetId.isBlank()) {
            throw new IllegalStateException("路由切换 Interaction 缺少当前 DomainAgent 上下文");
        }
        String originalQuery = firstText(interaction.requestPayload().get("originalQuery"));
        String candidateRouteQuery = blankToDefault(
                firstText(interaction.requestPayload().get("routeMemoryQuery")),
                originalQuery == null ? "" : originalQuery);
        RuntimeEvent responseEvent = routeSwitchResponseEvent(runId, session.id(), interaction,
                claim.responsePayload());
        RouteTarget route = approved
                ? routeSwitchTarget(candidateProvider, candidateTargetId, "user-confirmed")
                : RouteTarget.domainAgent(currentTargetId, routeSourceFromInteraction(interaction), 1.0,
                "declined route switch");
        ChatMessage userMessage = new ChatMessage(interaction.userMessageId(), user.tenantId(), user.ownerUserId(),
                session.id(), "user", originalQuery == null ? "" : originalQuery, null, Instant.now());
        ChatRunMessagePlan messagePlan = new ChatRunMessagePlan(ChatRunMode.NEXT,
                interaction.userMessageId(), userMessage, null);
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>();
        AssistantAssembly assistant = new AssistantAssembly();
        ChatRun run = chatRunService.createInteractionRunning(new CreateChatRunContext(
                runId,
                user,
                session.id(),
                route,
                null,
                interactionRunMetadata(interaction),
                ChatRunMode.NEXT,
                interaction.userMessageId(),
                interaction.userMessageId()
        ), interaction.id());
        trackStartAttemptRun(startAttempt, run, "after-domain-switch-run-create");
        RunExecutionClaim executionClaim;
        try {
            executionClaim = chatRunLeaseService.startInteractionRun(run, interaction.id());
        } catch (RuntimeException ex) {
            return failExecutionInitialization(run, interaction, ex);
        }
        trackStartAttemptExecution(startAttempt, executionClaim, "after-domain-switch-execution-create");
        AtomicReference<RouteTarget> routeRef = new AtomicReference<>(route);
        RunEventPipelineContext context = new RunEventPipelineContext(user, session, messagePlan,
                routeRef, bindingRef, assistant, runId, executionClaim, new AtomicReference<>(),
                interaction, startAttempt, List.of());
        try {
            return executeAfterRunStarted(context, () -> {
                RuntimeSessionMode runtimeSessionMode = RuntimeSessionMode.RESUME;
                RuntimeBinding binding;
                if (!approved) {
                    binding = runtimeBindingService.resumeForInteraction(interaction, runId, options.agentMode());
                } else if (RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(candidateProvider)) {
                    RuntimeBinding currentBinding = runtimeBindingService.resumeForInteraction(interaction, runId);
                    runtimeBindingService.markNotRoutable(
                            currentBinding,
                            firstText(interaction.requestPayload().get("refusalCode")));
                    binding = runtimeBindingService.bindDomainAgentForRun(new DomainAgentBindingCommand(
                            user.tenantId(), user.ownerUserId(), session.id(), runId,
                            interaction.assistantMessageId(), candidateTargetId,
                            "user-confirmed", routeSwitchBindingMetadata(interaction), options.agentMode()));
                } else if (RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER.equals(candidateProvider)) {
                    RuntimeBinding currentBinding = runtimeBindingService.resumeForInteraction(interaction, runId);
                    runtimeBindingService.markNotRoutable(
                            currentBinding,
                            firstText(interaction.requestPayload().get("refusalCode")));
                    RuntimeBindingResolution resolution = runtimeBindingService.resolveForRun(
                            user.tenantId(), user.ownerUserId(), session.id(), runId,
                            interaction.assistantMessageId());
                    binding = resolution.binding();
                    runtimeSessionMode = resolution.sessionMode();
                } else {
                    throw new IllegalArgumentException("不支持的候选 Runtime provider: " + candidateProvider);
                }
                bindingRef.set(binding);
                bestEffortBindResolvedRoute(runId, route, binding);
                Flux<ChatEvent> body;
                if (approved) {
                    IntentDecision switchIntent = routeSwitchIntent(interaction, route);
                    MemoryContext runtimeMemory = recordAppliedRouteDecision(new AppliedRouteDecisionContext(
                            user, session.id(), runId, candidateRouteQuery,
                            switchIntent, route, binding, MemoryContext.empty()));
                    ChatCommand command = new ChatCommand(null, user.tenantId(), user.ownerUserId(), session.id(), null,
                            null, originalQuery == null ? "" : originalQuery, List.of(), Map.of(),
                            route.type() == RouteType.DOMAIN_AGENT ? "DOMAIN_AGENT" : null,
                            route.type() == RouteType.DOMAIN_AGENT ? candidateTargetId : null, ChatRunMode.NEXT,
                            interaction.assistantMessageId(), null, null, null, null, null, null, null,
                            null, null, options.agentMode());
                    if (route.type() == RouteType.DOMAIN_AGENT) {
                        DomainAgentRunContext domainContext = new DomainAgentRunContext(
                                command, runId, session, runtimeMemory, route, user, routeRef, bindingRef,
                                executionClaim, forwardHeaders, options.traceContext(), switchIntent, List.of(),
                                new HashSet<>(), 0, candidateRouteQuery);
                        body = requireCurrentOwnerRunning(executionClaim, "before-route-switch-domain-agent")
                                .thenMany(Flux.defer(() -> executeDomainAgentWithReroute(domainContext)));
                    } else {
                        RuntimeSessionMode selectedMode = runtimeSessionMode;
                        body = requireCurrentOwnerRunning(executionClaim, "before-route-switch-relay")
                                .thenMany(Flux.defer(() -> agentRuntimeExecutor.execute(new RuntimeExecutionContext(
                                        command, runId, runtimeMemory, switchIntent, route, user, binding,
                                        selectedMode, forwardHeaders, List.of(), options.traceContext()))));
                    }
                    body = Flux.concat(Flux.just(routeSwitchAppliedEvent(
                            runId, session.id(), interaction, route, binding)), body);
                } else {
                    foldRouteClarificationsWithoutDecision(user, session.id());
                    body = Flux.just(routeSwitchDeclinedEvent(runId, session.id(), interaction));
                }
                return Flux.concat(Flux.just(responseEvent), body);
            });
        } catch (RuntimeException ex) {
            return failInteractionContinuationRun(context, ex);
        }
    }

    private Flux<ChatEvent> failInteractionContinuationRun(RunEventPipelineContext context, RuntimeException ex) {
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                        "Interaction continuation failed after run creation; falling back to run.failed")
                .runId(context.runId())
                .sessionId(context.session().id())
                .operation("interaction.continue")
                .attribute("interactionId", context.continuationInteractionRequest() == null
                        ? null : context.continuationInteractionRequest().id())
                .build(), ex);
        return persistAndPublishRunEvents(Flux.just(runtimeErrorEvent(context.runId(), context.session().id(), ex)),
                context).doFinally(ignored -> runExecutionRegistry.complete(context.executionClaim()));
    }

    private Flux<ChatEvent> failExecutionInitialization(ChatRun run,
                                                        ChatInteractionRequest interaction,
                                                        RuntimeException failure) {
        String message = blankToDefault(failure == null ? null : failure.getMessage(),
                "run execution 初始化失败");
        ChatEvent failed = ErrorEvent.of(run.id(), run.sessionId(), "RUN_EXECUTION_INIT_FAILED", message);
        if (terminalCommitService == null) {
            return Flux.error(new IllegalStateException(
                    "ChatRunTerminalCommitService 未配置，无法安全提交 execution 初始化失败终态", failure));
        }
        ChatRunTerminalCommitService.ExternalTerminalCommitResult result =
                terminalCommitService.commitExternalTerminal(
                        ChatRunTerminalCommitService.ExternalTerminalCommitCommand.executionInitFailure(
                                failed, run, interaction == null ? null : interaction.id()));
        chatRunService.synchronizeCommittedRunCache(result.run());
        if (!result.committed()) {
            log.info("Run execution initialization terminal claim was not acquired. runId={}, currentStatus={}",
                    run.id(), result.run() == null ? null : result.run().status());
            return Flux.empty();
        }
        try {
            chatStreamService.publishPersisted(result.event());
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.WEBSOCKET_SEND_FAILED,
                            "Run execution initialization failure committed but realtime publish failed")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.execution-init-failure-publish")
                    .build());
        }
        return Flux.just(result.event());
    }

    @Override
    public Flux<ChatEvent> executeRun(UserContext user, ChatCommand command, RuntimeForwardHeaders forwardHeaders) {
        return executeRun(user, TraceContext.empty(), command, forwardHeaders);
    }

    @Override
    public Flux<ChatEvent> executeRun(UserContext user, TraceContext traceContext, ChatCommand command,
                                      RuntimeForwardHeaders forwardHeaders) {
        return executeRun(user, normalizeTraceContext(traceContext), command, forwardHeaders, null);
    }

    private Flux<ChatEvent> executeRun(UserContext user, TraceContext traceContext, ChatCommand command,
                                       RuntimeForwardHeaders forwardHeaders, RunStartAttempt startAttempt) {
        // defer 确保每个订阅都会生成独立 runId，避免热流复用导致事件串线。
        return Flux.defer(() -> {
            ensureStartAttemptActive(startAttempt, "before-run-prepare");
            RuntimeForwardHeaders headerSnapshot = normalizeForwardHeaders(forwardHeaders);
            // 进入 application 后统一以 UserContext 为准；原始前端请求只保留会话、消息、附件和元数据。
            ChatCommand identified = new ChatCommand(command.commandId(), user.tenantId(), user.ownerUserId(),
                    command.sessionId(), command.conversationId(), command.channel(), command.message(),
                    command.attachments(), command.metadata(), command.targetType(), command.targetId(),
                    command.runMode(), command.parentMessageId(),
                    command.editedMessageId(), command.regeneratedMessageId(), command.routeTrigger(),
                    command.interactionId(), command.approved(), command.scope(), command.questionnaireAnswers(),
                    command.appId(), command.appName(), command.agentMode());
            String explicitDomainAgentId = explicitDomainAgentId(identified);
            boolean directDomainAgentWaitBypass = runAdmissionCommitService != null
                    && directDomainAgentWaitBypass(identified, explicitDomainAgentId);
            boolean forceReroute = forceReroute(identified);
            if (forceReroute && explicitDomainAgentId != null) {
                throw new IllegalArgumentException("forceReroute=true 时不能同时指定 targetType/targetId");
            }

            // 会话不存在时创建会话；历史 Memory 先排除本轮输入，避免 Runtime 再接收用户消息时重复。
            ChatSession session = sessionService.loadOrCreate(identified);
            ensureStartAttemptActive(startAttempt, "after-session-load");
            // 同一会话同一时刻只允许一个 active run。这里在写入用户消息前快速拒绝，
            // 避免多页签重复提交时先污染消息树；createRunning 仍会再做一次 Redis 原子声明。
            if (chatInteractionService != null && !directDomainAgentWaitBypass) {
                chatInteractionService.rejectIfWaiting(user, session.id());
            }
            chatRunService.rejectIfActiveRunExists(user, session.id());

            List<AttachmentRef> requestedAttachments = identified.attachments() == null
                    ? List.of()
                    : identified.attachments();
            ResolvedChatAttachments resolvedAttachments = requestedAttachments.isEmpty()
                    ? ResolvedChatAttachments.empty()
                    : documentFacade.resolveChatAttachmentsForUser(user, requestedAttachments);
            List<AttachmentRef> attachments = resolvedAttachments.attachments();
            List<UploadedDocument> documents = resolvedAttachments.documents();
            ensureStartAttemptActive(startAttempt, "after-document-resolve");
            String effectiveMessage = nextMessageWithAttachments(
                    identified.runMode(), identified.message(), attachments);
            ChatCommand normalized = new ChatCommand(identified.commandId(), user.tenantId(), user.ownerUserId(),
                    session.id(), identified.conversationId(), identified.channel(), effectiveMessage,
                    attachments, identified.metadata(), identified.targetType(), identified.targetId(),
                    identified.runMode(), identified.parentMessageId(),
                    identified.editedMessageId(), identified.regeneratedMessageId(), identified.routeTrigger(),
                    identified.interactionId(), identified.approved(), identified.scope(),
                    identified.questionnaireAnswers(), identified.appId(), identified.appName(),
                    identified.agentMode());
            String runId = startAttempt == null
                    ? idGenerator.newId("run", IdGenerateContext.of(user.tenantId(), user.ownerUserId(), session.id()))
                    : startAttempt.runId();

            // MemoryContext 是可选 SuperAgent 记忆增强。长短期记忆都关闭时这里返回空上下文，
            // 且不会查询 Redis、历史消息或长期记忆服务；当前用户输入也不会进入本轮上下文，避免重复。
            MemoryContext memory = memoryService.loadForRun(normalized);
            ensureStartAttemptActive(startAttempt, "after-memory-load");
            ChatRunAdmissionCommitService.AdmissionResult admission;
            if (runAdmissionCommitService == null) {
                admission = legacyRunAdmission(user, normalized, session, runId, attachments);
            } else if (directDomainAgentWaitBypass) {
                admission = runAdmissionCommitService.commitDirectDomainAgent(
                        user, normalized, session, runId, attachments);
            } else {
                admission = runAdmissionCommitService.commit(user, normalized, session, runId, attachments);
            }
            ChatRunMessagePlan messagePlan = admission.messagePlan();
            ChatRun run = admission.run();
            admission.cancelledBindings().forEach(this::scheduleRuntimeBindingCacheSync);
            trackStartAttemptRun(startAttempt, run, "after-run-admission");
            chatRunService.synchronizeCommittedRunCache(run);
            ensureStartAttemptActive(startAttempt, "after-run-cache-sync");
            ChatCommand runCommand = commandForExecution(normalized, messagePlan);
            String currentRouteQuery = routeMemoryQuery(messagePlan, null);
            String intentQuery = clarificationAnswerWithAttachments(runCommand.message(), attachments);
            String runtimeBindingLeafId = runtimeBindingLeafId(messagePlan);
            AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>();
            AtomicReference<RouteTarget> routeRef = new AtomicReference<>();
            AtomicReference<RuntimeSessionMode> runtimeSessionModeRef =
                    new AtomicReference<>(RuntimeSessionMode.RESUME);
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef = new AtomicReference<>();
            AssistantAssembly assistant = new AssistantAssembly();
            RunExecutionClaim executionClaim;
            try {
                executionClaim = chatRunLeaseService.startRun(run);
            } catch (RuntimeException ex) {
                return failExecutionInitialization(run, null, ex);
            }
            trackStartAttemptExecution(startAttempt, executionClaim, "after-execution-create");
            try {
                /*
                 * 外部用例库/意图路由放在 run pipeline 内执行。这样 run.started 会先落库和发布，
                 * 慢意图服务只影响后续输出，不会阻塞前端获得 runId 与首个事件。
                 */
                RunEventPipelineContext context = new RunEventPipelineContext(user, session, messagePlan,
                        routeRef, bindingRef, assistant, runId, executionClaim, pendingInteractionPayloadRef, null,
                        startAttempt, documentIds(documents));
                return executeAfterRunStarted(context, () -> {
                    prepareInitialRouteAndBinding(new InitialRoutePreparation(
                            user, session, runId, runtimeBindingLeafId, runCommand,
                            explicitDomainAgentId, forceReroute,
                            routeRef, bindingRef, runtimeSessionModeRef, normalized.agentMode()));
                    return routeAndExecute(new RoutePipelineRequest(
                            user, session, runCommand, attachments, documents, memory, runId, runtimeBindingLeafId,
                            headerSnapshot, traceContext, routeRef, bindingRef, runtimeSessionModeRef, executionClaim, run,
                            currentRouteQuery, intentQuery, intentQuery, null, normalized.agentMode()));
                });
            } catch (RuntimeException ex) {
                // run 已创建后同步步骤失败时，也必须写入 run.failed 并释放 active run，避免前端看到永远 RUNNING。
                return persistAndPublishRunEvents(
                        Flux.just(runtimeErrorEvent(runId, session.id(), ex)),
                        new RunEventPipelineContext(user, session, messagePlan, routeRef, bindingRef, assistant, runId,
                                executionClaim, pendingInteractionPayloadRef, null, startAttempt,
                                documentIds(documents))
                ).doFinally(ignored -> runExecutionRegistry.complete(executionClaim));
            }
        });
    }

    private ChatRunAdmissionCommitService.AdmissionResult legacyRunAdmission(
            UserContext user, ChatCommand command, ChatSession session, String runId,
            List<AttachmentRef> attachments) {
        ChatRunMessagePlan messagePlan = sessionService.prepareRunMessage(
                user, command, session, runId, attachments);
        ChatRun run = chatRunService.createRunning(new CreateChatRunContext(
                runId,
                user,
                session.id(),
                null,
                null,
                SelectedIntentContext.removeReserved(command.metadata()),
                messagePlan.runMode(),
                messagePlan.parentMessageId(),
                messagePlan.userMessage().id()
        ));
        return new ChatRunAdmissionCommitService.AdmissionResult(messagePlan, run);
    }

    private Flux<ChatEvent> executeDomainAgentWithReroute(DomainAgentRunContext context) {
        if (context.route() == null || context.route().selectedAgentCode() == null
                || context.route().selectedAgentCode().isBlank()) {
            return Flux.error(new IllegalStateException("DomainAgent 路由缺少目标 ID"));
        }
        AtomicReference<DomainAgentRefusal> refusalRef = new AtomicReference<>();
        AtomicReference<Sinks.One<Void>> refusalPersistedRef = new AtomicReference<>();
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
                        context.documents(),
                        context.traceContext()))
                .map(event -> enrichDomainAgentControlEvent(event, context.route().selectedAgentCode()))
                .map(event -> {
                    DomainAgentRefusal refusal = domainAgentRefusal(event);
                    if (refusal == null || !refusalRef.compareAndSet(null, refusal)) {
                        return event;
                    }
                    Sinks.One<Void> persisted = Sinks.one();
                    refusalPersistedRef.set(persisted);
                    return new PersistenceAcknowledgedEvent(event, persisted);
                })
                // takeUntil 会保留拒答控制事件本身，并立即取消旧 DomainAgent 上游订阅。
                // 同一个下游 frame 中排在拒答之后的 endFlag/snapshot 也不会进入本 run。
                .takeUntil(event -> refusalRef.get() != null);
        return current.concatWith(Flux.defer(() -> {
            Sinks.One<Void> persisted = refusalPersistedRef.get();
            Mono<Void> persistenceGate = persisted == null
                    ? Mono.empty()
                    : persisted.asMono().publishOn(eventIoScheduler);
            return persistenceGate.thenMany(Flux.defer(() ->
                            continueAfterDomainAgentRefusal(context, refusalRef.get()))
                    .subscribeOn(eventIoScheduler));
        }));
    }

    private ChatEvent enrichDomainAgentControlEvent(ChatEvent event, String domainAgentId) {
        if (event == null || event.payload() == null
                || DomainAgentControlEventMapper.fromNormalizedPayload(event.payload()).isEmpty()) {
            return event;
        }
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        putIfNotNull(payload, "domainAgentId", domainAgentId);
        putIfNotNull(payload, "targetId", domainAgentId);
        payload.put("provider", RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER);
        return new RuntimeEvent(event.runId(), event.sessionId(), event.sequence(), event.createdAt(),
                event.type(), ChatPayloadMaps.immutableCopy(payload));
    }

    private Flux<ChatEvent> continueAfterDomainAgentRefusal(DomainAgentRunContext context,
                                                           DomainAgentRefusal refusal) {
        if (refusal == null) {
            return Flux.empty();
        }
        markRejectedAutomaticBindingNotRoutable(context, refusal);
        String currentDomainAgentId = context.route().selectedAgentCode();
        Set<String> rejected = new HashSet<>(context.rejectedDomainAgentIds());
        rejected.add(currentDomainAgentId);
        if (context.rerouteCount() >= domainAgentProperties.normalizedMaxReroutes()) {
            foldRouteClarificationsWithoutDecision(context.user(), context.session().id());
            return Flux.just(domainAgentRerouteMetadata(context, refusal, null, "MAX_REROUTES_REACHED"));
        }
        ChatCommand rerouteCommand = commandWithDomainRejectContext(
                context.command(), rejectedIntentName(context), refusal);
        String rerouteIntentQuery = rerouteCommand.metadata().containsKey("intentClarification")
                ? blankToDefault(context.routeMemoryQuery(), rerouteCommand.message())
                : clarificationAnswerWithAttachments(rerouteCommand.message(), rerouteCommand.attachments());
        DomainAgentRerouteContext rerouteContext = new DomainAgentRerouteContext(
                context, refusal, currentDomainAgentId, rejected, rerouteIntentQuery, null);
        return requireCurrentOwnerRunning(context.executionClaim(), "before-domain-agent-reroute")
                .thenMany(Flux.defer(() -> routeSignalService.routeInitialWithProgress(new RouteSignalRequest(
                        context.runId(), context.user(), context.session(), rerouteCommand,
                        rerouteCommand.attachments(), context.memory(), rerouteIntentQuery))))
                .concatMap(frame -> {
                    if (frame.eventFrame()) {
                        return Flux.just(frame.event());
                    }
                    if (frame.progressFrame()) {
                        return Flux.just(routeProgressEvent(context.runId(), context.session().id(), frame.progress()));
                    }
                    return requireCurrentOwnerRunning(context.executionClaim(), "after-domain-agent-reroute")
                            .thenMany(Flux.defer(() -> continueAfterDomainAgentReroute(
                                    rerouteContext, frame.result())));
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
                            intentClarificationPayloadWithRerouteContext(reroute, nextSignal.intentClarificationPayload())));
        }
        RouteTarget nextRoute = nextSignal.route();
        if (nextSignal.failRunOnIntentFailure()) {
            context.bindingRef().set(markRejectedBindingNotRoutable(context.bindingRef().get(), refusal));
            recordIntentIfPresent(context, nextSignal.intentDecision(), null);
            foldRouteClarificationsWithoutDecision(context.user(), context.session().id());
            return Flux.error(new IntentRoutingFailedException(nextSignal.intentFailureReason()));
        }
        if (nextRoute != null && nextRoute.type() == RouteType.AGENT_RUNTIME) {
            recordIntentIfPresent(context, nextSignal.intentDecision(), nextRoute);
            if (requiresRouteSwitchConfirmation(routeSource(context.bindingRef().get()))) {
                return Flux.just(routeSwitchConfirmationRequest(reroute, nextSignal));
            }
            context.bindingRef().set(markRejectedBindingNotRoutable(context.bindingRef().get(), refusal));
            RuntimeBindingResolution resolution = runtimeBindingService.resolveForRun(
                    context.user().tenantId(), context.user().ownerUserId(), context.session().id(),
                    context.runId(), runtimeBindingLeafIdForCommand(context.command()));
            context.bindingRef().set(resolution.binding());
            context.routeRef().set(nextRoute);
            bestEffortBindResolvedRoute(context.runId(), nextRoute, resolution.binding());
            MemoryContext runtimeMemory = recordAppliedRouteDecision(new AppliedRouteDecisionContext(
                    context.user(), context.session().id(), context.runId(), reroute.intentQuery(),
                    nextSignal.intentDecision(), nextRoute, resolution.binding(), context.memory()));
            ChatCommand runtimeCommand = runtimeCommandWithIntentDocuments(
                    context.command(), context.documents(), nextRoute, nextSignal.intentDecision(), null);
            String action = nextSignal.intentFailure() ? "RELAY_FALLBACK" : "ROUTE_TO_RELAY";
            return Flux.concat(
                    Flux.just(domainAgentRerouteMetadata(context, refusal, nextRoute, action)),
                    requireCurrentOwnerRunning(context.executionClaim(), "before-relay-reroute-runtime")
                            .thenMany(Flux.defer(() -> agentRuntimeExecutor.execute(new RuntimeExecutionContext(
                                    runtimeCommand,
                                    context.runId(),
                                    runtimeMemory,
                                    nextSignal.intentDecision(),
                                    nextRoute,
                                    context.user(),
                                    resolution.binding(),
                                    resolution.sessionMode(),
                                    context.forwardHeaders(),
                                    context.documents(),
                                    context.traceContext())))));
        }
        if (nextRoute == null || nextRoute.type() != RouteType.DOMAIN_AGENT
                || nextRoute.selectedAgentCode() == null || nextRoute.selectedAgentCode().isBlank()
                || reroute.rejectedDomainAgentIds().contains(nextRoute.selectedAgentCode())) {
            foldRouteClarificationsWithoutDecision(context.user(), context.session().id());
            return Flux.just(domainAgentRerouteMetadata(context, refusal, nextRoute, "NO_AVAILABLE_DOMAIN_AGENT"));
        }
        recordIntentIfPresent(context, nextSignal.intentDecision(), nextRoute);
        String routeSource = routeSource(context.bindingRef().get());
        if (requiresRouteSwitchConfirmation(routeSource)
                && !reroute.currentDomainAgentId().equals(nextRoute.selectedAgentCode())) {
            return Flux.just(routeSwitchConfirmationRequest(reroute, nextSignal));
        }
        context.bindingRef().set(markRejectedBindingNotRoutable(context.bindingRef().get(), refusal));
        RuntimeBinding nextBinding = runtimeBindingService.bindDomainAgentForRun(new DomainAgentBindingCommand(
                context.user().tenantId(),
                context.user().ownerUserId(),
                context.session().id(),
                context.runId(),
                runtimeBindingLeafIdForCommand(context.command()),
                nextRoute.selectedAgentCode(),
                nextRoute.routeSource(),
                domainAgentBindingMetadata(nextRoute, nextSignal.intentDecision()), reroute.agentMode()));
        context.bindingRef().set(nextBinding);
        context.routeRef().set(nextRoute);
        bestEffortBindResolvedRoute(context.runId(), nextRoute, nextBinding);
        MemoryContext runtimeMemory = recordAppliedRouteDecision(new AppliedRouteDecisionContext(
                context.user(), context.session().id(), context.runId(), reroute.intentQuery(),
                nextSignal.intentDecision(), nextRoute, nextBinding, context.memory()));
        ChatCommand runtimeCommand = runtimeCommandWithIntentDocuments(
                context.command(), context.documents(), nextRoute, nextSignal.intentDecision(), null);
        DomainAgentRunContext nextContext = new DomainAgentRunContext(
                runtimeCommand,
                context.runId(),
                context.session(),
                runtimeMemory,
                nextRoute,
                context.user(),
                context.routeRef(),
                context.bindingRef(),
                context.executionClaim(),
                context.forwardHeaders(),
                context.traceContext(),
                nextSignal.intentDecision(),
                context.documents(),
                reroute.rejectedDomainAgentIds(),
                context.rerouteCount() + 1,
                context.routeMemoryQuery());
        return Flux.concat(
                Flux.just(domainAgentRerouteMetadata(context, refusal, nextRoute, "AUTO_SWITCH")),
                requireCurrentOwnerRunning(context.executionClaim(), "before-domain-agent-reroute-runtime")
                        .thenMany(Flux.defer(() -> executeDomainAgentWithReroute(nextContext))));
    }

    private RuntimeEvent routeSwitchConfirmationRequest(DomainAgentRerouteContext reroute,
                                                        RouteSignalResult nextSignal) {
        DomainAgentRunContext context = reroute.context();
        DomainAgentRefusal refusal = reroute.refusal();
        RouteTarget candidate = nextSignal.route();
        String currentRouteSource = routeSource(context.bindingRef().get());
        String candidateProvider = candidate.type() == RouteType.DOMAIN_AGENT
                ? RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER
                : RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER;
        String candidateTargetId = candidate.type() == RouteType.DOMAIN_AGENT
                ? candidate.selectedAgentCode()
                : RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "route-switch-confirmation-request");
        payload.put("interactionType", ChatInteractionType.ROUTE_SWITCH_CONFIRMATION.name());
        payload.put("currentProvider", RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER);
        payload.put("currentTargetId", context.route().selectedAgentCode());
        payload.put("currentRouteSource", currentRouteSource);
        payload.put("candidateProvider", candidateProvider);
        payload.put("candidateTargetId", candidateTargetId);
        payload.put("message", "当前领域 Agent 无法处理该问题，是否切换到新的处理能力继续回答？");
        putIfNotNull(payload, "candidateIntentCode",
                nextSignal.intentDecision() == null ? null : nextSignal.intentDecision().intentCode());
        putIfNotNull(payload, "candidateIntentName",
                nextSignal.intentDecision() == null ? null : nextSignal.intentDecision().intentName());
        putIfNotNull(payload, "routeAction", routeAction(nextSignal.intentDecision()));
        putIfNotNull(payload, "refusalCode", refusal.code());
        putIfNotNull(payload, "refusalReasonCode", refusal.reasonCode());
        putIfNotNull(payload, "refusalRecoverable", refusal.recoverable());
        putIfNotNull(payload, "refusalReason", refusal.message());
        payload.put("originalQuery", context.command().message() == null ? "" : context.command().message());
        putIfNotNull(payload, "routeMemoryQuery", reroute.intentQuery());
        putIfNotNull(payload, "candidateRouteSource", candidate.routeSource());
        return RuntimeEvent.card(context.runId(), context.session().id(), ChatPayloadMaps.immutableCopy(payload));
    }

    private boolean protectedRouteSource(String routeSource) {
        return "front-selected".equals(routeSource) || "user-confirmed".equals(routeSource);
    }

    private boolean requiresRouteSwitchConfirmation(String routeSource) {
        return protectedRouteSource(routeSource) && !domainAgentProperties.isRefusalAutoSwitchEnabled();
    }

    private void markRejectedAutomaticBindingNotRoutable(DomainAgentRunContext context,
                                                          DomainAgentRefusal refusal) {
        context.bindingRef().set(markRejectedAutomaticBindingNotRoutable(context.bindingRef().get(), refusal));
    }

    private RuntimeBinding markRejectedAutomaticBindingNotRoutable(RuntimeBinding binding,
                                                                    DomainAgentRefusal refusal) {
        if (binding == null || requiresRouteSwitchConfirmation(routeSource(binding))) {
            return binding;
        }
        return markRejectedBindingNotRoutable(binding, refusal);
    }

    private RuntimeBinding markRejectedBindingNotRoutable(RuntimeBinding binding,
                                                           DomainAgentRefusal refusal) {
        if (binding == null || binding.status() != RuntimeBindingStatus.ACTIVE) {
            return binding;
        }
        return runtimeBindingService.markNotRoutable(binding, refusal == null ? null : refusal.code());
    }

    private String routeAction(IntentDecision intent) {
        return firstText(intent == null || intent.slots() == null ? null : intent.slots().get("routeAction"),
                intent == null || intent.raw() == null ? null : intent.raw().get("routeAction"));
    }

    private Flux<ChatEvent> intentClarificationWaitingBody(String runId, String sessionId,
                                                           Map<String, Object> requestPayload) {
        return Flux.just(intentClarificationRequestEvent(runId, sessionId, requestPayload),
                MessageCompletedEvent.of(runId, sessionId));
    }

    private Map<String, Object> intentClarificationPayloadWithRerouteContext(
            DomainAgentRerouteContext reroute,
            Map<String, Object> requestPayload) {
        Map<String, Object> payload = new LinkedHashMap<>(mapOrEmpty(requestPayload));
        DomainAgentRunContext context = reroute.context();
        Map<String, Object> rerouteState = new LinkedHashMap<>();
        rerouteState.put("currentProvider", RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER);
        rerouteState.put("currentTargetId", reroute.currentDomainAgentId());
        putIfNotNull(rerouteState, "currentBindingId",
                context.bindingRef().get() == null ? null : context.bindingRef().get().id());
        putIfNotNull(rerouteState, "currentRouteSource", routeSource(context.bindingRef().get()));
        putIfNotNull(rerouteState, "refusalCode", reroute.refusal().code());
        putIfNotNull(rerouteState, "refusalReasonCode", reroute.refusal().reasonCode());
        putIfNotNull(rerouteState, "refusalRecoverable", reroute.refusal().recoverable());
        putIfNotNull(rerouteState, "refusalReason", reroute.refusal().message());
        putIfNotNull(rerouteState, "refusalAgentId", reroute.refusal().agentId());
        rerouteState.put("rerouteCount", context.rerouteCount());
        rerouteState.put("rejectedDomainAgentIds", List.copyOf(reroute.rejectedDomainAgentIds()));
        putIfNotNull(rerouteState, "originalQuery", firstText(context.routeMemoryQuery(), context.command().message()));
        payload.put(DOMAIN_AGENT_REROUTE_CONTEXT_METADATA, Map.copyOf(rerouteState));
        return ChatPayloadMaps.immutableCopy(payload);
    }

    private RuntimeEvent intentClarificationRequestEvent(String runId, String sessionId,
                                                         Map<String, Object> requestPayload) {
        Map<String, Object> payload = new LinkedHashMap<>(mapOrEmpty(requestPayload));
        payload.put("source", "intent-agent");
        payload.put("sourceType", "intent-clarification-request");
        payload.put("interactionType", ChatInteractionType.INTENT_CLARIFICATION.name());
        return RuntimeEvent.card(runId, sessionId, Map.copyOf(payload));
    }

    private RuntimeEvent domainAgentRerouteMetadata(DomainAgentRunContext context, DomainAgentRefusal refusal,
                                                    RouteTarget nextRoute, String action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "domain-agent-reroute");
        payload.put("metadataType", "domain_agent_reroute");
        payload.put("action", action);
        putIfNotNull(payload, "currentDomainAgentId", context.route().selectedAgentCode());
        putIfNotNull(payload, "refusalCode", refusal.code());
        putIfNotNull(payload, "refusalReasonCode", refusal.reasonCode());
        putIfNotNull(payload, "refusalRecoverable", refusal.recoverable());
        putIfNotNull(payload, "refusalReason", refusal.message());
        if (nextRoute != null) {
            putIfNotNull(payload, "candidateDomainAgentId", nextRoute.selectedAgentCode());
            putIfNotNull(payload, "candidateRouteSource", nextRoute.routeSource());
        }
        return RuntimeEvent.metadata(context.runId(), context.session().id(), payload);
    }

    private DomainAgentRefusal domainAgentRefusal(ChatEvent event) {
        if (event == null || event.payload() == null) {
            return null;
        }
        return DomainAgentControlEventMapper.fromNormalizedPayload(event.payload())
                .filter(DomainAgentControlEventMapper.ControlEvent::reroute)
                .map(control -> new DomainAgentRefusal(
                        control.code(), control.reasonCode(), control.recoverable(),
                        control.message(), control.agentId()))
                .orElse(null);
    }

    private ChatCommand commandWithDomainRejectContext(ChatCommand command, String intentName,
                                                       DomainAgentRefusal refusal) {
        Map<String, Object> metadata = new LinkedHashMap<>(
                SelectedIntentContext.removeReserved(command.metadata()));
        metadata.put("routeTrigger", "domain_reject");
        metadata.put("lastIntentRejectReason", Map.of(
                "lastIntent", intentName,
                "domainRejectMessage", refusal.message() == null ? "" : refusal.message()
        ));
        return new ChatCommand(command.commandId(), command.tenantId(), command.userId(), command.sessionId(),
                command.conversationId(), command.channel(), command.message(), command.attachments(), metadata,
                command.targetType(), command.targetId(), command.runMode(), command.parentMessageId(),
                command.editedMessageId(), command.regeneratedMessageId(), "domain_reject",
                command.interactionId(), command.approved(), command.scope(), command.questionnaireAnswers(),
                command.appId(), command.appName(), command.agentMode());
    }

    private String rejectedIntentName(DomainAgentRunContext context) {
        RuntimeBinding binding = context == null || context.bindingRef() == null
                ? null
                : context.bindingRef().get();
        Object bindingIntentName = binding == null || binding.metadata() == null
                ? null
                : binding.metadata().get("intentName");
        IntentDecision intent = context == null ? null : context.intentDecision();
        String decisionIntentName = intent == null ? null : intent.intentName();
        return blankToDefault(firstText(bindingIntentName, decisionIntentName), "未知意图");
    }

    private boolean forceReroute(ChatCommand command) {
        return command != null
                && RouteMemoryApplicationService.TRIGGER_USER_CORRECTION.equals(command.routeTrigger());
    }

    private boolean directDomainAgentWaitBypass(ChatCommand command, String explicitDomainAgentId) {
        return command != null
                && command.runMode() == ChatRunMode.NEXT
                && explicitDomainAgentId != null;
    }

    private ChatCommand commandWithIntentClarificationContext(UserContext user, ChatSession session,
                                                              ChatInteractionRequest interaction,
                                                              Map<String, Object> responsePayload,
                                                              IntentClarificationContinuationInput input) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, Object> requestPayload = interaction.requestPayload() == null ? Map.of() : interaction.requestPayload();
        Map<String, Object> publicRequestPayload = withoutIntentClarificationDocumentIds(requestPayload);
        String clarifyAnswer = blankToDefault(input.intentQuery(), intentClarificationAnswer(responsePayload));
        String resolvedOriginalQuery = blankToDefault(
                firstText(requestPayload.get("originalQuery"), clarifyAnswer), "");
        List<Map<String, Object>> clarificationHistory = appendClarificationHistory(
                requestPayload, responsePayload, clarifyAnswer, resolvedOriginalQuery);
        Map<String, Object> intentClarification = new LinkedHashMap<>();
        intentClarification.put("interactionId", interaction.id());
        intentClarification.put("intentSessionId", interaction.runtimeSessionId() == null ? "" : interaction.runtimeSessionId());
        intentClarification.put("intentRequestId", requestPayload.getOrDefault("intentRequestId", ""));
        intentClarification.put("originalQuery", resolvedOriginalQuery);
        putNonNull(intentClarification, "clarifyQuestion", clarifyQuestion(requestPayload));
        putNonNull(intentClarification, "clarificationType", clarificationType(requestPayload));
        intentClarification.put("answerText", clarifyAnswer == null ? "" : clarifyAnswer);
        intentClarification.put("clarificationHistory", clarificationHistory);
        intentClarification.put("request", publicRequestPayload);
        intentClarification.put("response", responsePayload == null ? Map.of() : responsePayload);
        metadata.put("routeTrigger", "clarify_answer");
        metadata.put("intentClarification", Map.copyOf(intentClarification));
        Object domainAgentRerouteContext = requestPayload.get(DOMAIN_AGENT_REROUTE_CONTEXT_METADATA);
        if (domainAgentRerouteContext instanceof Map<?, ?> rerouteContext) {
            metadata.put(DOMAIN_AGENT_REROUTE_CONTEXT_METADATA, mapOrEmpty(rerouteContext));
        }
        return new ChatCommand(null, user.tenantId(), user.ownerUserId(), session.id(), null,
                null, clarifyAnswer == null ? "" : clarifyAnswer,
                input.cumulativeAttachments(), Map.copyOf(metadata),
                null, null, ChatRunMode.NEXT, interaction.assistantMessageId(), null, null, "clarify_answer",
                null, null, null, Map.of(), null, null, input.agentMode());
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
        String normalized = firstText(responsePayload.get("answerText"));
        if (normalized != null) {
            return normalized;
        }
        Object answers = responsePayload.get("questionnaireAnswers");
        if (answers instanceof Map<?, ?> answerMap && !answerMap.isEmpty()) {
            java.util.List<Map.Entry<String, String>> entries = answerMap.entrySet().stream()
                    .map(entry -> Map.entry(
                            entry.getKey() == null ? "" : String.valueOf(entry.getKey()).trim(),
                            entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim()))
                    .filter(entry -> !entry.getValue().isBlank())
                    .sorted(Map.Entry.comparingByKey())
                    .toList();
            if (entries.size() == 1) {
                return entries.getFirst().getValue();
            }
            if (!entries.isEmpty()) {
                return entries.stream()
                        .map(entry -> (entry.getKey().isBlank() ? "问题" : entry.getKey()) + "：" + entry.getValue())
                        .collect(java.util.stream.Collectors.joining("\n"));
            }
        }
        return firstText(responsePayload.get("answer"),
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
        return domainAgentBindingMetadata(route, intent, Map.of());
    }

    private Map<String, Object> domainAgentBindingMetadata(RouteTarget route, IntentDecision intent,
                                                            Map<String, Object> commandMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (route != null) {
            metadata.put("domainAgentId", route.selectedAgentCode());
            metadata.put("routeSource", route.routeSource());
        }
        if (intent != null) {
            metadata.put("intentCode", intent.intentCode());
            metadata.put("intentName", intent.intentName());
            metadata.put("intentConfidence", intent.confidence());
        } else {
            putIfNotNull(metadata, "intentCode", SelectedIntentContext.intentId(commandMetadata));
            putIfNotNull(metadata, "intentName", SelectedIntentContext.intentName(commandMetadata));
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
        Flux<ChatEvent> acceptedEvents = chatDeltaCoalescer.coalesce(events)
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
                        log.error(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                                        "Dropped mismatched chat event before persistence")
                                .runId(runId)
                                .sessionId(session.id())
                                .operation("chat-event.identity-guard")
                                .attribute("actualRunId", event == null ? null : event.runId())
                                .attribute("actualSessionId", event == null ? null : event.sessionId())
                                .attribute("eventType", event == null ? null : event.type())
                                .build());
                        rejectPersistenceAcknowledgement(event, new ChatEventAppendRejectedException(
                                "下游返回的事件身份与当前 run/session 不一致"));
                        sink.next(ErrorEvent.of(runId, session.id(), "RUN_EVENT_IDENTITY_MISMATCH",
                                "下游返回的事件身份与当前 run/session 不一致，已终止本轮回答"));
                        sink.complete();
                        return;
                    }
                    if (!chatRunService.shouldAcceptEvent(event)) {
                        rejectPersistenceAcknowledgement(event, new ChatEventAppendRejectedException(
                                "run 已不再接受事件: runId=" + runId));
                        sink.complete();
                        return;
                    }
                    sink.next(event);
                });
        Flux<ChatEventBatcher.Batch> batches = chatEventBatcher == null
                ? acceptedEvents.map(event -> ChatEventBatcher.Batch.single(event, false))
                : chatEventBatcher.batch(acceptedEvents, event -> batchableRuntimeEvent(event, context));
        return batches
                .publishOn(eventIoScheduler)
                .concatMap(batch -> {
                    if (writeRejected.get()) {
                        rejectPersistenceAcknowledgements(batch.events(), new ChatEventAppendRejectedException(
                                "run 已停止接受后续事件: runId=" + runId));
                        return Flux.empty();
                    }
                    return persistAndPublishEventBatchAsync(batch, context)
                            .onErrorResume(ChatEventAppendRejectedException.class, ex -> {
                                rejectPersistenceAcknowledgements(batch.events(), ex);
                                writeRejected.set(true);
                                log.info("Stop chat run event stream after guarded insert rejection. runId={}, reason={}",
                                        runId, ex.getMessage());
                                return Flux.empty();
                            })
                            .onErrorResume(CommittedBatchPostProcessingException.class, ex -> {
                                log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                                                "Committed chat event batch post-processing failed")
                                        .runId(runId)
                                        .sessionId(session.id())
                                        .operation("chat-event.batch-post-processing")
                                        .build());
                                if (terminalCommitService == null) {
                                    return Flux.error(ex);
                                }
                                ChatEvent failed = commitTerminalFailure(context, ex);
                                writeRejected.set(true);
                                return Flux.just(failed);
                            })
                            .onErrorResume(RuntimeException.class, ex -> {
                                rejectPersistenceAcknowledgements(batch.events(), ex);
                                if (containsEventType(batch.events(), "run.failed") || terminalCommitService == null) {
                                    return Flux.error(ex);
                                }
                                ChatEvent failed = commitTerminalFailure(context, ex);
                                writeRejected.set(true);
                                return Flux.just(failed);
                            });
                }, 0);
    }

    private Flux<ChatEvent> persistAndPublishEventBatchAsync(ChatEventBatcher.Batch batch,
                                                             RunEventPipelineContext context) {
        if (batch == null || batch.events().isEmpty()) {
            return Flux.empty();
        }
        if (!batch.databaseBatch()) {
            return persistAndPublishOneEventAsync(batch.events().getFirst(), context).flux();
        }
        return Mono.fromCallable(() -> persistAndPublishOrdinaryEventBatch(batch.events(), context))
                .subscribeOn(eventIoScheduler)
                .flatMapMany(result -> {
                    Flux<ChatEvent> persisted = Flux.fromIterable(result.events());
                    return result.failure() == null
                            ? persisted
                            : persisted.concatWith(Mono.error(result.failure()));
                });
    }

    private CommittedEventBatch persistAndPublishOrdinaryEventBatch(List<ChatEvent> events,
                                                                    RunEventPipelineContext context) {
        List<ChatEvent> storedEvents = chatStreamService.appendBatchWithExecutionGuard(
                events, context.executionClaim());
        if (storedEvents.size() != events.size()) {
            throw new IllegalStateException("聊天事件批量写入结果数量不一致: expected=" + events.size()
                    + ", actual=" + storedEvents.size());
        }
        events.forEach(this::acknowledgePersistence);
        CommittedBatchPostProcessingException failure = null;
        for (int index = 0; index < storedEvents.size(); index++) {
            ChatEvent stored = storedEvents.get(index);
            failure = attemptCommittedBatchOperation(failure, "assistant.observe", stored,
                    () -> context.assistant().observe(stored));
            failure = attemptCommittedBatchOperation(failure, "interaction.observe", stored,
                    () -> rememberPendingInteractionRequest(stored, context));
            failure = attemptCommittedBatchOperation(failure, "run.observe", stored,
                    () -> chatRunService.observeEvent(stored));
            failure = attemptCommittedBatchOperation(failure, "binding.observe", stored, () ->
                    context.bindingRef().set(runtimeBindingService.observeEvent(context.bindingRef().get(), stored)));
            failure = attemptCommittedBatchOperation(failure, "route-memory.observe", stored,
                    () -> recordRouteMemoryAfterCommitted(stored, context));
            failure = attemptCommittedBatchOperation(failure, "stream.publish", stored,
                    () -> chatStreamService.publishPersisted(stored));
        }
        return new CommittedEventBatch(storedEvents, failure);
    }

    private CommittedBatchPostProcessingException attemptCommittedBatchOperation(
            CommittedBatchPostProcessingException failure,
            String operation,
            ChatEvent event,
            Runnable action) {
        try {
            action.run();
            return failure;
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "Committed chat event post-processing step failed")
                    .runId(event.runId())
                    .sessionId(event.sessionId())
                    .operation("chat-event.post-processing")
                    .attribute("sequence", event.sequence())
                    .attribute("eventType", event.type())
                    .attribute("failedOperation", operation)
                    .build());
            if (failure == null) {
                return new CommittedBatchPostProcessingException(event, operation, ex);
            }
            failure.addSuppressed(ex);
            return failure;
        }
    }

    private boolean batchableRuntimeEvent(ChatEvent event, RunEventPipelineContext context) {
        if (event == null || event instanceof PersistenceAcknowledgedEvent
                || !BATCHABLE_RUNTIME_EVENT_TYPES.contains(event.type())
                || domainAgentRefusal(event) != null
                || interactionControlEvent(event)) {
            return false;
        }
        String source = firstText(event.payload() == null ? null : event.payload().get("source"));
        if (source != null) {
            return RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER.equals(source)
                    || RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(source);
        }
        RuntimeBinding binding = context == null ? null : context.bindingRef().get();
        return binding != null
                && (RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER.equals(binding.provider())
                || RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(binding.provider()));
    }

    private boolean interactionControlEvent(ChatEvent event) {
        if (event == null || event.payload() == null) {
            return false;
        }
        String sourceType = firstText(event.payload().get("sourceType"));
        if (event.payload().containsKey("interactionId")) {
            return true;
        }
        if (sourceType == null) {
            return false;
        }
        String normalized = sourceType.toLowerCase(java.util.Locale.ROOT);
        return "agent.refusal".equals(normalized)
                || normalized.contains("approval")
                || normalized.contains("clarification")
                || normalized.contains("confirmation")
                || normalized.startsWith("route-switch");
    }

    private boolean containsEventType(List<ChatEvent> events, String eventType) {
        return events != null && events.stream()
                .anyMatch(event -> event != null && eventType.equals(event.type()));
    }

    private void rejectPersistenceAcknowledgements(List<ChatEvent> events, Throwable failure) {
        if (events == null) {
            return;
        }
        events.forEach(event -> rejectPersistenceAcknowledgement(event, failure));
    }

    private Mono<ChatEvent> persistAndPublishOneEventAsync(ChatEvent event, RunEventPipelineContext context) {
        AutomaticDomainAgentRefusalCommit automaticRefusal = automaticDomainAgentRefusalCommit(event, context);
        if (automaticRefusal == null || terminalCommitService == null) {
            return Mono.fromCallable(() -> persistAndPublishOneEvent(event, context));
        }
        AtomicReference<RuntimeBinding> cacheBindingRef = new AtomicReference<>();
        return Mono.fromCallable(() -> terminalCommitService.commitDomainAgentRefusal(
                        new ChatRunTerminalCommitService.DomainAgentRefusalCommitCommand(
                                event,
                                context.executionClaim(),
                                automaticRefusal.binding(),
                                automaticRefusal.refusal().code())))
                .subscribeOn(domainAgentControlIoScheduler)
                .publishOn(eventIoScheduler)
                .map(result -> {
                    cacheBindingRef.set(result.binding());
                    return publishCommittedDomainAgentRefusal(event, result, context);
                })
                .doFinally(ignored -> scheduleRuntimeBindingCacheSync(cacheBindingRef.get()));
    }

    /**
     * 只有持久化后的 run.started 才能放行路由和下游 Runtime 副作用。
     *
     * <p>不能使用单纯的 thenMany/concatWith：guard 拒绝会被事件持久化链转换为空完成，而空完成
     * 不代表启动成功。这里把空完成、终态失败和真正的 run.started 显式区分。</p>
     */
    private Flux<ChatEvent> executeAfterRunStarted(RunEventPipelineContext context,
                                                   Supplier<Flux<ChatEvent>> bodySupplier) {
        return persistRunStartedGate(context).flatMapMany(outcome -> {
            if (outcome.status() == RunStartGateStatus.REJECTED) {
                log.info("Chat run start gate rejected execution; skip route and runtime side effects. runId={}",
                        context.runId());
                return Flux.empty();
            }
            if (outcome.status() == RunStartGateStatus.TERMINATED) {
                return Flux.just(outcome.event());
            }
            Flux<ChatEvent> body = Flux.concat(
                            Mono.fromRunnable(() -> ensureStartAttemptActive(
                                            context.startAttempt(), "after-run-started"))
                                    .then(requireCurrentOwnerRunning(context.executionClaim(), "after-run-started"))
                                    .thenMany(Flux.defer(bodySupplier)),
                            Flux.defer(() -> Flux.just(RunCompletedEvent.of(
                                    context.runId(), context.session().id(),
                                    runCompletedPayload(context.routeRef().get(), context.bindingRef().get())))))
                    .onErrorResume(ChatEventAppendRejectedException.class, ex -> {
                        log.info("Chat run owner lost before external side effect; stop local flow. runId={}, reason={}",
                                context.runId(), ex.getMessage());
                        return Flux.empty();
                    })
                    .onErrorResume(ex -> Flux.just(runtimeErrorEvent(
                            context.runId(), context.session().id(), ex)));
            return Flux.concat(
                    Flux.just(outcome.event()),
                    persistAndPublishRunEvents(body, context)
            );
        }).doFinally(ignored -> runExecutionRegistry.complete(context.executionClaim()));
    }

    private Mono<RunStartGateOutcome> persistRunStartedGate(RunEventPipelineContext context) {
        return persistAndPublishRunEvents(
                        Flux.just(RunStartedEvent.of(context.runId(), context.session().id())), context)
                .singleOrEmpty()
                .map(event -> "run.started".equals(event.type())
                        ? RunStartGateOutcome.admitted(event)
                        : RunStartGateOutcome.terminated(event))
                .defaultIfEmpty(RunStartGateOutcome.rejected());
    }

    private Mono<Void> requireCurrentOwnerRunning(RunExecutionClaim claim, String stage) {
        return Mono.fromCallable(() -> {
                    if (!chatRunLeaseService.isCurrentOwnerRunning(claim)) {
                        throw new ChatEventAppendRejectedException(
                                "run execution owner 已失效: runId="
                                        + (claim == null ? null : claim.runId()) + ", stage=" + stage);
                    }
                    return true;
                })
                .subscribeOn(eventIoScheduler)
                .then();
    }

    private void prepareInitialRouteAndBinding(InitialRoutePreparation preparation) {
        if (preparation.explicitDomainAgentId() != null) {
            RouteTarget route = RouteTarget.domainAgent(preparation.explicitDomainAgentId(), "front-selected", 1.0,
                    "front selected domain agent");
            RuntimeBinding binding = runtimeBindingService.bindDomainAgentForRun(new DomainAgentBindingCommand(
                    preparation.user().tenantId(), preparation.user().ownerUserId(), preparation.session().id(),
                    preparation.runId(), preparation.runtimeBindingLeafId(), preparation.explicitDomainAgentId(),
                    "front-selected",
                    domainAgentBindingMetadata(route, null,
                            preparation.command() == null ? Map.of() : preparation.command().metadata()),
                    preparation.agentMode()));
            preparation.routeRef().set(route);
            preparation.bindingRef().set(binding);
            preparation.runtimeSessionModeRef().set(RuntimeSessionMode.RESUME);
            return;
        }
        if (preparation.forceReroute()) {
            runtimeBindingService.cancelActive(
                    preparation.user().tenantId(), preparation.user().ownerUserId(), preparation.session().id());
            return;
        }
        runtimeBindingService.findActiveBySession(preparation.user().tenantId(), preparation.user().ownerUserId(),
                        preparation.session().id())
                .ifPresent(active -> {
                    boolean domainAgent = RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(active.provider());
                    RuntimeBinding binding = domainAgent
                            ? runtimeBindingService.touchDomainAgentForRun(
                                    active, preparation.runId(), preparation.agentMode())
                            : runtimeBindingService.touchForRun(active, preparation.runId());
                    RouteTarget route = domainAgent
                            ? RouteTarget.domainAgent(domainAgentId(binding), "runtime-binding", 1.0,
                            "active domain agent binding")
                            : RouteTarget.agentRuntime("runtime-binding", 1.0,
                            "active relay runtime binding");
                    preparation.bindingRef().set(binding);
                    preparation.routeRef().set(route);
                    preparation.runtimeSessionModeRef().set(RuntimeSessionMode.RESUME);
                });
    }

    private Flux<ChatEvent> routeAndExecute(RoutePipelineRequest request) {
        Flux<RouteSignalFrame> frames = requireCurrentOwnerRunning(request.executionClaim(), "before-route")
                .thenMany(Flux.defer(() -> request.routeRef().get() == null
                        ? routeSignalService.routeInitialWithProgress(new RouteSignalRequest(
                                request.runId(), request.user(), request.session(), request.runCommand(),
                                request.attachments(), request.memory(), request.intentQuery()))
                        : Flux.just(RouteSignalFrame.result(RouteSignalResult.of(request.routeRef().get())))));
        return frames.concatMap(frame -> {
            if (frame.eventFrame()) {
                return Flux.just(frame.event());
            }
            if (frame.progressFrame()) {
                return Flux.just(routeProgressEvent(request.runId(), request.session().id(), frame.progress()));
            }
            return requireCurrentOwnerRunning(request.executionClaim(), "after-route")
                    .thenMany(Flux.defer(() -> executeResolvedRoute(request, frame.result())));
        });
    }

    private Flux<ChatEvent> executeResolvedRoute(RoutePipelineRequest request, RouteSignalResult routeSignal) {
        DomainAgentRerouteState rerouteState = domainAgentRerouteState(request.runCommand());
        if (rerouteState != null) {
            return continueAfterClarifiedDomainAgentRefusal(request, routeSignal, rerouteState);
        }
        if (routeSignal != null && routeSignal.failRunOnIntentFailure()) {
            recordIntentSignal(request.user(), request.runCommand(), request.run().id(), routeSignal, null);
            foldRouteClarificationsWithoutDecision(request.user(), request.session().id());
            return Flux.error(new IntentRoutingFailedException(routeSignal.intentFailureReason()));
        }
        RouteExecutionResolution resolution = resolveRouteForRun(new RouteResolutionRequest(
                request.user(), request.session(), request.runCommand(), request.attachments(), request.memory(),
                request.runId(), request.runtimeBindingLeafId(), request.routeRef().get(),
                request.bindingRef().get(), request.runtimeSessionModeRef().get(), request.agentMode()), routeSignal);
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
            bestEffortBindIntentAgentProvider(request.runId());
            return intentClarificationWaitingBody(request.runId(), request.session().id(),
                    resolution.intentClarificationPayload());
        }
        String appliedRouteQuery = resolution.intent() == null
                ? request.routeMemoryQuery()
                : blankToDefault(request.intentRouteMemoryQuery(), request.routeMemoryQuery());
        MemoryContext runtimeMemory = recordAppliedRouteDecision(new AppliedRouteDecisionContext(
                request.user(), request.session().id(), request.runId(), appliedRouteQuery,
                resolution.intent(), resolution.route(), resolution.binding(), request.memory()));
        ChatCommand runtimeCommand = runtimeCommandForResolvedRoute(
                request, resolution.route(), resolution.intent());
        return requireCurrentOwnerRunning(request.executionClaim(), "before-runtime")
                .thenMany(Flux.defer(() -> switch (resolution.route().type()) {
                    case DOMAIN_AGENT -> executeDomainAgentWithReroute(new DomainAgentRunContext(
                            runtimeCommand, request.runId(), request.session(), runtimeMemory,
                            resolution.route(), request.user(), request.routeRef(), request.bindingRef(),
                            request.executionClaim(), request.forwardHeaders(), request.traceContext(),
                            resolution.intent(), request.documents(), new HashSet<>(), 0,
                            request.routeMemoryQuery()));
                    case SYSTEM_RESPONSE -> systemResponseExecutor.execute(runtimeCommand, request.runId(),
                            resolution.intent(), resolution.route());
                    case AGENT_RUNTIME -> agentRuntimeExecutor.execute(new RuntimeExecutionContext(
                            runtimeCommand, request.runId(), runtimeMemory, resolution.intent(),
                            resolution.route(), request.user(), request.bindingRef().get(),
                            resolution.runtimeSessionMode(), request.forwardHeaders(), request.documents(),
                            request.traceContext()));
                }));
    }

    private Flux<ChatEvent> continueAfterClarifiedDomainAgentRefusal(
            RoutePipelineRequest request,
            RouteSignalResult routeSignal,
            DomainAgentRerouteState state) {
        RuntimeBinding currentBinding = runtimeBindingService.loadDomainAgentForReroute(
                request.user().tenantId(), request.user().ownerUserId(), request.session().id(),
                state.currentBindingId(), state.currentTargetId());
        request.bindingRef().set(currentBinding);
        RouteTarget currentRoute = RouteTarget.domainAgent(
                state.currentTargetId(), state.currentRouteSource(), 1.0,
                "domain agent refusal clarification continuation");
        request.routeRef().set(currentRoute);
        ChatCommand runtimeCommand = withoutDomainAgentRerouteContext(
                runtimeCommandForResolvedRoute(request,
                        routeSignal == null ? null : routeSignal.route(),
                        routeSignal == null ? null : routeSignal.intentDecision()));
        DomainAgentRunContext context = new DomainAgentRunContext(
                runtimeCommand,
                request.runId(),
                request.session(),
                request.memory(),
                currentRoute,
                request.user(),
                request.routeRef(),
                request.bindingRef(),
                request.executionClaim(),
                request.forwardHeaders(),
                request.traceContext(),
                null,
                request.documents(),
                state.rejectedDomainAgentIds(),
                state.rerouteCount(),
                request.routeMemoryQuery());
        DomainAgentRerouteContext reroute = new DomainAgentRerouteContext(
                context, state.refusal(), state.currentTargetId(), state.rejectedDomainAgentIds(),
                blankToDefault(request.intentRouteMemoryQuery(), request.routeMemoryQuery()), request.agentMode());
        return continueAfterDomainAgentReroute(reroute, routeSignal);
    }

    private ChatCommand withoutDomainAgentRerouteContext(ChatCommand command) {
        if (command == null || command.metadata() == null
                || !command.metadata().containsKey(DOMAIN_AGENT_REROUTE_CONTEXT_METADATA)) {
            return command;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(command.metadata());
        metadata.remove(DOMAIN_AGENT_REROUTE_CONTEXT_METADATA);
        return new ChatCommand(command.commandId(), command.tenantId(), command.userId(), command.sessionId(),
                command.conversationId(), command.channel(), command.message(), command.attachments(), metadata,
                command.targetType(), command.targetId(), command.runMode(), command.parentMessageId(),
                command.editedMessageId(), command.regeneratedMessageId(), command.routeTrigger(),
                command.interactionId(), command.approved(), command.scope(), command.questionnaireAnswers(),
                command.appId(), command.appName(), command.agentMode());
    }

    private DomainAgentRerouteState domainAgentRerouteState(ChatCommand command) {
        Object value = command == null || command.metadata() == null
                ? null
                : command.metadata().get(DOMAIN_AGENT_REROUTE_CONTEXT_METADATA);
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> state = mapOrEmpty(raw);
        String currentTargetId = firstText(state.get("currentTargetId"));
        if (currentTargetId == null) {
            return null;
        }
        Set<String> rejected = new HashSet<>();
        Object rejectedValues = state.get("rejectedDomainAgentIds");
        if (rejectedValues instanceof Iterable<?> values) {
            for (Object item : values) {
                String id = firstText(item);
                if (id != null) {
                    rejected.add(id);
                }
            }
        }
        rejected.add(currentTargetId);
        int rerouteCount = intValue(state.get("rerouteCount"), 0);
        DomainAgentRefusal refusal = new DomainAgentRefusal(
                firstText(state.get("refusalCode")),
                firstText(state.get("refusalReasonCode")),
                booleanValue(state.get("refusalRecoverable")),
                firstText(state.get("refusalReason")),
                firstText(state.get("refusalAgentId")));
        return new DomainAgentRerouteState(
                currentTargetId,
                firstText(state.get("currentBindingId")),
                blankToDefault(firstText(state.get("currentRouteSource")), "intent-agent"),
                refusal,
                Set.copyOf(rejected),
                rerouteCount);
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    private ChatCommand runtimeCommand(ChatCommand command, String foldedQuery) {
        if (command == null || command.metadata() == null
                || !command.metadata().containsKey("intentClarification")
                || foldedQuery == null || foldedQuery.isBlank()) {
            return command;
        }
        return new ChatCommand(command.commandId(), command.tenantId(), command.userId(), command.sessionId(),
                command.conversationId(), command.channel(), foldedQuery, command.attachments(), command.metadata(),
                command.targetType(), command.targetId(), command.runMode(), command.parentMessageId(),
                command.editedMessageId(), command.regeneratedMessageId(), command.routeTrigger(),
                command.interactionId(), command.approved(), command.scope(), command.questionnaireAnswers(),
                command.appId(), command.appName(), command.agentMode());
    }

    private ChatCommand runtimeCommandForResolvedRoute(RoutePipelineRequest request,
                                                       RouteTarget resolvedRoute,
                                                       IntentDecision resolvedIntent) {
        ChatCommand command = runtimeCommand(request.runCommand(), request.routeMemoryQuery());
        return runtimeCommandWithIntentDocuments(command, request.documents(), resolvedRoute, resolvedIntent,
                request.runtimeMetadataOverride());
    }

    private ChatCommand runtimeCommandWithIntentDocuments(ChatCommand command,
                                                          List<UploadedDocument> documents,
                                                          RouteTarget resolvedRoute,
                                                          IntentDecision resolvedIntent,
                                                          Map<String, Object> metadataOverride) {
        if (command == null || !runtimeRoute(resolvedRoute)) {
            return command;
        }
        Map<String, Object> metadataSource = metadataOverride != null
                ? metadataOverride
                : resolvedIntent == null ? null : command.metadata();
        if (metadataSource == null) {
            return command;
        }
        Map<String, Object> runtimeMetadata = documentFacade.replaceRuntimeDocumentMetadata(
                metadataSource, documents);
        return new ChatCommand(command.commandId(), command.tenantId(), command.userId(), command.sessionId(),
                command.conversationId(), command.channel(), command.message(), command.attachments(), runtimeMetadata,
                command.targetType(), command.targetId(), command.runMode(), command.parentMessageId(),
                command.editedMessageId(), command.regeneratedMessageId(), command.routeTrigger(),
                command.interactionId(), command.approved(), command.scope(), command.questionnaireAnswers(),
                command.appId(), command.appName(), command.agentMode());
    }

    private boolean runtimeRoute(RouteTarget route) {
        return route != null && (route.type() == RouteType.DOMAIN_AGENT || route.type() == RouteType.AGENT_RUNTIME);
    }

    private void bestEffortBindResolvedRoute(String runId, RouteTarget route, RuntimeBinding binding) {
        try {
            chatRunService.bindResolvedRoute(runId, route, binding);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_WRITE_FAILED,
                            "ChatRun resolved route diagnostic update failed and was ignored")
                    .runId(runId)
                    .operation("chat-run.bind-resolved-route")
                    .attribute("routeType", route == null || route.type() == null ? null : route.type().name())
                    .attribute("agentCode", route == null ? null : route.selectedAgentCode())
                    .build(), ex);
        }
    }

    private void bestEffortBindIntentAgentProvider(String runId) {
        try {
            chatRunService.bindRuntimeProvider(runId, "intent-agent");
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_WRITE_FAILED,
                            "ChatRun intent-agent diagnostic update failed and was ignored")
                    .runId(runId)
                    .operation("chat-run.bind-intent-provider")
                    .build(), ex);
        }
    }

    private MemoryContext recordAppliedRouteDecision(AppliedRouteDecisionContext decision) {
        MemoryContext currentMemory = decision.memory() == null ? MemoryContext.empty() : decision.memory();
        if (routeMemoryService == null || decision.binding() == null
                || !routeMemoryService.isNewRouteDecision(decision.route())) {
            return currentMemory;
        }
        try {
            IntentDecision routeMemoryIntent = normalizedRouteMemoryIntent(decision.intent(), decision.route());
            RouteMemoryApplicationService.RouteMemoryRouteCommand command =
                    new RouteMemoryApplicationService.RouteMemoryRouteCommand(
                            decision.user(), decision.sessionId(), decision.runId(),
                            blankToDefault(decision.query(), ""), routeMemoryIntent, decision.route());
            routeMemoryService.recordRouteDecision(command);
            Map<String, Object> historyItem = routeMemoryService.routeHistory(command);
            return appendInlineRouteHistory(currentMemory, historyItem);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.TASK_REJECTED,
                            "RouteMemory route decision scheduling failed and was ignored")
                    .runId(decision.runId())
                    .sessionId(decision.sessionId())
                    .operation("route-memory.schedule")
                    .attribute("routeType", decision.route() == null || decision.route().type() == null
                            ? null : decision.route().type().name())
                    .attribute("agentCode", decision.route() == null
                            ? null : decision.route().selectedAgentCode())
                    .build(), ex);
            return currentMemory;
        }
    }

    private IntentDecision normalizedRouteMemoryIntent(IntentDecision intent, RouteTarget route) {
        if (route == null || route.type() != RouteType.AGENT_RUNTIME) {
            return intent;
        }
        String routeAction = firstText(
                intent == null || intent.slots() == null ? null : intent.slots().get("routeAction"),
                intent == null || intent.raw() == null ? null : intent.raw().get("routeAction"),
                "NO_MATCH");
        Map<String, Object> slots = intent == null || intent.slots() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(intent.slots());
        slots.put("routeAction", routeAction);
        return new IntentDecision("relay", "no_match", TaskComplexity.COMPLEX, 0.0,
                false, null, slots, List.of(),
                Map.of("targetProvider", "relay", "routeAction", routeAction));
    }

    private MemoryContext appendInlineRouteHistory(MemoryContext memory, Map<String, Object> historyItem) {
        if (historyItem == null || historyItem.isEmpty()) {
            return memory;
        }
        RouteMemoryContext current = memory.routeMemory() == null
                ? RouteMemoryContext.empty()
                : memory.routeMemory();
        List<Map<String, Object>> history = new java.util.ArrayList<>(current.history());
        if (!history.contains(historyItem)) {
            history.add(historyItem);
        }
        return memory.withRouteMemory(new RouteMemoryContext(
                current.routeTrigger(), history, current.lastIntentRejectReason()));
    }

    private IntentDecision routeSwitchIntent(ChatInteractionRequest interaction, RouteTarget route) {
        Map<String, Object> requestPayload = interaction == null || interaction.requestPayload() == null
                ? Map.of()
                : interaction.requestPayload();
        if (route != null && route.type() == RouteType.AGENT_RUNTIME) {
            String routeAction = blankToDefault(firstText(requestPayload.get("routeAction")), "NO_MATCH");
            return new IntentDecision("relay", "no_match", TaskComplexity.COMPLEX, 1.0,
                    false, null, Map.of("routeAction", routeAction), List.of(),
                    Map.of("targetProvider", "relay", "routeAction", routeAction));
        }
        String domainAgentId = route == null ? null : route.selectedAgentCode();
        String intentCode = firstText(requestPayload.get("candidateIntentCode"), domainAgentId);
        String intentName = firstText(requestPayload.get("candidateIntentName"), domainAgentId);
        return new IntentDecision(intentCode, intentName, TaskComplexity.SIMPLE, 1.0,
                true, domainAgentId,
                Map.of("routeAction", "ROUTE_SINGLE", "accessName", blankToDefault(domainAgentId, "")),
                List.of(), Map.of());
    }

    private RouteTarget routeSwitchTarget(String provider, String targetId, String routeSource) {
        if (RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(provider)) {
            if (targetId == null || targetId.isBlank()) {
                throw new IllegalArgumentException("切换到 DomainAgent 时 candidateTargetId 不能为空");
            }
            return RouteTarget.domainAgent(targetId, routeSource, 1.0, "confirmed route switch");
        }
        if (RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER.equals(provider)) {
            return RouteTarget.agentRuntime(routeSource, 1.0, "confirmed route switch to relay");
        }
        throw new IllegalArgumentException("不支持的候选 Runtime provider: " + provider);
    }

    private String routeSourceFromInteraction(ChatInteractionRequest interaction) {
        return blankToDefault(firstText(interaction == null ? null
                : interaction.requestPayload().get("currentRouteSource")), "front-selected");
    }

    private void foldRouteClarificationsWithoutDecision(UserContext user, String sessionId) {
        if (routeMemoryService != null) {
            routeMemoryService.completeWithoutRoute(user, sessionId);
        }
    }

    private void recordIntentSignal(UserContext user, ChatCommand command, String runId,
                                    RouteSignalResult signal, RouteTarget route) {
        if (signal == null || signal.intentDecision() == null) {
            return;
        }
        intentRecognitionRecordService.recordAsync(IntentRecognitionRecordSnapshot.of(
                new IntentRecognitionRecordSnapshot.IntentRecognitionRecordInput(
                        user,
                        command,
                        runId,
                        signal.intentDecision(),
                        route,
                        signal.intentConfidenceThreshold() == null ? 0.0 : signal.intentConfidenceThreshold(),
                        signal.intentLatencyMs())));
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
                        domainAgentBindingMetadata(route, intent), request.agentMode()));
            } else if (!waitingIntentClarification && route.type() == RouteType.AGENT_RUNTIME) {
                RuntimeBindingResolution resolution = runtimeBindingService.resolveForRun(
                        user.tenantId(), user.ownerUserId(), session.id(), runId, runtimeBindingLeafId);
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
        ChatInteractionRequest waitingRequest = waitingRequest(event, completionTarget, context);
        ChatEvent eventToPersist = waitingRequest == null
                ? withCompletionFeedbackPayload(event, completionTarget)
                : withWaitingUserPayload(event, completionTarget, waitingRequest);
        if (terminalCommitService != null && waitingRequest != null) {
            return commitWaitingUser(eventToPersist, context, completionTarget, waitingRequest);
        }
        if (terminalCommitService != null && ownerRunTerminal(eventToPersist)) {
            if ("run.completed".equals(eventToPersist.type()) && completionTarget.messageReady()) {
                return commitCompleted(eventToPersist, context, completionTarget);
            }
            return commitTerminalOnly(eventToPersist, context);
        }
        /*
         * 只有 DB guarded insert 成功后，事件才算事实成立。assistant 文本累积、
         * 历史消息写入、run 状态推进和 Redis/WebSocket 发布都以该持久化结果为准，
         * 避免 stop/watchdog 后的迟到 delta 进入用户可见历史。
         */
        ChatEvent stored = chatStreamService.appendWithExecutionGuard(eventToPersist, context.executionClaim());
        assistant.observe(stored);
        cancelPersistedAutomaticDomainAgentBinding(stored, context);
        rememberPendingInteractionRequest(stored, context);
        /*
         * run.completed 是前端、Event Resume 和跨设备续接共同认可的“本轮回答已经闭合”信号。
         * 因此在发布该终态事件之前，必须先把完整 assistant 消息写入历史消息树，
         * 避免客户端收到 completed 后立即查询历史时只能看到 user 节点。
         */
        if ("run.completed".equals(stored.type()) && completionTarget.messageReady()) {
            ChatMessage savedAssistant = context.continuationInteractionRequest() == null
                    || InteractionMessageStrategy.newTurn(context.continuationInteractionRequest())
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
                            context.continuationInteractionRequest().assistantMessageId(),
                            assistant.finalContent(),
                            runId,
                            assistant.parts(),
                            null
            ));
            sessionService.advanceLatestMessageSeq(user, session, stored.sequence());
            chatRunService.bindAssistantMessage(runId, savedAssistant.id());
            bindingRef.set(completeBindingAfterRunCompleted(bindingRef.get(), runId, savedAssistant.id()));
            if (context.continuationInteractionRequest() != null
                    && !InteractionMessageStrategy.newTurn(context.continuationInteractionRequest())) {
                chatInteractionService.markAnswered(context.continuationInteractionRequest());
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
                    completionTarget.assistantMessageId(),
                    waitingRequest.interactionType() != ChatInteractionType.ROUTE_SWITCH_CONFIRMATION
            ));
            sessionService.advanceLatestMessageSeq(user, session, stored.sequence());
            chatRunService.bindAssistantMessage(runId, savedAssistant.id());
            bindingRef.set(runtimeBindingService.touchAndMoveToLeaf(bindingRef.get(), runId, savedAssistant.id()));
            chatInteractionService.saveInteraction(waitingRequest);
            if (context.continuationInteractionRequest() != null
                    && !InteractionMessageStrategy.newTurn(context.continuationInteractionRequest())) {
                chatInteractionService.markAnswered(context.continuationInteractionRequest());
            }
        }
        // 事件已经带有数据库持久化 seq，实时输出与断线补发看到的是同一份顺序。
        chatRunService.observeEvent(stored);
        if (context.continuationInteractionRequest() != null
                && !InteractionMessageStrategy.newTurn(context.continuationInteractionRequest())
                && ("run.failed".equals(stored.type())
                || "run.cancelled".equals(stored.type()))) {
            chatInteractionService.markWaiting(context.continuationInteractionRequest());
        }
        if ("run.failed".equals(stored.type()) && runtimeSessionUnavailable(stored.payload())) {
            bindingRef.set(runtimeBindingService.markNotRoutable(bindingRef.get(),
                    "RUNTIME_SESSION_UNAVAILABLE"));
        }
        markExecutionTerminalIfNeeded(stored);
        bindingRef.set(runtimeBindingService.observeEvent(bindingRef.get(), stored));
        chatStreamService.publishPersisted(stored);
        recordRouteMemoryAfterCommitted(stored, context);
        acknowledgePersistence(event);
        return stored;
    }

    private void cancelPersistedAutomaticDomainAgentBinding(ChatEvent stored,
                                                            RunEventPipelineContext context) {
        DomainAgentRefusal refusal = domainAgentRefusal(stored);
        if (refusal == null) {
            return;
        }
        RuntimeBinding current = context.bindingRef().get();
        context.bindingRef().set(markRejectedAutomaticBindingNotRoutable(current, refusal));
    }

    private AutomaticDomainAgentRefusalCommit automaticDomainAgentRefusalCommit(
            ChatEvent event,
            RunEventPipelineContext context) {
        DomainAgentRefusal refusal = domainAgentRefusal(event);
        RuntimeBinding binding = context == null ? null : context.bindingRef().get();
        if (refusal == null || binding == null
                || !RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(binding.provider())
                || binding.status() != RuntimeBindingStatus.ACTIVE
                || requiresRouteSwitchConfirmation(routeSource(binding))) {
            return null;
        }
        return new AutomaticDomainAgentRefusalCommit(binding, refusal);
    }

    private ChatEvent publishCommittedDomainAgentRefusal(
            ChatEvent sourceEvent,
            ChatRunTerminalCommitService.CommitResult result,
            RunEventPipelineContext context) {
        ChatEvent stored = result.event();
        RuntimeBinding binding = result.binding();
        context.bindingRef().set(binding);
        context.assistant().observe(stored);
        rememberPendingInteractionRequest(stored, context);
        chatRunService.observeEvent(stored);
        markExecutionTerminalIfNeeded(stored);
        binding = runtimeBindingService.observeEvent(binding, stored);
        context.bindingRef().set(binding);
        chatStreamService.publishPersisted(stored);
        recordRouteMemoryAfterCommitted(stored, context);
        acknowledgePersistence(sourceEvent);
        return stored;
    }

    private void scheduleRuntimeBindingCacheSync(RuntimeBinding binding) {
        if (binding == null) {
            return;
        }
        try {
            domainAgentControlIoScheduler.schedule(() -> {
                try {
                    runtimeBindingService.synchronizeCache(binding);
                } catch (RuntimeException ex) {
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_CACHE_SYNC_FAILED,
                                    "RuntimeBinding cache sync failed after database commit")
                            .sessionId(binding.chatSessionId())
                            .operation("runtime-binding.cache-sync")
                            .attribute("bindingId", binding.id())
                            .build(), ex);
                }
            });
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.TASK_REJECTED,
                            "RuntimeBinding cache sync task was rejected after database commit")
                    .sessionId(binding.chatSessionId())
                    .operation("runtime-binding.cache-sync-schedule")
                    .attribute("bindingId", binding.id())
                    .build(), ex);
        }
    }

    private void acknowledgePersistence(ChatEvent event) {
        if (event instanceof PersistenceAcknowledgedEvent acknowledged) {
            acknowledged.persisted().tryEmitEmpty();
        }
    }

    private void rejectPersistenceAcknowledgement(ChatEvent event, Throwable failure) {
        if (event instanceof PersistenceAcknowledgedEvent acknowledged) {
            acknowledged.persisted().tryEmitError(failure == null
                    ? new ChatEventAppendRejectedException("拒答控制事件未持久化")
                    : failure);
        }
    }

    private boolean ownerRunTerminal(ChatEvent event) {
        return event != null && ("run.completed".equals(event.type())
                || "run.waiting_user".equals(event.type())
                || "run.failed".equals(event.type())
                || "run.cancelled".equals(event.type()));
    }

    private ChatEvent commitWaitingUser(ChatEvent eventToPersist, RunEventPipelineContext context,
                                        CompletionMessageTarget completionTarget, ChatInteractionRequest waitingRequest) {
        try {
            ChatRunTerminalCommitService.CommitResult result = terminalCommitService.commitWaitingUser(
                    new ChatRunTerminalCommitService.WaitingUserCommitCommand(
                            eventToPersist,
                            terminalCommitContext(context),
                            terminalMessageTarget(completionTarget),
                            waitingRequest
                    ));
            return publishCommitted(result, context);
        } catch (ChatEventAppendRejectedException ex) {
            throw ex;
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
        } catch (ChatEventAppendRejectedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            return commitTerminalFailure(context, ex);
        }
    }

    private RuntimeBinding completeBindingAfterRunCompleted(RuntimeBinding binding, String runId,
                                                            String assistantMessageId) {
        return runtimeBindingService.completeAfterRun(binding, runId, assistantMessageId);
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
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_TRANSACTION_FAILED,
                        "Chat run terminal processing failed; falling back to run.failed")
                .runId(context.runId())
                .sessionId(context.session().id())
                .operation("chat-run.terminal-commit")
                .build(), ex);
        ChatEvent failed = runtimeErrorEvent(context.runId(), context.session().id(), ex);
        return commitTerminalOnly(failed, context);
    }

    private ChatEvent publishCommitted(ChatRunTerminalCommitService.CommitResult result,
                                       RunEventPipelineContext context) {
        context.bindingRef().set(result.binding());
        runtimeBindingService.synchronizeCache(result.binding());
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
        }
    }

    private void recordIntentClarificationAfterWaiting(ChatEvent stored, RunEventPipelineContext context) {
        Map<String, Object> payload = stored.payload() == null ? Map.of() : stored.payload();
        if (!ChatInteractionType.INTENT_CLARIFICATION.name().equals(String.valueOf(payload.get("interactionType")))) {
            return;
        }
        Map<String, Object> requestPayload = context.pendingInteractionPayloadRef().get();
        if (requestPayload == null || requestPayload.isEmpty()) {
            requestPayload = payload;
        }
        Object interactionId = payload.get("interactionId");
        routeMemoryService.appendClarification(context.user(), context.session().id(), stored.runId(),
                interactionId == null ? null : String.valueOf(interactionId), requestPayload);
    }

    private String routeMemoryQuery(ChatRunMessagePlan messagePlan, ChatInteractionRequest interaction) {
        return routeMemoryQuery(messagePlan, interaction, null);
    }

    private String routeMemoryQuery(ChatRunMessagePlan messagePlan, ChatInteractionRequest interaction,
                                    String intentAnswer) {
        if (messagePlan == null || messagePlan.userMessage() == null) {
            return "";
        }
        if (interaction != null && interaction.interactionType() == ChatInteractionType.INTENT_CLARIFICATION) {
            String folded = foldedIntentClarificationQuery(
                    interaction, messagePlan.userMessage().content(), intentAnswer);
            if (folded != null && !folded.isBlank()) {
                return folded;
            }
        }
        String content = messagePlan.userMessage().content();
        return content == null ? "" : content;
    }

    private String foldedIntentClarificationQuery(ChatInteractionRequest interaction, String fallbackAnswer,
                                                  String intentAnswer) {
        Map<String, Object> payload = interaction.requestPayload() == null ? Map.of() : interaction.requestPayload();
        String originalQuery = firstText(payload.get("originalQuery"), fallbackAnswer);
        List<Map<String, Object>> history = new java.util.ArrayList<>(clarificationHistory(payload));
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("type", "clarify");
        putNonNull(current, "query", firstText(payload.get("clarifyTriggerQuery"), payload.get("originalQuery"), originalQuery));
        putNonNull(current, "clarifyQuestion", clarifyQuestion(payload));
        putNonNull(current, "clarificationType", clarificationType(payload));
        Map<String, Object> responsePayload = interaction.responsePayload() == null ? Map.of() : interaction.responsePayload();
        String answer = firstText(intentAnswer, responsePayload.get("answerText"), fallbackAnswer);
        putNonNull(current, "answer", answer);
        if (current.size() > 1) {
            history.add(Map.copyOf(current));
        }
        StringBuilder builder = new StringBuilder();
        builder.append("用户:").append(originalQuery == null ? "" : originalQuery);
        for (Map<String, Object> item : history) {
            String question = firstText(item.get("clarifyQuestion"), item.get("question"));
            String itemAnswer = firstText(item.get("answer"), item.get("answerText"));
            if (question != null) {
                builder.append("；系统追问:").append(question);
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
                context.continuationInteractionRequest()
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
        if (context.continuationInteractionRequest() != null
                && !InteractionMessageStrategy.newTurn(context.continuationInteractionRequest())) {
            // Interaction 续接复用等待态 assistant，即使 Relay 只返回终态也要把同一条消息标记为可反馈。
            return CompletionMessageTarget.ready(context.continuationInteractionRequest().assistantMessageId());
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

    private ChatInteractionRequest waitingRequest(ChatEvent event, CompletionMessageTarget target,
                                           RunEventPipelineContext context) {
        if (chatInteractionService == null || event == null || !"run.completed".equals(event.type())) {
            return null;
        }
        Map<String, Object> requestPayload = context.pendingInteractionPayloadRef().get();
        if (requestPayload == null) {
            return null;
        }
        if (!target.messageReady()) {
            return null;
        }
        boolean intentClarification = ChatInteractionType.INTENT_CLARIFICATION.name()
                .equals(String.valueOf(requestPayload.get("interactionType")));
        Map<String, Object> interactionRequestPayload = requestPayload;
        if (intentClarification) {
            Map<String, Object> internalPayload = new LinkedHashMap<>(requestPayload);
            internalPayload.put(INTENT_CLARIFICATION_DOCUMENT_IDS,
                    context.intentClarificationDocumentIds());
            interactionRequestPayload = ChatPayloadMaps.immutableCopy(internalPayload);
        }
        RuntimeBinding binding = context.bindingRef().get();
        if (!intentClarification && (binding == null || binding.id() == null || binding.id().isBlank())) {
            throw new IllegalStateException("Interaction 等待态缺少 RuntimeBinding，无法续接 Runtime");
        }
        String runtimeProvider = intentClarification ? "intent-agent" : binding.provider();
        String runtimeSessionId = runtimeSessionId(requestPayload, binding);
        return chatInteractionService.prepareInteraction(new ChatInteractionCreateContext(
                context.user(),
                context.session(),
                context.runId(),
                context.messagePlan().userMessage(),
                target.assistantMessageId(),
                runtimeProvider,
                intentClarification || binding == null ? null : binding.id(),
                runtimeSessionId,
                interactionRequestPayload
        ));
    }

    private List<String> documentIds(List<UploadedDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
        for (UploadedDocument document : documents) {
            if (document != null && document.id() != null && !document.id().isBlank()) {
                ids.putIfAbsent(document.id(), Boolean.TRUE);
            }
        }
        return List.copyOf(ids.keySet());
    }

    private List<String> intentClarificationDocumentIds(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get(INTENT_CLARIFICATION_DOCUMENT_IDS);
        if (!(value instanceof Iterable<?> values)) {
            return List.of();
        }
        LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
        for (Object item : values) {
            if (item != null && !String.valueOf(item).isBlank()) {
                ids.putIfAbsent(String.valueOf(item).trim(), Boolean.TRUE);
            }
        }
        return List.copyOf(ids.keySet());
    }

    private Map<String, Object> withoutIntentClarificationDocumentIds(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()
                || !payload.containsKey(INTENT_CLARIFICATION_DOCUMENT_IDS)) {
            return payload == null ? Map.of() : payload;
        }
        Map<String, Object> copy = new LinkedHashMap<>(payload);
        copy.remove(INTENT_CLARIFICATION_DOCUMENT_IDS);
        return ChatPayloadMaps.immutableCopy(copy);
    }

    private ChatEvent withWaitingUserPayload(ChatEvent event, CompletionMessageTarget target,
                                             ChatInteractionRequest waitingRequest) {
        Map<String, Object> payload = new LinkedHashMap<>(event.payload() == null ? Map.of() : event.payload());
        payload.put("status", "WAITING_USER");
        payload.put("interactionType", waitingRequest.interactionType().name());
        payload.put("interactionId", waitingRequest.id());
        payload.put("messageReady", target.messageReady());
        payload.put("assistantMessageId", target.assistantMessageId());
        payload.put("feedbackTargetMessageId", target.assistantMessageId());
        if (waitingRequest.expiresAt() != null) {
            payload.put("expiresAt", waitingRequest.expiresAt().toString());
        }
        copyIfPresent(waitingRequest.requestPayload(), payload,
                "currentProvider", "currentTargetId", "currentRouteSource",
                "candidateProvider", "candidateTargetId", "candidateRouteSource",
                "candidateIntentCode", "candidateIntentName", "routeAction",
                "refusalCode", "refusalReasonCode", "refusalRecoverable", "refusalReason",
                "intentSessionId", "intentRequestId", "originalQuery");
        return new RunWaitingUserEvent(event.runId(), event.sessionId(), event.sequence(),
                event.createdAt(), ChatPayloadMaps.immutableCopy(payload));
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

    private void rememberPendingInteractionRequest(ChatEvent stored, RunEventPipelineContext context) {
        if (!questionnaireApprovalRequest(stored) && !intentClarificationRequest(stored)
                && !routeSwitchConfirmationRequest(stored)) {
            return;
        }
        RuntimeBinding binding = context.bindingRef().get();
        String runtimeProvider = binding == null ? null : binding.provider();
        if (!routeSwitchConfirmationRequest(stored) && !intentClarificationRequest(stored)
                && !agentRuntimeExecutor.supportsWaitingUserResponse(runtimeProvider)) {
            return;
        }
        context.pendingInteractionPayloadRef().compareAndSet(null,
                ChatPayloadMaps.immutableCopy(stored.payload()));
    }

    private boolean questionnaireApprovalRequest(ChatEvent event) {
        if (event == null || !"runtime.card".equals(event.type()) || event.payload() == null) {
            return false;
        }
        return "approval-request".equals(String.valueOf(event.payload().get("sourceType")))
                && "questionnaire".equalsIgnoreCase(String.valueOf(event.payload().get("operation_type")));
    }

    private boolean routeSwitchConfirmationRequest(ChatEvent event) {
        if (event == null || !"runtime.card".equals(event.type()) || event.payload() == null) {
            return false;
        }
        return "route-switch-confirmation-request".equals(String.valueOf(event.payload().get("sourceType")));
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

    private RuntimeEvent clarificationResponseEvent(String runId, String sessionId, ChatInteractionRequest interaction,
                                                    Map<String, Object> responsePayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", interaction.interactionType() == ChatInteractionType.INTENT_CLARIFICATION
                ? "intent-clarification-response"
                : "clarification-response");
        payload.put("interactionId", interaction.id());
        payload.put("interactionType", interaction.interactionType().name());
        putIfNotNull(payload, "approval_id", interaction.approvalId());
        putIfNotNull(payload, "approved", responsePayload.get("approved"));
        putIfNotNull(payload, "scope", responsePayload.get("scope"));
        payload.put("questionnaireAnswers", mapOrEmpty(responsePayload.get("questionnaireAnswers")));
        putIfNotNull(payload, "answerText", responsePayload.get("answerText"));
        payload.put("metadata", mapOrEmpty(responsePayload.get("metadata")));
        return RuntimeEvent.card(runId, sessionId, Map.copyOf(payload));
    }

    private RuntimeEvent routeSwitchResponseEvent(String runId, String sessionId, ChatInteractionRequest interaction,
                                                  Map<String, Object> responsePayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "route-switch-confirmation-response");
        payload.put("interactionId", interaction.id());
        payload.put("interactionType", interaction.interactionType().name());
        putIfNotNull(payload, "approved", responsePayload.get("approved"));
        putIfNotNull(payload, "currentProvider", interaction.requestPayload().get("currentProvider"));
        putIfNotNull(payload, "currentTargetId", interaction.requestPayload().get("currentTargetId"));
        putIfNotNull(payload, "currentRouteSource", interaction.requestPayload().get("currentRouteSource"));
        putIfNotNull(payload, "candidateProvider", interaction.requestPayload().get("candidateProvider"));
        putIfNotNull(payload, "candidateTargetId", interaction.requestPayload().get("candidateTargetId"));
        putIfNotNull(payload, "candidateIntentName", interaction.requestPayload().get("candidateIntentName"));
        payload.put("metadata", mapOrEmpty(responsePayload.get("metadata")));
        return RuntimeEvent.card(runId, sessionId, Map.copyOf(payload));
    }

    private RuntimeEvent routeSwitchDeclinedEvent(String runId, String sessionId, ChatInteractionRequest interaction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "route-switch-declined");
        payload.put("interactionId", interaction.id());
        payload.put("message", "已保留当前领域 Agent，本轮不切换处理能力。");
        putIfNotNull(payload, "currentProvider", interaction.requestPayload().get("currentProvider"));
        putIfNotNull(payload, "currentTargetId", interaction.requestPayload().get("currentTargetId"));
        putIfNotNull(payload, "candidateProvider", interaction.requestPayload().get("candidateProvider"));
        putIfNotNull(payload, "candidateTargetId", interaction.requestPayload().get("candidateTargetId"));
        putIfNotNull(payload, "refusalCode", interaction.requestPayload().get("refusalCode"));
        putIfNotNull(payload, "refusalReason", interaction.requestPayload().get("refusalReason"));
        return RuntimeEvent.card(runId, sessionId, Map.copyOf(payload));
    }

    private RuntimeEvent routeSwitchAppliedEvent(String runId, String sessionId,
                                                 ChatInteractionRequest interaction,
                                                 RouteTarget route,
                                                 RuntimeBinding binding) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "route-switch-applied");
        payload.put("metadataType", "route_switch_applied");
        payload.put("interactionId", interaction.id());
        payload.put("targetProvider", binding == null ? null : binding.provider());
        putIfNotNull(payload, "targetId", firstText(
                route == null ? null : route.selectedAgentCode(),
                interaction.requestPayload().get("candidateTargetId")));
        putIfNotNull(payload, "routeSource", route == null ? null : route.routeSource());
        return RuntimeEvent.metadata(runId, sessionId, ChatPayloadMaps.immutableCopy(payload));
    }

    private void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private Map<String, Object> mapOrEmpty(Object value) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return Map.copyOf(copy);
        }
        return Map.of();
    }

    private Map<String, Object> routeSwitchBindingMetadata(ChatInteractionRequest interaction) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfNotNull(metadata, "domainAgentId", interaction.requestPayload().get("candidateTargetId"));
        metadata.put("routeSource", "user-confirmed");
        putIfNotNull(metadata, "intentCode", interaction.requestPayload().get("candidateIntentCode"));
        putIfNotNull(metadata, "intentName", interaction.requestPayload().get("candidateIntentName"));
        putIfNotNull(metadata, "confirmedFromDomainAgentId", interaction.requestPayload().get("currentTargetId"));
        metadata.put("confirmedInteractionId", interaction.id());
        return Map.copyOf(metadata);
    }

    private record RunEventPipelineContext(
            UserContext user,
            ChatSession session,
            ChatRunMessagePlan messagePlan,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AssistantAssembly assistant,
            String runId,
            RunExecutionClaim executionClaim,
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef,
            ChatInteractionRequest continuationInteractionRequest,
            RunStartAttempt startAttempt,
            List<String> intentClarificationDocumentIds
    ) {
        private RunEventPipelineContext {
            intentClarificationDocumentIds = intentClarificationDocumentIds == null
                    ? List.of()
                    : List.copyOf(intentClarificationDocumentIds);
        }
    }

    private record InteractionContinuationOptions(
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            RunStartAttempt startAttempt,
            ChatSession session,
            AgentModeProfile agentMode
    ) {
        private InteractionContinuationOptions {
            traceContext = traceContext == null ? TraceContext.empty() : traceContext;
        }

        private InteractionContinuationOptions withSession(ChatSession value) {
            return new InteractionContinuationOptions(forwardHeaders, traceContext, startAttempt, value, agentMode);
        }
    }

    private enum RunStartGateStatus {
        ADMITTED,
        REJECTED,
        TERMINATED
    }

    private record RunStartGateOutcome(RunStartGateStatus status, ChatEvent event) {
        private static RunStartGateOutcome admitted(ChatEvent event) {
            return new RunStartGateOutcome(RunStartGateStatus.ADMITTED, event);
        }

        private static RunStartGateOutcome rejected() {
            return new RunStartGateOutcome(RunStartGateStatus.REJECTED, null);
        }

        private static RunStartGateOutcome terminated(ChatEvent event) {
            return new RunStartGateOutcome(RunStartGateStatus.TERMINATED, event);
        }
    }

    private record InitialRoutePreparation(
            UserContext user,
            ChatSession session,
            String runId,
            String runtimeBindingLeafId,
            ChatCommand command,
            String explicitDomainAgentId,
            boolean forceReroute,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AtomicReference<RuntimeSessionMode> runtimeSessionModeRef,
            AgentModeProfile agentMode
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

    private record AppliedRouteDecisionContext(
            UserContext user,
            String sessionId,
            String runId,
            String query,
            IntentDecision intent,
            RouteTarget route,
            RuntimeBinding binding,
            MemoryContext memory
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
            TraceContext traceContext,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AtomicReference<RuntimeSessionMode> runtimeSessionModeRef,
            RunExecutionClaim executionClaim,
            ChatRun run,
            String routeMemoryQuery,
            String intentQuery,
            String intentRouteMemoryQuery,
            Map<String, Object> runtimeMetadataOverride,
            AgentModeProfile agentMode
    ) {
    }

    private record IntentClarificationContinuationInput(
            String messageText,
            String intentQuery,
            List<AttachmentRef> currentAttachments,
            List<AttachmentRef> cumulativeAttachments,
            List<UploadedDocument> cumulativeDocuments,
            List<String> cumulativeDocumentIds,
            Map<String, Object> runtimeMetadata,
            AgentModeProfile agentMode
    ) {
        private IntentClarificationContinuationInput {
            messageText = messageText == null ? "" : messageText;
            intentQuery = intentQuery == null ? "" : intentQuery;
            currentAttachments = currentAttachments == null ? List.of() : List.copyOf(currentAttachments);
            cumulativeAttachments = cumulativeAttachments == null ? List.of() : List.copyOf(cumulativeAttachments);
            cumulativeDocuments = cumulativeDocuments == null ? List.of() : List.copyOf(cumulativeDocuments);
            cumulativeDocumentIds = cumulativeDocumentIds == null ? List.of() : List.copyOf(cumulativeDocumentIds);
            runtimeMetadata = runtimeMetadata == null ? Map.of() : ChatPayloadMaps.immutableCopy(runtimeMetadata);
        }
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
            RuntimeSessionMode currentRuntimeSessionMode,
            AgentModeProfile agentMode
    ) {
    }

    private record DomainAgentRunContext(
            ChatCommand command,
            String runId,
            ChatSession session,
            MemoryContext memory,
            RouteTarget route,
            UserContext user,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            RunExecutionClaim executionClaim,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            IntentDecision intentDecision,
            List<UploadedDocument> documents,
            Set<String> rejectedDomainAgentIds,
            int rerouteCount,
            String routeMemoryQuery
    ) {
    }

    private record DomainAgentRerouteContext(
            DomainAgentRunContext context,
            DomainAgentRefusal refusal,
            String currentDomainAgentId,
            Set<String> rejectedDomainAgentIds,
            String intentQuery,
            AgentModeProfile agentMode
    ) {
    }

    private record DomainAgentRerouteState(
            String currentTargetId,
            String currentBindingId,
            String currentRouteSource,
            DomainAgentRefusal refusal,
            Set<String> rejectedDomainAgentIds,
            int rerouteCount
    ) {
    }

    private record DomainAgentRefusal(
            String code,
            String reasonCode,
            Boolean recoverable,
            String message,
            String agentId
    ) {
    }

    private record AutomaticDomainAgentRefusalCommit(
            RuntimeBinding binding,
            DomainAgentRefusal refusal
    ) {
    }

    private record CommittedEventBatch(
            List<ChatEvent> events,
            CommittedBatchPostProcessingException failure
    ) {
        private CommittedEventBatch {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    private static final class CommittedBatchPostProcessingException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private CommittedBatchPostProcessingException(ChatEvent event, String operation, RuntimeException cause) {
            super("聊天事件批次已落库，但提交后处理失败: runId=" + event.runId()
                    + ", sequence=" + event.sequence()
                    + ", type=" + event.type()
                    + ", operation=" + operation, cause);
        }
    }

    private record PersistenceAcknowledgedEvent(
            ChatEvent delegate,
            Sinks.One<Void> persisted
    ) implements ChatEvent {
        @Override
        public String runId() {
            return delegate.runId();
        }

        @Override
        public String sessionId() {
            return delegate.sessionId();
        }

        @Override
        public long sequence() {
            return delegate.sequence();
        }

        @Override
        public String type() {
            return delegate.type();
        }

        @Override
        public Instant createdAt() {
            return delegate.createdAt();
        }

        @Override
        public Map<String, Object> payload() {
            return delegate.payload();
        }
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
            super((com.huawei.it.ex.one.application.integration.agent.AgentRuntime) null,
                    new com.huawei.it.ex.one.application.service.runtime.WorkloadConcurrencyLimiter(
                            new com.huawei.it.ex.one.application.config.ResourceIsolationProperties()));
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
        public Flux<ChatEvent> continueWithUserResponse(RuntimeInteractionResponseContext context) {
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
                normalized.editedMessageId(), normalized.regeneratedMessageId(), normalized.routeTrigger(),
                normalized.interactionId(), normalized.approved(), normalized.scope(),
                normalized.questionnaireAnswers(), normalized.appId(), normalized.appName(),
                normalized.agentMode());
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
            case CONTINUE_INTERACTION ->
                    throw new IllegalArgumentException("CONTINUE_INTERACTION 不参与 RuntimeBinding leaf 计算");
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

    private TraceContext normalizeTraceContext(TraceContext traceContext) {
        return traceContext == null ? TraceContext.empty() : traceContext;
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
        IntentRoutingFailedException intentFailure = findCause(ex, IntentRoutingFailedException.class);
        if (intentFailure != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", IntentRoutingFailedException.CODE);
            payload.put("message", IntentRoutingFailedException.USER_MESSAGE);
            payload.put("source", "intent-agent");
            payload.put("failureStrategy", "FAIL_RUN");
            payload.put("suggestedAction", "SELECT_DOMAIN_AGENT");
            payload.put("retryable", true);
            return ErrorEvent.of(runId, sessionId, IntentRoutingFailedException.CODE,
                    IntentRoutingFailedException.USER_MESSAGE, Map.copyOf(payload));
        }
        if (runtimeSessionUnavailable(ex)) {
            return ErrorEvent.of(runId, sessionId, "RUNTIME_SESSION_UNAVAILABLE",
                    ex == null || ex.getMessage() == null || ex.getMessage().isBlank()
                            ? "Runtime session 不存在或已损坏"
                            : ex.getMessage());
        }
        String code = relayWebSocketConfigTimeout(ex)
                ? "RELAY_WS_CONFIG_TIMEOUT"
                : isTimeout(ex) ? "RUNTIME_STREAM_TIMEOUT" : "RUN_ERROR";
        String message = ex == null || ex.getMessage() == null || ex.getMessage().isBlank()
                ? "Runtime execution failed"
                : ex.getMessage();
        return ErrorEvent.of(runId, sessionId, code, message);
    }

    private boolean runtimeSessionUnavailable(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof AgentRuntimeSessionUnavailable) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean runtimeSessionUnavailable(Map<String, Object> payload) {
        return payload != null
                && "RUNTIME_SESSION_UNAVAILABLE".equals(String.valueOf(payload.get("code")));
    }

    private <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
        Throwable current = ex;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
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

    private enum RunStartHandoffState {
        PENDING,
        HANDED_OFF,
        ABORTED
    }

    private enum FirstEventCompensationOutcome {
        DONE,
        RETRY
    }

    private static final class FirstEventCompensationPendingException extends RuntimeException {
        private FirstEventCompensationPendingException(String runId) {
            super("run control state is not ready for first-event timeout compensation: " + runId);
        }
    }

    private static final class RunStartAttempt {
        private final UserContext user;
        private final String runId;
        private final String interactionId;
        private final AtomicReference<RunStartHandoffState> handoffState =
                new AtomicReference<>(RunStartHandoffState.PENDING);
        private final AtomicReference<ChatRun> run = new AtomicReference<>();
        private final AtomicReference<RunExecutionClaim> executionClaim = new AtomicReference<>();
        private final AtomicReference<ChatInteractionRequest> interactionRequest = new AtomicReference<>();
        private final AtomicBoolean executionInitializationSkipped = new AtomicBoolean(false);
        private final AtomicBoolean compensationActive = new AtomicBoolean(false);
        private final AtomicBoolean compensationRetryRequested = new AtomicBoolean(false);

        private RunStartAttempt(UserContext user, String runId, String interactionId) {
            this.user = user;
            this.runId = runId;
            this.interactionId = interactionId;
        }

        private boolean beginFirstEventHandoff() {
            return handoffState.compareAndSet(RunStartHandoffState.PENDING, RunStartHandoffState.HANDED_OFF);
        }

        private boolean abort() {
            return handoffState.compareAndSet(RunStartHandoffState.PENDING, RunStartHandoffState.ABORTED);
        }

        private boolean abortFailedHandoff() {
            return handoffState.compareAndSet(RunStartHandoffState.HANDED_OFF, RunStartHandoffState.ABORTED);
        }

        private boolean aborted() {
            return handoffState.get() == RunStartHandoffState.ABORTED;
        }

        private void recordRun(ChatRun value) {
            run.set(value);
        }

        private void recordExecutionClaim(RunExecutionClaim value) {
            executionClaim.set(value);
        }

        private void recordInteraction(ChatInteractionRequest value) {
            interactionRequest.set(value);
        }

        private void markExecutionInitializationSkipped() {
            executionInitializationSkipped.set(true);
        }

        private boolean beginCompensation() {
            return compensationActive.compareAndSet(false, true);
        }

        private void finishCompensation() {
            compensationActive.set(false);
        }

        private void requestCompensationRetry() {
            compensationRetryRequested.set(true);
        }

        private boolean consumeCompensationRetry() {
            return compensationRetryRequested.compareAndSet(true, false);
        }

        private UserContext user() {
            return user;
        }

        private String runId() {
            return runId;
        }

        private String interactionId() {
            return interactionId;
        }

        private ChatRun run() {
            return run.get();
        }

        private RunExecutionClaim executionClaim() {
            return executionClaim.get();
        }

        private ChatInteractionRequest interactionRequest() {
            return interactionRequest.get();
        }

        private boolean executionInitializationSkipped() {
            return executionInitializationSkipped.get();
        }
    }

    private static final class RunPermitGuard implements AutoCloseable {
        private final RunAdmissionControlService.Permit delegate;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private RunPermitGuard(RunAdmissionControlService.Permit delegate) {
            this.delegate = delegate == null ? RunAdmissionControlService.Permit.NOOP : delegate;
        }

        @Override
        public void close() {
            closeOnce();
        }

        private boolean closeOnce() {
            if (closed.compareAndSet(false, true)) {
                delegate.close();
                return true;
            }
            return false;
        }
    }

}
