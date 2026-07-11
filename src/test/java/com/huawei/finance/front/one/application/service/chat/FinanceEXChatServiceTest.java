package com.huawei.finance.front.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.ChatInteractionProperties;
import com.huawei.finance.front.one.application.config.ChatRunOperationalProperties;
import com.huawei.finance.front.one.application.command.DocumentUpdateCommand;
import com.huawei.finance.front.one.application.command.DocumentUploadCommand;
import com.huawei.finance.front.one.application.config.IntentRecordProperties;
import com.huawei.finance.front.one.application.config.IntentFailureStrategy;
import com.huawei.finance.front.one.application.config.MemoryProperties;
import com.huawei.finance.front.one.application.config.RouteSignalProperties;
import com.huawei.finance.front.one.application.facade.DocumentFacade;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntime;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeInteractionResponseRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeInteraction;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.agent.DomainAgentClient;
import com.huawei.finance.front.one.application.integration.agent.DomainAgentRequest;
import com.huawei.finance.front.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.application.integration.conversation.ChatInteractionRequestRepository;
import com.huawei.finance.front.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.finance.front.one.application.integration.conversation.ChatEventStore;
import com.huawei.finance.front.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunCache;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.finance.front.one.application.integration.intent.IntentService;
import com.huawei.finance.front.one.application.integration.memory.ChatMessageRepository;
import com.huawei.finance.front.one.application.integration.memory.LongTermMemoryStore;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingCache;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.finance.front.one.application.service.memory.MemoryApplicationService;
import com.huawei.finance.front.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.finance.front.one.application.service.routing.IntentRecognitionRecordService;
import com.huawei.finance.front.one.application.service.routing.RouteSignalFrame;
import com.huawei.finance.front.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.finance.front.one.application.service.routing.RouteSignalRequest;
import com.huawei.finance.front.one.application.service.routing.RouteSignalResult;
import com.huawei.finance.front.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.finance.front.one.application.service.runtime.DomainAgentExecutor;
import com.huawei.finance.front.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.finance.front.one.application.service.runtime.SystemResponseExecutor;
import com.huawei.finance.front.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatInteractionRequest;
import com.huawei.finance.front.one.domain.chat.ChatInteractionStatus;
import com.huawei.finance.front.one.domain.chat.ChatInteractionType;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePart;
import com.huawei.finance.front.one.domain.chat.ChatMessagePartDraft;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunCancelSignal;
import com.huawei.finance.front.one.domain.chat.ChatRunExecution;
import com.huawei.finance.front.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.finance.front.one.domain.chat.ChatRunMessagePlan;
import com.huawei.finance.front.one.domain.chat.ChatRunMode;
import com.huawei.finance.front.one.domain.chat.ChatRunStartResult;
import com.huawei.finance.front.one.domain.chat.ChatRunStopResult;
import com.huawei.finance.front.one.domain.chat.ChatRunStatus;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.domain.chat.MessageSnapshotEvent;
import com.huawei.finance.front.one.domain.chat.RuntimeEvent;
import com.huawei.finance.front.one.domain.chat.RunCompletedEvent;
import com.huawei.finance.front.one.domain.chat.RunExecutionClaim;
import com.huawei.finance.front.one.domain.chat.RunStartedEvent;
import com.huawei.finance.front.one.domain.chat.RunWaitingUserEvent;
import com.huawei.finance.front.one.domain.chat.StoredChatEvent;
import com.huawei.finance.front.one.domain.document.DocumentLibraryPage;
import com.huawei.finance.front.one.domain.document.DocumentLibraryQuery;
import com.huawei.finance.front.one.domain.document.StoredObjectContent;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import com.huawei.finance.front.one.domain.memory.LongTermMemoryItem;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import com.huawei.finance.front.one.domain.runtime.RuntimeBindingStatus;
import com.huawei.finance.front.one.domain.usecase.UseCaseMatchResult;
import com.huawei.finance.front.one.infrastructure.runtime.intentagent.BlockingIntentAgentRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
class FinanceEXChatServiceTest {
    @Test
    void guardedRunStartedRejectionDoesNotSubscribeRouteOrRuntime() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        RejectingRunStartedEventStore events = new RejectingRunStartedEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicInteger runtimeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = countingRuntimeRouteService(routeCalls);
        AgentRuntime runtime = new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                runtimeCalls.incrementAndGet();
                return Flux.empty();
            }
            @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
        };
        FinanceEXChatService service = defaultFinanceService(
                sessions, messages, runs, events, routeService, runtime, true, new NeverCancelRunCache());

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())))
                .verifyComplete();

        assertThat(events.guardedAttempts.get()).isEqualTo(1);
        assertThat(routeCalls.get()).isZero();
        assertThat(runtimeCalls.get()).isZero();
        assertThat(events.findLatestSeqByOwnerAndSession("tenant1", "user1", "session_1")).isZero();
    }

    @Test
    void cancelledBeforeRunStartedDoesNotSubscribeRouteOrRuntime() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicInteger runtimeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = countingRuntimeRouteService(routeCalls);
        AgentRuntime runtime = new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                runtimeCalls.incrementAndGet();
                return Flux.empty();
            }
            @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
        };
        FinanceEXChatService service = defaultFinanceService(
                sessions, messages, runs, events, routeService, runtime, true, new AlwaysCancelledRunCache());

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())))
                .verifyComplete();

        assertThat(routeCalls.get()).isZero();
        assertThat(runtimeCalls.get()).isZero();
        assertThat(events.events).isEmpty();
    }

    @Test
    void ownerLostAfterRouteDoesNotInvokeRuntimeOrAppendFallbackTerminal() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicInteger runtimeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.finance.front.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                return Flux.defer(() -> {
                    routeCalls.incrementAndGet();
                    executions.rejectOwnerRunningChecks = true;
                    return Flux.just(RouteSignalFrame.result(
                            RouteSignalResult.of(RouteTarget.agentRuntime("test-runtime"))));
                });
            }
        };
        AgentRuntime runtime = new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                runtimeCalls.incrementAndGet();
                return Flux.empty();
            }
            @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
        };
        FinanceEXChatService service = defaultFinanceService(
                sessions, messages, runs, events, routeService, runtime, true,
                new NeverCancelRunCache(), executions);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                                null, null, "web", "hello", List.of(), Map.of()))
                        .collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .containsExactly("run.started"))
                .verifyComplete();

        assertThat(routeCalls).hasValue(1);
        assertThat(runtimeCalls).hasValue(0);
        assertThat(events.events).extracting(ChatEvent::type)
                .containsExactly("run.started")
                .doesNotContain("run.completed", "run.failed");
    }

    @Test
    void ownerTerminalFenceRejectionStopsWithoutFallbackFailureOrAssistant() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        runs.rejectOwnerTerminalFences = true;
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return Flux.just(MessageDeltaEvent.of(request.runId(), request.sessionId(), "partial answer"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, runtimeRouteService(), runtime);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                                null, null, "web", "hello", List.of(), Map.of()))
                        .collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .containsExactly("run.started", "message.delta"))
                .verifyComplete();

        assertThat(runs.ownerTerminalFenceAttempts).hasValue(1);
        assertThat(events.events).extracting(ChatEvent::type)
                .containsExactly("run.started", "message.delta")
                .doesNotContain("run.completed", "run.failed", "run.cancelled");
        assertThat(messages.messages).noneMatch(message -> "assistant".equals(message.role()));
        assertThat(runs.runs.values()).singleElement()
                .extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.CANCELLING);
    }

    @Test
    void ordinaryOwnerFailureUsesTerminalFence() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return Flux.error(new IllegalStateException("runtime failed"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, runtimeRouteService(), runtime);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                                null, null, "web", "hello", List.of(), Map.of()))
                        .collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .containsExactly("run.started", "run.failed"))
                .verifyComplete();

        assertThat(runs.ownerTerminalFenceAttempts).hasValue(1);
        assertThat(events.events).extracting(ChatEvent::type)
                .containsExactly("run.started", "run.failed");
        assertThat(runs.runs.values()).singleElement()
                .extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.FAILED);
    }

    @Test
    void executionInitializationFailureCommitsFailedBeforeRouteOrRuntime() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicInteger runtimeCalls = new AtomicInteger();
        AgentRuntime runtime = new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                runtimeCalls.incrementAndGet();
                return Flux.empty();
            }
            @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
        };
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, countingRuntimeRouteService(routeCalls), runtime,
                new FailingExecutionRepository());

        StepVerifier.create(service.executeRun(new UserContext("tenant1", "user1", "User One"),
                                new ChatCommand("cmd1", null, null, null, null, "web", "hello", List.of(), Map.of()))
                        .collectList())
                .assertNext(stream -> {
                    assertThat(stream).extracting(ChatEvent::type).containsExactly("run.failed");
                    assertThat(stream.getFirst().payload()).containsEntry("code", "RUN_EXECUTION_INIT_FAILED");
                })
                .verifyComplete();

        assertThat(routeCalls).hasValue(0);
        assertThat(runtimeCalls).hasValue(0);
        assertThat(runs.runs.values()).singleElement().extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.FAILED);
    }

    @Test
    void completedWithoutAssistantStillUsesTerminalFence() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, runtimeRouteService(), noopRuntime());

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                                null, null, "web", "hello", List.of(), Map.of()))
                        .collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .containsExactly("run.started", "run.completed"))
                .verifyComplete();

        assertThat(runs.ownerTerminalFenceAttempts).hasValue(1);
        assertThat(messages.messages).noneMatch(message -> "assistant".equals(message.role()));
        assertThat(runs.runs.values()).singleElement()
                .extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.COMPLETED);
    }

    @Test
    void stoppingInteractionContinuationReleasesMatchingClaim() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.finance.front.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        SessionApplicationService sessionService = new SessionApplicationService(sessions, messages, ids,
                permissionChecker);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(), events,
                permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(), ids,
                executionRegistry);
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(
                interactions, ids, permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService terminalCommitService = new ChatRunTerminalCommitService(
                streamService, sessionService, runs, leaseService, runtimeBindingRepository(), interactionService,
                Duration.ofDays(3));
        Instant now = Instant.now();
        ChatSession session = sessions.save(new ChatSession("session1", user.tenantId(), user.ownerUserId(),
                "测试会话", "ACTIVE", "web", "msg-user", "msg-assistant", null, null, 1L,
                null, now, now));
        messages.save(new ChatMessage("msg-user", user.tenantId(), user.ownerUserId(), session.id(),
                null, 1L, 0, 1, "user", "原问题", null, "run-source",
                "NORMAL", false, null, null, null, null, null, now));
        messages.save(new ChatMessage("msg-assistant", user.tenantId(), user.ownerUserId(), session.id(),
                "msg-user", 2L, 1, 1, "assistant", "请补充范围", null, "run-source",
                "NORMAL", false, null, null, null, null,
                "{\"finishReason\":\"WAITING_USER\"}", now));
        ChatInteractionRequest waiting = new ChatInteractionRequest(
                "interaction1", user.tenantId(), user.ownerUserId(), session.id(), "run-source", null,
                "msg-user", "msg-assistant", "intent-agent", null, null, null,
                ChatInteractionType.INTENT_CLARIFICATION, ChatInteractionStatus.WAITING,
                Map.of("originalQuery", "原问题"), Map.of(), now.plus(Duration.ofHours(1)), null, null, now, now);
        interactions.insert(waiting);
        ChatRun run = runService.createRunning(new CreateChatRunContext(
                "run-continue", user, session.id(), null, null,
                Map.of("interactionId", waiting.id(),
                        "interactionType", waiting.interactionType().name(),
                        "interactionAssistantMessageId", waiting.assistantMessageId()),
                ChatRunMode.NEXT, waiting.userMessageId(), waiting.userMessageId()));
        leaseService.startRun(run);
        interactionService.claimInteractionResponse(new ChatInteractionResponseCommand(
                user, waiting.id(), null, null, Map.of("问题", "答案"), Map.of()), run.id());
        ChatRunStopCoordinator coordinator = new ChatRunStopCoordinator(
                sessionService, streamService, runService, leaseService, executionRegistry,
                new AgentRuntimeExecutor(noopRuntime(), limiter), interactionService, terminalCommitService, ids);
        events.append(MessageDeltaEvent.of(run.id(), session.id(), "续接中的部分回答"));

        ChatRunStopResult stopResult = coordinator.stopRun(
                user, run.id(), "USER_STOP", RuntimeForwardHeaders.empty()).block();

        ChatInteractionRequest released = interactions.requests.get(waiting.id());
        assertThat(released.status()).isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(released.continueRunId()).isNull();
        assertThat(runs.runs.get(run.id()).status()).isEqualTo(ChatRunStatus.CANCELLED);
        assertThat(events.events).extracting(ChatEvent::type).containsExactly("message.delta", "run.cancelled");
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role())).hasSize(1);
        ChatMessage updatedAssistant = messages.findByOwnerAndId(
                user.tenantId(), user.ownerUserId(), waiting.assistantMessageId()).orElseThrow();
        assertThat(updatedAssistant.content()).isEqualTo("续接中的部分回答");
        assertThat(updatedAssistant.metadataJson()).contains("\"partial\":true").contains("USER_STOP");
        assertThat(updatedAssistant.parts()).extracting(ChatMessagePart::partType).containsExactly("ANSWER");
        assertThat(updatedAssistant.runId()).isEqualTo(run.id());
        assertThat(runs.runs.get(run.id()).assistantMessageId()).isEqualTo(waiting.assistantMessageId());
        assertThat(sessions.findById(session.id()).orElseThrow().currentLeafMessageId())
                .isEqualTo(waiting.assistantMessageId());
        assertThat(stopResult.messageReady()).isTrue();
        assertThat(stopResult.assistantMessageId()).isEqualTo(waiting.assistantMessageId());

        // 模拟旧版本或异常窗口遗留的 CANCELLED + RESPONDING，再次 stop 只修复 claim，不重复写终态。
        interactionService.claimInteractionResponse(new ChatInteractionResponseCommand(
                user, waiting.id(), null, null, Map.of("问题", "重试答案"), Map.of()), run.id());
        coordinator.stopRun(user, run.id(), "USER_STOP", RuntimeForwardHeaders.empty()).block();

        ChatInteractionRequest reconciled = interactions.requests.get(waiting.id());
        assertThat(reconciled.status()).isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(reconciled.continueRunId()).isNull();
        assertThat(events.events).extracting(ChatEvent::type).containsExactly("message.delta", "run.cancelled");
    }

    @Test
    void rejectedIntentClarificationStartDoesNotInvokeRouteOrEmitResponse() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        RejectingRunStartedEventStore events = new RejectingRunStartedEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.finance.front.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        SessionApplicationService sessionService = new SessionApplicationService(sessions, messages, ids,
                permissionChecker);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(), events,
                permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(), ids,
                executionRegistry);
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(
                interactions, ids, permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService terminalCommitService = new ChatRunTerminalCommitService(
                streamService, sessionService, runs, leaseService, runtimeBindingRepository(), interactionService,
                Duration.ofDays(3));
        AtomicInteger routeCalls = new AtomicInteger();
        FinanceEXChatService service = new FinanceEXChatService(
                sessionService,
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids,
                        Duration.ofDays(3), "relay"),
                countingRuntimeRouteService(routeCalls),
                intentRecordService(),
                domainAgentExecutor(documentFacade(), limiter),
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(noopRuntime(), limiter),
                documentFacade(), streamService, runService, leaseService,
                new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                new ChatRunStopCoordinator(sessionService, streamService, runService, leaseService, executionRegistry,
                        new AgentRuntimeExecutor(noopRuntime(), limiter), interactionService, ids),
                interactionService, terminalCommitService, ids, reactor.core.scheduler.Schedulers.boundedElastic(),
                new com.huawei.finance.front.one.application.config.DomainAgentProperties());
        Instant now = Instant.now();
        ChatSession session = sessions.save(new ChatSession("session1", user.tenantId(), user.ownerUserId(),
                "测试会话", "ACTIVE", "web", "msg-user", "msg-assistant", null, null, 1L,
                null, now, now));
        messages.save(new ChatMessage("msg-user", user.tenantId(), user.ownerUserId(), session.id(),
                null, 1L, 0, 1, "user", "原问题", null, "run-source",
                "NORMAL", false, null, null, null, null, null, now));
        ChatInteractionRequest waiting = new ChatInteractionRequest(
                "interaction1", user.tenantId(), user.ownerUserId(), session.id(), "run-source", null,
                "msg-user", "msg-assistant", "intent-agent", null, null, null,
                ChatInteractionType.INTENT_CLARIFICATION, ChatInteractionStatus.WAITING,
                Map.of("originalQuery", "原问题", "clarifyQuestion", "请补充范围"), Map.of(),
                now.plus(Duration.ofHours(1)), null, null, now, now);
        interactions.insert(waiting);

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, session.id(), null, "web", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, waiting.id(), null, null, Map.of("请补充范围", "账务")),
                        RuntimeForwardHeaders.empty()))
                .expectErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("before emitting any event"))
                .verify();

        assertThat(routeCalls.get()).isZero();
        assertThat(events.findLatestSeqByOwnerAndSession(user.tenantId(), user.ownerUserId(), session.id())).isZero();
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.RESPONDING);
    }

    @Test
    void runStartedIsEmittedBeforeExternalRouteSignalsAreRead() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.finance.front.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public RouteSignalResult routeInitial(UserContext routeUser, ChatSession session, ChatCommand command,
                                                  List<AttachmentRef> attachments,
                                                  com.huawei.finance.front.one.domain.memory.MemoryContext memory) {
                routeCalls.incrementAndGet();
                assertThat(events.events).extracting(ChatEvent::type).contains("run.started");
                return RouteSignalResult.of(RouteTarget.agentRuntime("test-runtime"));
            }

            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                return Flux.defer(() -> Flux.just(RouteSignalFrame.result(
                        routeInitial(request.user(), request.session(), request.command(),
                                request.attachments(), request.memory()))));
            }
        };
        FinanceEXChatService service = defaultFinanceService(sessions, messages, runs, events, routeService);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                                null, null, "web", "hello", List.of(), Map.of())),
                        1)
                .expectNextMatches(event -> "run.started".equals(event.type()))
                .thenCancel()
                .verify();
        assertThat(routeCalls.get()).isLessThanOrEqualTo(1);
    }

    @Test
    void firstEventTimeoutAbortsBackgroundHandoffOnlyOnce() {
        AtomicInteger abortCalls = new AtomicInteger();

        StepVerifier.withVirtualTime(() -> FinanceEXChatService.withFirstEventTimeout(
                        Mono.<ChatEvent>never(), Duration.ofSeconds(30), abortCalls::incrementAndGet))
                .thenAwait(Duration.ofSeconds(30))
                .expectErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("RUN_FIRST_EVENT_TIMEOUT"))
                .verify();

        assertThat(abortCalls).hasValue(1);
    }

    @Test
    void firstEventTimeoutDoesNotLimitRunAfterSuccessfulHandoff() {
        AtomicInteger abortCalls = new AtomicInteger();
        ChatEvent first = RunStartedEvent.of("run1", "session1");

        StepVerifier.create(FinanceEXChatService.withFirstEventTimeout(
                        Mono.just(first), Duration.ofMillis(1), abortCalls::incrementAndGet))
                .expectNext(first)
                .verifyComplete();

        assertThat(abortCalls).hasValue(0);
    }

    @Test
    void firstEventTimeoutFailsPersistedRunAndDoesNotInvokeRouteOrRuntime() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        BlockingRunStartedEventStore events = new BlockingRunStartedEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        AtomicInteger routeCalls = new AtomicInteger();
        ChatRunOperationalProperties properties = new ChatRunOperationalProperties();
        properties.setFirstEventTimeout(Duration.ofMillis(50));
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, countingRuntimeRouteService(routeCalls), noopRuntime(),
                executions, properties);
        UserContext user = new UserContext("tenant1", "user1", "User One");

        assertThatThrownBy(() -> service.startRun(user, new ChatCommand(
                        "cmd1", null, null, null, null, "web", "hello", List.of(), Map.of()),
                        RuntimeForwardHeaders.empty()).block())
                .hasMessageContaining("RUN_FIRST_EVENT_TIMEOUT");

        awaitEvent(events, "run.failed");
        events.releaseRunStarted();
        assertThat(runs.findById("run_1")).get().extracting(ChatRun::status).isEqualTo(ChatRunStatus.FAILED);
        assertThat(executions.findByRunId("run_1")).get()
                .extracting(ChatRunExecution::executionStatus).isEqualTo(ChatRunExecutionStatus.FAILED);
        assertThat(routeCalls).hasValue(0);
        assertThat(((InMemoryEventStore) events).events).extracting(ChatEvent::type).containsExactly("run.failed");
    }

    @Test
    void firstEventTimeoutAfterAdmissionDuringCacheSyncDoesNotLeaveRunningOrphan() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        BlockingPutRunCache runCache = new BlockingPutRunCache();
        AtomicInteger routeCalls = new AtomicInteger();
        ChatRunOperationalProperties properties = new ChatRunOperationalProperties();
        properties.setFirstEventTimeout(Duration.ofMillis(500));
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, countingRuntimeRouteService(routeCalls), noopRuntime(),
                executions, properties, runCache);
        UserContext user = new UserContext("tenant1", "user1", "User One");
        java.util.concurrent.CompletableFuture<ChatRunStartResult> start = service.startRun(user, new ChatCommand(
                        "cmd1", null, null, null, null, "web", "hello", List.of(), Map.of()),
                        RuntimeForwardHeaders.empty())
                .toFuture();

        try {
            assertThat(runCache.awaitPut()).isTrue();
            assertThatThrownBy(start::join)
                    .rootCause()
                    .hasMessageContaining("RUN_FIRST_EVENT_TIMEOUT");
        } finally {
            runCache.releasePut();
        }

        awaitEvent(events, "run.failed");
        assertThat(runs.findById("run_1")).get().extracting(ChatRun::status).isEqualTo(ChatRunStatus.FAILED);
        assertThat(executions.findByRunId("run_1")).isEmpty();
        assertThat(routeCalls).hasValue(0);
        assertThat(events.events).extracting(ChatEvent::type).containsExactly("run.failed");
    }

    @Test
    void interactionFirstEventTimeoutReleasesClaimWhenRunWasNotCreated() {
        BlockingSessionRepository sessions = new BlockingSessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        ChatRunOperationalProperties properties = new ChatRunOperationalProperties();
        properties.setFirstEventTimeout(Duration.ofMillis(50));
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        SessionApplicationService sessionService = new SessionApplicationService(
                sessions, messages, ids, permissionChecker);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(
                events, new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(
                runs, new NeverCancelRunCache(), events, permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions, (ApplicationInstanceIdProvider) () -> "instance-test", properties, ids, executionRegistry);
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(
                interactions, ids, permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService terminalCommitService = new ChatRunTerminalCommitService(
                streamService, sessionService, runs, leaseService, runtimeBindingRepository(), interactionService,
                Duration.ofDays(3));
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.finance.front.one.application.config.ResourceIsolationProperties());
        AgentRuntimeExecutor runtimeExecutor = new AgentRuntimeExecutor(noopRuntime(), limiter);
        reactor.core.scheduler.Scheduler eventScheduler = reactor.core.scheduler.Schedulers.newBoundedElastic(
                2, 100, "interaction-timeout-test");
        FinanceEXChatService service = new FinanceEXChatService(
                sessionService,
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids,
                        Duration.ofDays(3), "relay"),
                countingRuntimeRouteService(new AtomicInteger()), intentRecordService(), new SystemResponseExecutor(),
                runtimeExecutor, documentFacade(), streamService, runService, leaseService,
                new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                new ChatRunStopCoordinator(sessionService, streamService, runService, leaseService,
                        executionRegistry, runtimeExecutor, interactionService, terminalCommitService, ids),
                interactionService, terminalCommitService, ids, eventScheduler,
                new com.huawei.finance.front.one.application.config.DomainAgentProperties(), null, properties);
        Instant now = Instant.now();
        sessions.save(new ChatSession("session1", user.tenantId(), user.ownerUserId(), "测试会话", "ACTIVE", "web",
                "msg-user", "msg-assistant", null, null, 1L, null, now, now));
        ChatInteractionRequest waiting = new ChatInteractionRequest(
                "interaction1", user.tenantId(), user.ownerUserId(), "session1", "run-source", null,
                "msg-user", "msg-assistant", "intent-agent", null, null, null,
                ChatInteractionType.INTENT_CLARIFICATION, ChatInteractionStatus.WAITING,
                Map.of("originalQuery", "原问题", "clarifyQuestion", "请补充范围"), Map.of(),
                now.plus(Duration.ofHours(1)), null, null, now, now);
        interactions.insert(waiting);
        sessions.blockReads();

        try {
            assertThatThrownBy(() -> service.startRun(user, new ChatCommand(
                            null, null, null, "session1", null, "web", null, List.of(), Map.of(),
                            null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                            null, waiting.id(), null, null, Map.of("请补充范围", "账务")),
                            RuntimeForwardHeaders.empty()).block())
                    .hasMessageContaining("RUN_FIRST_EVENT_TIMEOUT");

            awaitInteractionStatus(interactions, waiting.id(), ChatInteractionStatus.WAITING);
            assertThat(runs.runs).isEmpty();
            assertThat(executions.executions).isEmpty();
        } finally {
            sessions.releaseReads();
            eventScheduler.dispose();
        }
    }

    @Test
    void intentCallingProgressIsPersistedBeforeIntentRouteCompletes() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> new com.huawei.finance.front.one.domain.intent.IntentDecision(
                        "domain_agent_finance_knowledge",
                        "财经知识助手",
                        com.huawei.finance.front.one.domain.intent.TaskComplexity.SIMPLE,
                        0.91,
                        true,
                        "domain_agent_finance_knowledge",
                        Map.of(),
                        List.of(),
                        Map.of())),
                new com.huawei.finance.front.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, true));
        FinanceEXChatService service = defaultFinanceService(sessions, messages, runs, events, routeService);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())).collectList())
                .assertNext(stream -> {
                    assertThat(stream).isNotEmpty();
                    assertThat(stream.getFirst().type()).isEqualTo("run.started");
                    assertThat(stream).anySatisfy(event -> {
                        assertThat(event.type()).isEqualTo("runtime.progress");
                        assertThat(event.payload()).containsEntry("source", "intent-agent")
                                .containsEntry("sourceType", "intent-start")
                                .containsEntry("stage", "intent_calling");
                    });
                    assertThat(stream).anySatisfy(event -> {
                        assertThat(event.type()).isEqualTo("runtime.progress");
                        assertThat(event.payload()).containsEntry("source", "intent-agent")
                                .containsEntry("sourceType", "intent-result")
                                .containsEntry("routeAction", "ROUTE_SINGLE")
                                .containsEntry("targetProvider", "domain-agent");
                    });
                    int progressIndex = indexOfEvent(stream, "runtime.progress", "intent-start");
                    int completedIndex = indexOfEvent(stream, "run.completed", null);
                    assertThat(progressIndex).isGreaterThanOrEqualTo(0);
                    assertThat(completedIndex).isGreaterThan(progressIndex);
                })
                .verifyComplete();

        assertThat(events.events).extracting(ChatEvent::type)
                .containsSubsequence("run.started", "runtime.progress", "run.completed");
    }

    @Test
    void intentFailureStrategyFailRunEndsWithoutCallingRuntimeOrSavingAssistant() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> {
                    throw new IllegalStateException("intent down");
                }),
                new com.huawei.finance.front.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, true, IntentFailureStrategy.FAIL_RUN));
        AtomicInteger runtimeCalls = new AtomicInteger();
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                runtimeCalls.incrementAndGet();
                return Flux.empty();
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = defaultFinanceService(
                sessions, messages, runs, events, routeService, runtime);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())).collectList())
                .assertNext(stream -> {
                    assertThat(stream).extracting(ChatEvent::type)
                            .containsSubsequence("run.started", "runtime.progress", "runtime.progress", "run.failed")
                            .doesNotContain("run.completed");
                    assertThat(stream.getLast().payload())
                            .containsEntry("code", "INTENT_ROUTING_FAILED")
                            .containsEntry("source", "intent-agent")
                            .containsEntry("failureStrategy", "FAIL_RUN")
                            .containsEntry("suggestedAction", "SELECT_DOMAIN_AGENT")
                            .containsEntry("retryable", true);
                })
                .verifyComplete();

        assertThat(runtimeCalls).hasValue(0);
        assertThat(messages.messages).extracting(ChatMessage::role).containsExactly("user");
        assertThat(runs.runs.values()).singleElement()
                .extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.FAILED);
    }

    private static int indexOfEvent(List<ChatEvent> events, String type, String sourceType) {
        for (int i = 0; i < events.size(); i++) {
            ChatEvent event = events.get(i);
            if (!type.equals(event.type())) {
                continue;
            }
            if (sourceType == null || (event.payload() != null
                    && sourceType.equals(event.payload().get("sourceType")))) {
                return i;
            }
        }
        return -1;
    }

    private static BlockingIntentAgentRuntime intentAgent(IntentService intentService) {
        return new BlockingIntentAgentRuntime(intentService);
    }

    @Test
    void resolvedRouteDiagnosticUpdateFailureDoesNotFailStartedRun() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        FailingResolvedRouteRunRepository runs = new FailingResolvedRouteRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.finance.front.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.agentRuntime("relay"))));
            }
        };
        FinanceEXChatService service = defaultFinanceService(sessions, messages, runs, events, routeService);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())).collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .containsSubsequence("run.started", "run.completed")
                        .doesNotContain("run.failed"))
                .verifyComplete();
    }

    @Test
    void domainAgentRefusalRerouteUsesProgressFramesAndDoesNotCallBlockingRouteInitial() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicBoolean blockingRouteInitialCalled = new AtomicBoolean(false);
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.finance.front.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public RouteSignalResult routeInitial(UserContext routeUser, ChatSession session, ChatCommand command,
                                                  List<AttachmentRef> attachments,
                                                  com.huawei.finance.front.one.domain.memory.MemoryContext memory) {
                blockingRouteInitialCalled.set(true);
                throw new AssertionError("blocking routeInitial must not be used for DomainAgent reroute");
            }

            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                if (routeCalls.incrementAndGet() == 1) {
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                            "agent-a", "intent-agent", 1.0, "initial intent route"))));
                }
                return Flux.just(
                        RouteSignalFrame.event(RuntimeEvent.progress(request.runId(), request.session().id(), Map.of(
                                "source", "intent-agent",
                                "sourceType", "intent-start",
                                "stage", "intent_calling",
                                "message", "正在识别问题意图",
                                "routeTrigger", "domain_reject"))),
                        RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                                "agent-b", "intent-agent", 1.0, "rerouted after refusal"))));
            }
        };
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public String provider() {
                return "domain-agent";
            }

            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                if ("agent-a".equals(request.routeTarget().selectedAgentCode())) {
                    return Flux.just(
                            RuntimeEvent.progress(request.runId(), request.sessionId(), Map.of(
                                    "code", "DOMAIN_REJECT",
                                    "message", "cannot answer this domain")),
                            com.huawei.finance.front.one.domain.chat.MessageCompletedEvent.of(
                                    request.runId(), request.sessionId()));
                }
                return Flux.just(
                        MessageDeltaEvent.of(request.runId(), request.sessionId(), "rerouted answer"),
                        com.huawei.finance.front.one.domain.chat.MessageCompletedEvent.of(
                                request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = defaultFinanceService(sessions, messages, runs, events, routeService,
                runtime, false);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())).collectList())
                .assertNext(stream -> {
                    assertThat(blockingRouteInitialCalled.get()).isFalse();
                    assertThat(stream).anySatisfy(event -> assertThat(event.payload())
                            .containsEntry("source", "intent-agent")
                            .containsEntry("sourceType", "intent-start"));
                    assertThat(stream).extracting(ChatEvent::type)
                            .contains("message.delta", "run.completed")
                            .doesNotContain("run.failed");
                })
                .verifyComplete();
    }

    @Test
    void domainAgentRefusalNoMatchExecutesRelayInSameRun() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.finance.front.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                if (routeCalls.incrementAndGet() == 1) {
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                            "agent-a", "intent-agent", 1.0, "initial intent route"))));
                }
                var noMatch = new com.huawei.finance.front.one.domain.intent.IntentDecision(
                        "finance.runtime.no_intent", "未识别到可用意图",
                        com.huawei.finance.front.one.domain.intent.TaskComplexity.COMPLEX,
                        0.0, false, null, Map.of("routeAction", "NO_MATCH"), List.of(), Map.of());
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.ofIntent(
                        RouteTarget.agentRuntime("intent-agent", 0.0, "no match routes to relay"),
                        noMatch, 1L, 0.85)));
            }
        };
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                return Flux.just(
                        RuntimeEvent.progress(request.runId(), request.sessionId(), Map.of(
                                "code", "DOMAIN_REJECT", "message", "cannot answer this domain")),
                        com.huawei.finance.front.one.domain.chat.MessageCompletedEvent.of(
                                request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        AtomicInteger relayCalls = new AtomicInteger();
        AgentRuntime relay = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                relayCalls.incrementAndGet();
                return Flux.just(
                        MessageDeltaEvent.of(request.runId(), request.sessionId(), "relay answer"),
                        com.huawei.finance.front.one.domain.chat.MessageCompletedEvent.of(
                                request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClient(
                sessions, messages, runs, events, routeService, domainClient, relay);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())).collectList())
                .assertNext(stream -> {
                    assertThat(stream).anySatisfy(event -> assertThat(event.payload())
                            .containsEntry("sourceType", "domain-agent-reroute")
                            .containsEntry("action", "ROUTE_TO_RELAY"));
                    assertThat(stream).extracting(ChatEvent::type)
                            .contains("message.delta", "run.completed")
                            .doesNotContain("run.failed");
                })
                .verifyComplete();

        assertThat(relayCalls).hasValue(1);
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role()))
                .singleElement()
                .extracting(ChatMessage::content)
                .isEqualTo("relay answer");
        assertThat(runs.runs.values()).singleElement()
                .extracting(ChatRun::runtimeProvider)
                .isEqualTo("relay");
    }

    @Test
    void domainAgentRefusalIntentFailureFailRunDoesNotCallRelay() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.finance.front.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                if (routeCalls.incrementAndGet() == 1) {
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                            "agent-a", "intent-agent", 1.0, "initial intent route"))));
                }
                var failure = new com.huawei.finance.front.one.domain.intent.IntentDecision(
                        "finance.runtime.degraded", "意图服务不可用",
                        com.huawei.finance.front.one.domain.intent.TaskComplexity.COMPLEX,
                        0.0, false, null, Map.of(), List.of(), Map.of("reason", "intent down"));
                RouteSignalResult result = RouteSignalResult.intentFailure(
                        null, failure, 1L, 0.85,
                        new RouteSignalResult.IntentFailure(IntentFailureStrategy.FAIL_RUN, "intent down"));
                return Flux.just(
                        RouteSignalFrame.event(RuntimeEvent.progress(request.runId(), request.session().id(), Map.of(
                                "source", "intent-agent",
                                "sourceType", "intent-result",
                                "routeAction", "DEGRADED",
                                "failureStrategy", "FAIL_RUN",
                                "targetProvider", "none"))),
                        RouteSignalFrame.result(result));
            }
        };
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                return Flux.just(RuntimeEvent.progress(request.runId(), request.sessionId(), Map.of(
                        "code", "DOMAIN_REJECT", "message", "cannot answer this domain")));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        AtomicInteger relayCalls = new AtomicInteger();
        AgentRuntime relay = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                relayCalls.incrementAndGet();
                return Flux.empty();
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClient(
                sessions, messages, runs, events, routeService, domainClient, relay);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())).collectList())
                .assertNext(stream -> {
                    assertThat(stream).extracting(ChatEvent::type)
                            .contains("run.failed")
                            .doesNotContain("run.completed");
                    assertThat(stream.getLast().payload())
                            .containsEntry("code", "INTENT_ROUTING_FAILED")
                            .containsEntry("failureStrategy", "FAIL_RUN");
                })
                .verifyComplete();

        assertThat(relayCalls).hasValue(0);
        assertThat(messages.messages).extracting(ChatMessage::role).containsExactly("user");
    }

    @Test
    void snapshotOverridesDeltaAndRuntimeEventsAreSavedAsMessageParts() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        NeverCancelRunCache runCache = new NeverCancelRunCache();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.finance.front.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                new InMemoryExecutionRepository(),
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry
        );
        AgentRuntime runtime = new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return Flux.just(
                        MessageDeltaEvent.of(request.runId(), request.sessionId(), "草稿"),
                        RuntimeEvent.tool(request.runId(), request.sessionId(),
                                Map.of("sourceType", "tool_call_streaming", "toolName", "search",
                                        "inputPreview", "查询报销流程")),
                        MessageSnapshotEvent.of(request.runId(), request.sessionId(), "最终\nMarkdown **正文**")
                );
            }
            @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
        };

        FinanceEXChatService service = new FinanceEXChatService(
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                runtimeRouteService(),
                intentRecordService(),
                domainAgentExecutor(documentFacade(), limiter),
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(runtime, limiter),
                documentFacade(),
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                ids
        );

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())))
                .expectNextMatches(event -> "run.started".equals(event.type()))
                .expectNextMatches(event -> "message.delta".equals(event.type()))
                .expectNextMatches(event -> "runtime.tool".equals(event.type()))
                .expectNextMatches(event -> "message.snapshot".equals(event.type()))
                .expectNextMatches(event -> "run.completed".equals(event.type())
                        && Boolean.TRUE.equals(event.payload().get("messageReady"))
                        && event.payload().get("assistantMessageId") != null
                        && event.payload().get("assistantMessageId").equals(event.payload().get("feedbackTargetMessageId")))
                .verifyComplete();

        ChatMessage assistant = messages.messages.stream()
                .filter(message -> "assistant".equals(message.role()))
                .findFirst()
                .orElseThrow();
        assertThat(assistant.content()).isEqualTo("最终\nMarkdown **正文**");
        assertThat(events.events).filteredOn(event -> "run.completed".equals(event.type()))
                .singleElement()
                .extracting(event -> event.payload().get("assistantMessageId"))
                .isEqualTo(assistant.id());
        assertThat(assistant.parts()).extracting(ChatMessagePart::partType)
                .containsExactly("TOOL", "MESSAGE_SNAPSHOT", "ANSWER");
        assertThat(assistant.parts()).extracting(ChatMessagePart::contentText)
                .containsExactly("search: 查询报销流程", "最终\nMarkdown **正文**", "最终\nMarkdown **正文**");
    }

    @Test
    void assistantAssemblyKeepsAllMessageSnapshotsAsParts() {
        AssistantAssembly assistant = new AssistantAssembly();
        assistant.observe(new MessageSnapshotEvent("run1", "session1", 0, Instant.now(), "first",
                Map.of("content", "first", "sourceType", "agent", "isFinal", false)));
        assistant.observe(new MessageSnapshotEvent("run1", "session1", 0, Instant.now(), "second",
                Map.of("content", "second", "sourceType", "generate-response", "isFinal", true)));

        assertThat(assistant.finalContent()).isEqualTo("second");
        assertThat(assistant.parts()).extracting(ChatMessagePartDraft::partType)
                .containsExactly("MESSAGE_SNAPSHOT", "MESSAGE_SNAPSHOT");
        assertThat(assistant.parts()).extracting(ChatMessagePartDraft::sourceType)
                .containsExactly("agent", "generate-response");
        assertThat(assistant.parts()).extracting(ChatMessagePartDraft::contentText)
                .containsExactly("first", "second");
        assertThat(assistant.parts()).extracting(ChatMessagePartDraft::visible)
                .containsExactly(false, false);
    }

    @Test
    void questionnaireApprovalRequestCompletesRunAsWaitingUser() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        InMemoryInteractionRequestRepository interactionRequests = new InMemoryInteractionRequestRepository();
        CapturingRuntimeBindingRepository bindings = new CapturingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.finance.front.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        SessionApplicationService sessionService = new SessionApplicationService(sessions, messages, ids, permissionChecker);
        ChatStreamApplicationService chatStreamService = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(), events,
                permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry
        );
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(interactionRequests, ids,
                permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService terminalCommitService = new ChatRunTerminalCommitService(
                chatStreamService, sessionService, runs, leaseService, bindings, interactionService, Duration.ofDays(3));
        AgentRuntime runtime = new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return Flux.just(RuntimeEvent.card(request.runId(), request.sessionId(), Map.of(
                        "source", "relay",
                        "sourceType", "approval-request",
                        "operation_type", "questionnaire",
                        "approval_id", "approval-1",
                        "message", "Please answer the following questions",
                        "runtimeSessionId", request.sessionId()
                )));
            }
            @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
        };
        AgentRuntimeInteraction interaction = new AgentRuntimeInteraction() {
            @Override public boolean supportsWaitingUserResponse(String runtimeProvider) {
                return "relay".equals(runtimeProvider);
            }
            @Override public Flux<ChatEvent> continueWithUserResponse(AgentRuntimeInteractionResponseRequest request) {
                return Flux.empty();
            }
        };

        FinanceEXChatService service = new FinanceEXChatService(
                sessionService,
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(bindings, runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                runtimeRouteService(),
                intentRecordService(),
                domainAgentExecutor(documentFacade(), limiter),
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(runtime, interaction, limiter),
                documentFacade(),
                chatStreamService,
                runService,
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                new ChatRunStopCoordinator(sessionService, chatStreamService, runService, leaseService,
                        executionRegistry, new AgentRuntimeExecutor(runtime, interaction, limiter),
                        domainAgentExecutor(documentFacade(), limiter), ids),
                interactionService,
                terminalCommitService,
                ids,
                reactor.core.scheduler.Schedulers.boundedElastic(),
                new com.huawei.finance.front.one.application.config.DomainAgentProperties()
        );

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())))
                .expectNextMatches(event -> "run.started".equals(event.type()))
                .expectNextMatches(event -> "runtime.card".equals(event.type()))
                .expectNextMatches(event -> "run.waiting_user".equals(event.type()))
                .verifyComplete();

        ChatRun run = runs.runs.values().iterator().next();
        assertThat(run.status()).isEqualTo(ChatRunStatus.WAITING_USER);
        assertThat(executions.findByRunId(run.id())).get()
                .extracting(ChatRunExecution::executionStatus)
                .isEqualTo(ChatRunExecutionStatus.WAITING_USER);
        ChatMessage assistant = messages.messages.stream()
                .filter(message -> "assistant".equals(message.role()))
                .findFirst()
                .orElseThrow();
        assertThat(assistant.parts()).extracting(ChatMessagePart::partType)
                .contains("AGENT_CLARIFICATION_REQUEST");
        assertThat(interactionRequests.requests.values()).singleElement()
                .satisfies(request -> {
                    assertThat(request.status()).isEqualTo(ChatInteractionStatus.WAITING);
                    assertThat(request.assistantMessageId()).isEqualTo(assistant.id());
                });
        assertThat(events.events).extracting(ChatEvent::type)
                .containsExactly("run.started", "runtime.card", "run.waiting_user");
    }

    @Test
    void intentClarificationInteractionResponseStartsContinuationWithoutApprovalIdOrApproved() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        InMemoryInteractionRequestRepository interactionRequests = new InMemoryInteractionRequestRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.finance.front.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        SessionApplicationService sessionService = new SessionApplicationService(sessions, messages, ids, permissionChecker);
        ChatStreamApplicationService chatStreamService = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(), events,
                permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry
        );
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(interactionRequests, ids,
                permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService terminalCommitService = new ChatRunTerminalCommitService(
                chatStreamService, sessionService, runs, leaseService, runtimeBindingRepository(), interactionService,
                Duration.ofDays(3));
        RouteSignalApplicationService routeService = systemRouteService();
        FinanceEXChatService service = new FinanceEXChatService(
                sessionService,
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids,
                        Duration.ofDays(3), "relay"),
                routeService,
                intentRecordService(),
                domainAgentExecutor(documentFacade(), limiter),
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(noopRuntime(), limiter),
                documentFacade(),
                chatStreamService,
                runService,
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                new ChatRunStopCoordinator(sessionService, chatStreamService, runService, leaseService,
                        executionRegistry, new AgentRuntimeExecutor(noopRuntime(), limiter),
                        domainAgentExecutor(documentFacade(), limiter), ids),
                interactionService,
                terminalCommitService,
                ids,
                reactor.core.scheduler.Schedulers.boundedElastic(),
                new com.huawei.finance.front.one.application.config.DomainAgentProperties()
        );
        Instant now = Instant.now();
        sessions.save(new ChatSession("session1", user.tenantId(), user.ownerUserId(), "测试会话", "ACTIVE", "web",
                "msg-user", "msg-assistant", null, null, 1L, null, now, now));
        messages.save(new ChatMessage("msg-user", user.tenantId(), user.ownerUserId(), "session1",
                null, 1L, 0, 1, "user", "再帮我看下方案", null, "run-source",
                "NORMAL", false, null, null, null, null, null, now));
        messages.save(new ChatMessage("msg-assistant", user.tenantId(), user.ownerUserId(), "session1",
                null, 2L, 1, 1, "assistant", "", null, "run-source",
                "NORMAL", false, null, null, null, null, null, now));
        ChatInteractionRequest waiting = new ChatInteractionRequest(
                "interaction1",
                user.tenantId(),
                user.ownerUserId(),
                "session1",
                "run-source",
                null,
                "msg-user",
                "msg-assistant",
                "intent-agent",
                null,
                null,
                null,
                ChatInteractionType.INTENT_CLARIFICATION,
                ChatInteractionStatus.WAITING,
                Map.of(
                        "source", "intent-agent",
                        "sourceType", "intent-clarification-request",
                        "interactionType", "INTENT_CLARIFICATION",
                        "originalQuery", "再帮我看下方案",
                        "clarifyQuestion", "您提到的方案具体是指哪个方案？"),
                Map.of(),
                now.plus(Duration.ofHours(1)),
                null,
                null,
                now,
                now);
        interactionRequests.insert(waiting);

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, "session1", null, "web", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, waiting.id(), null, null,
                        Map.of("您提到的方案具体是指哪个方案？", "我是说账务审批的方案")),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> {
                    assertThat(result.sessionId()).isEqualTo("session1");
                    assertThat(result.firstSeq()).isGreaterThan(0L);
                    assertThat(result.streamTopicId()).isEqualTo("chat-run-" + result.runId());
                })
                .verifyComplete();

        awaitEvent(events, "runtime.card");
        assertThat(events.events).extracting(ChatEvent::type)
                .contains("run.started", "runtime.card");
        assertThat(events.events).filteredOn(event -> "runtime.card".equals(event.type()))
                .anySatisfy(event -> assertThat(event.payload())
                        .containsEntry("sourceType", "intent-clarification-response")
                        .doesNotContainKey("approval_id"));
        assertThat(runs.runs.values()).allSatisfy(run -> assertThat(run.status()).isNotEqualTo(ChatRunStatus.RUNNING));
    }

    @Test
    void interactionContinuationSyncFailureAfterRunCreationMarksRunFailedAndRestoresWaiting() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        InMemoryInteractionRequestRepository interactionRequests = new InMemoryInteractionRequestRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.finance.front.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        SessionApplicationService sessionService = new SessionApplicationService(sessions, messages, ids, permissionChecker);
        ChatStreamApplicationService chatStreamService = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(), events,
                permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry
        );
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(interactionRequests, ids,
                permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService terminalCommitService = new ChatRunTerminalCommitService(
                chatStreamService, sessionService, runs, leaseService, runtimeBindingRepository(), interactionService,
                Duration.ofDays(3));
        RouteSignalApplicationService failingRouteService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.finance.front.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                throw new IllegalStateException("route setup down");
            }
        };
        FinanceEXChatService service = new FinanceEXChatService(
                sessionService,
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids,
                        Duration.ofDays(3), "relay"),
                failingRouteService,
                intentRecordService(),
                domainAgentExecutor(documentFacade(), limiter),
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(noopRuntime(), limiter),
                documentFacade(),
                chatStreamService,
                runService,
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                new ChatRunStopCoordinator(sessionService, chatStreamService, runService, leaseService,
                        executionRegistry, new AgentRuntimeExecutor(noopRuntime(), limiter),
                        domainAgentExecutor(documentFacade(), limiter), ids),
                interactionService,
                terminalCommitService,
                ids,
                reactor.core.scheduler.Schedulers.boundedElastic(),
                new com.huawei.finance.front.one.application.config.DomainAgentProperties()
        );
        Instant now = Instant.now();
        sessions.save(new ChatSession("session1", user.tenantId(), user.ownerUserId(), "测试会话", "ACTIVE", "web",
                "msg-user", "msg-assistant", null, null, 1L, null, now, now));
        messages.save(new ChatMessage("msg-user", user.tenantId(), user.ownerUserId(), "session1",
                null, 1L, 0, 1, "user", "再帮我看下方案", null, "run-source",
                "NORMAL", false, null, null, null, null, null, now));
        ChatInteractionRequest waiting = new ChatInteractionRequest(
                "interaction1",
                user.tenantId(),
                user.ownerUserId(),
                "session1",
                "run-source",
                null,
                "msg-user",
                "msg-assistant",
                "intent-agent",
                null,
                null,
                null,
                ChatInteractionType.INTENT_CLARIFICATION,
                ChatInteractionStatus.WAITING,
                Map.of("interactionType", "INTENT_CLARIFICATION", "originalQuery", "再帮我看下方案"),
                Map.of(),
                now.plus(Duration.ofHours(1)),
                null,
                null,
                now,
                now);
        interactionRequests.insert(waiting);

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, "session1", null, "web", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, waiting.id(), null, null, Map.of("问题", "答案")),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.sessionId()).isEqualTo("session1"))
                .verifyComplete();

        awaitEvent(events, "run.failed");
        assertThat(events.events).extracting(ChatEvent::type).contains("run.failed");
        assertThat(runs.runs.values()).singleElement()
                .extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.FAILED);
        assertThat(executions.executions.values()).singleElement()
                .extracting(ChatRunExecution::executionStatus)
                .isEqualTo(ChatRunExecutionStatus.FAILED);
        assertThat(interactionRequests.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.WAITING);
    }

    @Test
    void visibleRuntimePartsPersistAssistantMessageWhenNoAnswerTextExists() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        NeverCancelRunCache runCache = new NeverCancelRunCache();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.finance.front.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                new InMemoryExecutionRepository(),
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry
        );
        AgentRuntime runtime = new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return Flux.just(RuntimeEvent.card(request.runId(), request.sessionId(),
                        Map.of("sourceType", "domain-agent-card",
                                "cardUrl", "https://cards.example/render/1",
                                "intent", "CreditSales",
                                "domainAgentId", "domain-agent-card")));
            }
            @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
        };

        FinanceEXChatService service = new FinanceEXChatService(
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                runtimeRouteService(),
                intentRecordService(),
                domainAgentExecutor(documentFacade(), limiter),
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(runtime, limiter),
                documentFacade(),
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                ids
        );

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "show card", List.of(), Map.of())))
                .expectNextMatches(event -> "run.started".equals(event.type()))
                .expectNextMatches(event -> "runtime.card".equals(event.type()))
                .expectNextMatches(event -> "run.completed".equals(event.type())
                        && Boolean.TRUE.equals(event.payload().get("messageReady"))
                        && event.payload().get("assistantMessageId") != null)
                .verifyComplete();

        ChatMessage assistant = messages.messages.stream()
                .filter(message -> "assistant".equals(message.role()))
                .findFirst()
                .orElseThrow();
        assertThat(assistant.content()).isEmpty();
        assertThat(assistant.parts()).extracting(ChatMessagePart::partType)
                .containsExactly("CARD", "ANSWER");
        assertThat(assistant.parts().get(0).payload()).containsEntry("cardUrl", "https://cards.example/render/1");
        assertThat(messages.parts).extracting(ChatMessagePart::partType)
                .containsExactly("CARD", "ANSWER");
        assertThat(runs.runs.values().iterator().next().assistantMessageId()).isEqualTo(assistant.id());
    }

    @Test
    void explicitDomainAgentTargetRoutesAndBindsDomainAgent() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        NeverCancelRunCache runCache = new NeverCancelRunCache();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.finance.front.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                new InMemoryExecutionRepository(),
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry
        );
        DocumentFacade documents = documentFacade();
        AtomicReference<RuntimeForwardHeaders> capturedHeaders = new AtomicReference<>();
        AtomicReference<DomainAgentRequest> capturedRequest = new AtomicReference<>();
        DomainAgentExecutor domainAgentExecutor = new DomainAgentExecutor(new DomainAgentClient() {
            @Override public Flux<ChatEvent> query(DomainAgentRequest request) {
                capturedHeaders.set(request.forwardHeaders());
                capturedRequest.set(request);
                return Flux.just(MessageDeltaEvent.of(request.runId(), request.sessionId(), "domain answer"));
            }
            @Override public Mono<Void> cancel(DomainAgentCancelRequest request) { return Mono.empty(); }
        }, documents, limiter);

        FinanceEXChatService service = new FinanceEXChatService(
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                runtimeRouteService(),
                intentRecordService(),
                domainAgentExecutor,
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(noopRuntime(), limiter),
                documents,
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                ids
        );

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of("skillId", "skill-other"),
                        "DOMAIN_AGENT", "skill-tax", ChatRunMode.NEXT, null, null, null),
                        RuntimeForwardHeaders.fromCookieHeader("sid=abc", 8192)))
                .expectNextMatches(event -> "run.started".equals(event.type()))
                .expectNextMatches(event -> "runtime.metadata".equals(event.type())
                        && "DOMAIN_AGENT".equals(event.payload().get("targetType"))
                        && "skill-tax".equals(event.payload().get("targetId"))
                        && "selected_domain_agent".equals(event.payload().get("metadataType")))
                .expectNextMatches(event -> "message.delta".equals(event.type()))
                .expectNextMatches(event -> "run.completed".equals(event.type()))
                .verifyComplete();

        assertThat(capturedHeaders.get()).isNotNull();
        assertThat(capturedHeaders.get().cookieHeader()).isEqualTo("sid=abc");
        assertThat(capturedRequest.get()).isNotNull();
        assertThat(capturedRequest.get().metadata()).containsEntry("skillId", "skill-other");
        ChatRun run = runs.runs.values().iterator().next();
        assertThat(run.routeType()).isEqualTo("DOMAIN_AGENT");
        assertThat(run.agentCode()).isEqualTo("skill-tax");
        assertThat(run.runtimeProvider()).isEqualTo("domain-agent");
        assertThat(messages.parts).anySatisfy(part -> {
            assertThat(part.partType()).isEqualTo("METADATA");
            assertThat(part.payload()).containsEntry("targetId", "skill-tax")
                    .containsEntry("domainAgentId", "skill-tax")
                    .containsEntry("metadataType", "selected_domain_agent");
        });
    }

    @Test
    void forceRerouteCancelsActiveDomainAgentBindingAndRunsRouteSignals() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        NeverCancelRunCache runCache = new NeverCancelRunCache();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        CapturingRuntimeBindingRepository bindings = new CapturingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.finance.front.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                new InMemoryExecutionRepository(),
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry
        );
        ChatSession session = new ChatSession("session1", user.tenantId(), user.ownerUserId(),
                "测试会话", "ACTIVE", "web", Instant.now(), Instant.now());
        sessions.save(session);
        bindings.save(new RuntimeBinding("binding-domain", user.tenantId(), user.ownerUserId(),
                session.id(), RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER, null,
                "session1", RuntimeBindingStatus.ACTIVE, "run-old", Instant.now().plus(Duration.ofDays(1)),
                Instant.now(), Instant.now(), Map.of("domainAgentId", "skill-old")));
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.finance.front.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                routeCalls.incrementAndGet();
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.agentRuntime("test-runtime"))));
            }
        };
        FinanceEXChatService service = new FinanceEXChatService(
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(bindings, runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                routeService,
                intentRecordService(),
                domainAgentExecutor(documentFacade(), limiter),
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(noopRuntime(), limiter),
                documentFacade(),
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                ids
        );

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        session.id(), null, "web", "重新路由", List.of(), Map.of(),
                        null, null, ChatRunMode.NEXT, null, null, null,
                        RouteMemoryApplicationService.TRIGGER_USER_CORRECTION)).collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type).contains("run.completed"))
                .verifyComplete();

        assertThat(routeCalls.get()).isEqualTo(1);
        assertThat(bindings.savedHistory).anySatisfy(binding -> {
            assertThat(binding.id()).isEqualTo("binding-domain");
            assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
        });
    }

    @Test
    void invalidTargetTypeFailsBeforeWritingUserMessageOrRun() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        FinanceEXChatService service = defaultFinanceService(sessions, messages, runs, events);
        UserContext user = new UserContext("tenant1", "user1", "User One");

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of(),
                        "BAD", "skill-tax", ChatRunMode.NEXT, null, null, null)))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                        && ex.getMessage().contains("targetType 仅支持 DOMAIN_AGENT"))
                .verify();

        assertThat(sessions.sessions).isEmpty();
        assertThat(messages.messages).isEmpty();
        assertThat(runs.runs).isEmpty();
        assertThat(events.events).isEmpty();
    }

    @Test
    void domainAgentTargetWithoutTargetIdFailsBeforeWritingUserMessageOrRun() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        FinanceEXChatService service = defaultFinanceService(sessions, messages, runs, events);
        UserContext user = new UserContext("tenant1", "user1", "User One");

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of(),
                        "DOMAIN_AGENT", null, ChatRunMode.NEXT, null, null, null)))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                        && ex.getMessage().contains("targetType=DOMAIN_AGENT 时 targetId 不能为空"))
                .verify();

        assertThat(sessions.sessions).isEmpty();
        assertThat(messages.messages).isEmpty();
        assertThat(runs.runs).isEmpty();
        assertThat(events.events).isEmpty();
    }

    @Test
    void mismatchedRuntimeEventFailsCurrentRunWithoutPersistingForeignEvent() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        CountingCancelRunCache runCache = new CountingCancelRunCache();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.finance.front.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                new InMemoryExecutionRepository(),
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry
        );

        AgentRuntime mismatchedRuntime = new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return Flux.just(new StoredChatEvent(request.runId(), "foreign-session", 0L,
                        "message.delta", Instant.now(), Map.of("delta", "wrong")));
            }
            @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
        };

        FinanceEXChatService service = new FinanceEXChatService(
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                runtimeRouteService(),
                intentRecordService(),
                domainAgentExecutor(documentFacade(), limiter),
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(mismatchedRuntime, limiter),
                documentFacade(),
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                ids
        );

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())))
                .assertNext(event -> assertThat(event.type()).isEqualTo("run.started"))
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo("run.failed");
                    assertThat(event.payload()).containsEntry("code", "RUN_EVENT_IDENTITY_MISMATCH");
                })
                .verifyComplete();

        assertThat(events.events).extracting(ChatEvent::sessionId).containsOnly("session_1");
        assertThat(events.events).extracting(ChatEvent::type).doesNotContain("message.delta");
        assertThat(messages.messages).extracting(ChatMessage::role).containsExactly("user");
    }

    @Test
    void cancelledRunDoesNotPersistPartialAssistantMessageAsHistory() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        CountingCancelRunCache runCache = new CountingCancelRunCache();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.finance.front.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                new InMemoryExecutionRepository(),
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry
        );

        FinanceEXChatService service = new FinanceEXChatService(
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                systemRouteService(),
                intentRecordService(),
                domainAgentExecutor(documentFacade(), limiter),
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(noopRuntime(), limiter),
                documentFacade(),
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                ids
        );

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())))
                .expectNextCount(3)
                .verifyComplete();

        assertThat(messages.messages).extracting(ChatMessage::role).containsExactly("user");
    }

    @Test
    void userStopPersistsPartialAssistantMessageFromPersistedEvents() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        seedRunningRun(sessions, messages, runs, user, "run1", "session1", "msg-user");
        events.append(RuntimeEvent.tool("run1", "session1", Map.of(
                "sourceType", "tool_call_streaming",
                "toolName", "search",
                "inputPreview", "查询报销流程"
        )));
        events.append(MessageDeltaEvent.of("run1", "session1", "已输出的部分回答"));

        FinanceEXChatService service = stopService(sessions, messages, runs, events);

        var stopResult = service.stopRun(user, "run1", RuntimeForwardHeaders.empty()).block();
        assertThat(stopResult.status()).isEqualTo(ChatRunStatus.CANCELLED);

        ChatMessage assistant = messages.messages.stream()
                .filter(message -> "assistant".equals(message.role()))
                .findFirst()
                .orElseThrow();
        assertThat(assistant.content()).isEqualTo("已输出的部分回答");
        assertThat(assistant.metadataJson()).contains("\"partial\":true").contains("USER_STOP");
        assertThat(assistant.parts()).extracting(ChatMessagePart::partType)
                .containsExactly("TOOL", "ANSWER");
        assertThat(runs.findById("run1").orElseThrow().assistantMessageId()).isEqualTo(assistant.id());
        assertThat(stopResult.messageReady()).isTrue();
        assertThat(stopResult.assistantMessageId()).isEqualTo(assistant.id());
        assertThat(stopResult.feedbackTargetMessageId()).isEqualTo(assistant.id());
        ChatEvent cancelled = events.events.stream()
                .filter(event -> "run.cancelled".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(cancelled.payload()).containsEntry("messageReady", true)
                .containsEntry("assistantMessageId", assistant.id())
                .containsEntry("feedbackTargetMessageId", assistant.id());
    }

    @Test
    void userStopPersistsAssistantMessageWhenOnlyVisibleRuntimePartsExist() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        seedRunningRun(sessions, messages, runs, user, "run1", "session1", "msg-user");
        events.append(RuntimeEvent.progress("run1", "session1", Map.of(
                "sourceType", "relay-progress",
                "text", "正在调用工具"
        )));

        FinanceEXChatService service = stopService(sessions, messages, runs, events);

        var stopResult = service.stopRun(user, "run1", RuntimeForwardHeaders.empty()).block();
        assertThat(stopResult.status()).isEqualTo(ChatRunStatus.CANCELLED);
        ChatMessage assistant = messages.messages.stream()
                .filter(message -> "assistant".equals(message.role()))
                .findFirst()
                .orElseThrow();
        assertThat(assistant.content()).isEmpty();
        assertThat(assistant.metadataJson()).contains("\"partial\":true").contains("USER_STOP");
        assertThat(assistant.parts()).extracting(ChatMessagePart::partType)
                .containsExactly("PROGRESS", "ANSWER");
        assertThat(messages.parts).extracting(ChatMessagePart::partType)
                .containsExactly("PROGRESS", "ANSWER");
        assertThat(runs.findById("run1").orElseThrow().assistantMessageId()).isEqualTo(assistant.id());
        assertThat(stopResult.messageReady()).isTrue();
        assertThat(stopResult.feedbackTargetMessageId()).isEqualTo(assistant.id());
        ChatEvent cancelled = events.events.stream()
                .filter(event -> "run.cancelled".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(cancelled.payload()).containsEntry("messageReady", true)
                .containsEntry("assistantMessageId", assistant.id());
    }

    @Test
    void userStopDoesNotPersistAssistantMessageForMetadataOnlyEvents() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        seedRunningRun(sessions, messages, runs, user, "run1", "session1", "msg-user");
        events.append(RuntimeEvent.metadata("run1", "session1", Map.of(
                "sourceType", "trace",
                "metadataType", "trace",
                "traceId", "trace-1"
        )));

        FinanceEXChatService service = stopService(sessions, messages, runs, events);

        var stopResult = service.stopRun(user, "run1", RuntimeForwardHeaders.empty()).block();
        assertThat(stopResult.status()).isEqualTo(ChatRunStatus.CANCELLED);
        assertThat(messages.messages).extracting(ChatMessage::role).containsExactly("user");
        assertThat(runs.findById("run1").orElseThrow().assistantMessageId()).isNull();
        assertThat(stopResult.messageReady()).isFalse();
        ChatEvent cancelled = events.events.stream()
                .filter(event -> "run.cancelled".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(cancelled.payload()).containsEntry("messageReady", false);
        assertThat(cancelled.payload()).doesNotContainKeys("assistantMessageId", "feedbackTargetMessageId");
    }

    @Test
    void userStopRollsBackTerminalWhenPartialAssistantPersistenceFails() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        FailingMessageRepository messages = new FailingMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        seedRunningRun(sessions, messages, runs, user, "run1", "session1", "msg-user");
        events.append(MessageDeltaEvent.of("run1", "session1", "已输出但保存失败的回答"));
        messages.failSaves = true;

        FinanceEXChatService service = stopService(sessions, messages, runs, events);

        assertThatThrownBy(() -> service.stopRun(user, "run1", RuntimeForwardHeaders.empty()).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("message db down");

        assertThat(events.events).noneMatch(event -> "run.cancelled".equals(event.type()));
        assertThat(runs.findById("run1").orElseThrow().status()).isEqualTo(ChatRunStatus.CANCELLING);
        assertThat(runs.findById("run1").orElseThrow().assistantMessageId()).isNull();

        messages.failSaves = false;
        var retried = service.stopRun(user, "run1", RuntimeForwardHeaders.empty()).block();

        assertThat(retried.status()).isEqualTo(ChatRunStatus.CANCELLED);
        assertThat(events.events.stream().filter(event -> "run.cancelled".equals(event.type())).count()).isEqualTo(1);
        assertThat(runs.findById("run1").orElseThrow().assistantMessageId()).isNotBlank();
    }

    @Test
    void terminalCommitPersistsWaitingUserStateTogether() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        InMemoryInteractionRequestRepository interactionRequests = new InMemoryInteractionRequestRepository();
        CapturingRuntimeBindingRepository bindings = new CapturingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        PermissionChecker permissionChecker = new PermissionChecker();
        IdGenerator ids = new FixedIdGenerator();
        seedRunningRun(sessions, messages, runs, user, "run1", "session1", "msg-user");
        executions.createForRun(runs.findById("run1").orElseThrow(), "exec1", "instance-test", Duration.ofMinutes(5));
        ChatSession session = sessions.findById("session1").orElseThrow();
        ChatMessage userMessage = messages.findByOwnerAndId(user.tenantId(), user.ownerUserId(), "msg-user")
                .orElseThrow();
        Instant now = Instant.now();
        RuntimeBinding binding = new RuntimeBinding("binding1", user.tenantId(), user.ownerUserId(),
                session.id(), "relay", userMessage.id(), "runtime-session-1", RuntimeBindingStatus.ACTIVE,
                "run1", now.plus(Duration.ofMinutes(5)), now, now, Map.of());
        bindings.save(binding);
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(interactionRequests, ids,
                permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService commitService = new ChatRunTerminalCommitService(
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()),
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                runs,
                new ChatRunLeaseApplicationService(executions,
                        (ApplicationInstanceIdProvider) () -> "instance-test",
                        new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(),
                        ids,
                        new LocalChatRunExecutionRegistry()),
                bindings,
                interactionService,
                Duration.ofDays(3)
        );
        AssistantAssembly assistant = new AssistantAssembly();
        assistant.observe(RuntimeEvent.card("run1", session.id(), Map.of(
                "sourceType", "approval-request",
                "operation_type", "questionnaire",
                "approval_id", "approval-1",
                "message", "请选择范围"
        )));
        ChatInteractionRequest waitingRequest = interactionService.prepareInteraction(new ChatInteractionCreateContext(
                user,
                session,
                "run1",
                userMessage,
                "msg-assistant",
                "relay",
                binding.id(),
                binding.runtimeSessionId(),
                Map.of("sourceType", "approval-request", "operation_type", "questionnaire",
                        "approval_id", "approval-1")
        ));
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>(binding);
        ChatRunTerminalCommitService.TerminalCommitContext commitContext =
                new ChatRunTerminalCommitService.TerminalCommitContext(
                        user,
                        session,
                        new ChatRunMessagePlan(ChatRunMode.NEXT, userMessage.id(), userMessage, null),
                        bindingRef,
                        assistant,
                        "run1",
                        new RunExecutionClaim("run1", "instance-test", 1L),
                        null
                );

        ChatRunTerminalCommitService.CommitResult result = commitService.commitWaitingUser(
                new ChatRunTerminalCommitService.WaitingUserCommitCommand(
                        new RunWaitingUserEvent("run1", session.id(), 0L, now, Map.of(
                                "status", "WAITING_USER",
                                "interactionId", waitingRequest.id(),
                                "messageReady", true,
                                "assistantMessageId", waitingRequest.assistantMessageId())),
                        commitContext,
                        new ChatRunTerminalCommitService.MessageTarget(true, waitingRequest.assistantMessageId()),
                        waitingRequest
                ));

        assertThat(result.event().type()).isEqualTo("run.waiting_user");
        assertThat(events.events).extracting(ChatEvent::type).containsExactly("run.waiting_user");
        ChatMessage assistantMessage = messages.messages.stream()
                .filter(message -> "assistant".equals(message.role()))
                .findFirst()
                .orElseThrow();
        assertThat(assistantMessage.id()).isEqualTo("msg-assistant");
        assertThat(assistantMessage.parts()).extracting(ChatMessagePart::partType)
                .contains("AGENT_CLARIFICATION_REQUEST");
        ChatInteractionRequest savedInteraction = interactionRequests.requests.get(waitingRequest.id());
        assertThat(savedInteraction.runtimeBindingId()).isEqualTo(binding.id());
        assertThat(savedInteraction.status()).isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(savedInteraction.expiresAt()).isNotNull();
        assertThat(runs.findById("run1").orElseThrow().status()).isEqualTo(ChatRunStatus.WAITING_USER);
        assertThat(runs.findById("run1").orElseThrow().assistantMessageId()).isEqualTo("msg-assistant");
        assertThat(executions.findByRunId("run1")).get()
                .extracting(ChatRunExecution::executionStatus)
                .isEqualTo(ChatRunExecutionStatus.WAITING_USER);
        assertThat(bindings.saved.leafMessageId()).isEqualTo("msg-assistant");
        assertThat(bindings.saved.runtimeSessionId()).isEqualTo("runtime-session-1");
        assertThat(bindings.saved.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
    }

    @Test
    void terminalCommitCancelsRelayBindingAfterCompletedRun() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        InMemoryInteractionRequestRepository interactionRequests = new InMemoryInteractionRequestRepository();
        CapturingRuntimeBindingRepository bindings = new CapturingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        PermissionChecker permissionChecker = new PermissionChecker();
        IdGenerator ids = new FixedIdGenerator();
        seedRunningRun(sessions, messages, runs, user, "run1", "session1", "msg-user");
        executions.createForRun(runs.findById("run1").orElseThrow(), "exec1", "instance-test", Duration.ofMinutes(5));
        ChatSession session = sessions.findById("session1").orElseThrow();
        ChatMessage userMessage = messages.findByOwnerAndId(user.tenantId(), user.ownerUserId(), "msg-user")
                .orElseThrow();
        Instant now = Instant.now();
        RuntimeBinding binding = new RuntimeBinding("binding1", user.tenantId(), user.ownerUserId(),
                session.id(), "relay", userMessage.id(), "runtime-session-1", RuntimeBindingStatus.ACTIVE,
                "run1", now.plus(Duration.ofMinutes(5)), now, now, Map.of());
        bindings.save(binding);
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(interactionRequests, ids,
                permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService commitService = new ChatRunTerminalCommitService(
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()),
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                runs,
                new ChatRunLeaseApplicationService(executions,
                        (ApplicationInstanceIdProvider) () -> "instance-test",
                        new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(),
                        ids,
                        new LocalChatRunExecutionRegistry()),
                bindings,
                interactionService,
                Duration.ofDays(3)
        );
        AssistantAssembly assistant = new AssistantAssembly();
        assistant.observe(MessageSnapshotEvent.of("run1", session.id(), "Relay answer"));
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>(binding);
        ChatRunTerminalCommitService.TerminalCommitContext commitContext =
                new ChatRunTerminalCommitService.TerminalCommitContext(
                        user,
                        session,
                        new ChatRunMessagePlan(ChatRunMode.NEXT, userMessage.id(), userMessage, null),
                        bindingRef,
                        assistant,
                        "run1",
                        new RunExecutionClaim("run1", "instance-test", 1L),
                        null
                );

        ChatRunTerminalCommitService.CommitResult result = commitService.commitCompleted(
                new ChatRunTerminalCommitService.CompletedCommitCommand(
                        RunCompletedEvent.of("run1", session.id(), Map.of("status", "COMPLETED")),
                        commitContext,
                        new ChatRunTerminalCommitService.MessageTarget(true, "msg-assistant")
                ));

        assertThat(result.event().type()).isEqualTo("run.completed");
        assertThat(runs.findById("run1").orElseThrow().status()).isEqualTo(ChatRunStatus.COMPLETED);
        assertThat(runs.findById("run1").orElseThrow().assistantMessageId()).isEqualTo("msg-assistant");
        assertThat(bindings.saved.id()).isEqualTo(binding.id());
        assertThat(bindings.saved.status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
        assertThat(bindings.saved.leafMessageId()).isEqualTo(userMessage.id());
    }

    @Test
    void terminalCommitWaitingUserAnswersPreviousInteractionInSameTransaction() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        InMemoryInteractionRequestRepository interactionRequests = new InMemoryInteractionRequestRepository();
        CapturingRuntimeBindingRepository bindings = new CapturingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        PermissionChecker permissionChecker = new PermissionChecker();
        IdGenerator ids = new FixedIdGenerator();
        seedRunningRun(sessions, messages, runs, user, "run1", "session1", "msg-user");
        executions.createForRun(runs.findById("run1").orElseThrow(), "exec1", "instance-test", Duration.ofMinutes(5));
        ChatSession session = sessions.findById("session1").orElseThrow();
        ChatMessage userMessage = messages.findByOwnerAndId(user.tenantId(), user.ownerUserId(), "msg-user")
                .orElseThrow();
        Instant now = Instant.now();
        messages.save(new ChatMessage("msg-assistant", user.tenantId(), user.ownerUserId(), session.id(),
                userMessage.id(), 2L, 1, 1, "assistant", "", null, "run-old",
                "NORMAL", false, null, null, null, null,
                "{\"finishReason\":\"WAITING_USER\"}", now));
        ChatInteractionRequest previous = new ChatInteractionRequest(
                "interaction-old",
                user.tenantId(),
                user.ownerUserId(),
                session.id(),
                "run-old",
                "run1",
                userMessage.id(),
                "msg-assistant",
                "intent-agent",
                null,
                "intent-session-1",
                null,
                ChatInteractionType.INTENT_CLARIFICATION,
                ChatInteractionStatus.RESPONDING,
                Map.of("sourceType", "intent-clarification-request", "originalQuery", "看下方案"),
                Map.of("questionnaireAnswers", Map.of("方向", "规范")),
                now.plus(Duration.ofHours(1)),
                null,
                null,
                now,
                now);
        interactionRequests.insert(previous);
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(interactionRequests, ids,
                permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService commitService = new ChatRunTerminalCommitService(
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()),
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                runs,
                new ChatRunLeaseApplicationService(executions,
                        (ApplicationInstanceIdProvider) () -> "instance-test",
                        new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(),
                        ids,
                        new LocalChatRunExecutionRegistry()),
                bindings,
                interactionService,
                Duration.ofDays(3)
        );
        AssistantAssembly assistant = new AssistantAssembly();
        assistant.observe(RuntimeEvent.card("run1", session.id(), Map.of(
                "sourceType", "intent-clarification-request",
                "interactionType", "INTENT_CLARIFICATION",
                "originalQuery", "看下方案",
                "clarifyQuestion", "你想看哪个方向？"
        )));
        ChatInteractionRequest nextWaiting = interactionService.prepareInteraction(new ChatInteractionCreateContext(
                user,
                session,
                "run1",
                userMessage,
                previous.assistantMessageId(),
                "intent-agent",
                null,
                "intent-session-1",
                Map.of("sourceType", "intent-clarification-request",
                        "interactionType", "INTENT_CLARIFICATION",
                        "originalQuery", "看下方案",
                        "clarifyQuestion", "你想看哪个方向？")
        ));
        ChatRunTerminalCommitService.TerminalCommitContext commitContext =
                new ChatRunTerminalCommitService.TerminalCommitContext(
                        user,
                        session,
                        new ChatRunMessagePlan(ChatRunMode.NEXT, userMessage.id(), userMessage, null),
                        new AtomicReference<>(),
                        assistant,
                        "run1",
                        new RunExecutionClaim("run1", "instance-test", 1L),
                        previous
                );

        commitService.commitWaitingUser(new ChatRunTerminalCommitService.WaitingUserCommitCommand(
                new RunWaitingUserEvent("run1", session.id(), 0L, now, Map.of(
                        "status", "WAITING_USER",
                        "interactionType", "INTENT_CLARIFICATION",
                        "interactionId", nextWaiting.id(),
                        "messageReady", true,
                        "assistantMessageId", nextWaiting.assistantMessageId())),
                commitContext,
                new ChatRunTerminalCommitService.MessageTarget(true, nextWaiting.assistantMessageId()),
                nextWaiting
        ));

        assertThat(interactionRequests.requests.get(previous.id()).status()).isEqualTo(ChatInteractionStatus.ANSWERED);
        assertThat(interactionRequests.requests.get(nextWaiting.id()).status()).isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(messages.messages).filteredOn(message -> "msg-assistant".equals(message.id())).hasSize(1);
        ChatMessage updatedAssistant = messages.findByOwnerAndId(
                user.tenantId(), user.ownerUserId(), "msg-assistant").orElseThrow();
        assertThat(updatedAssistant.runId()).isEqualTo("run1");
        assertThat(updatedAssistant.parts()).extracting(ChatMessagePart::partType)
                .contains("INTENT_CLARIFICATION_REQUEST");
    }

    private RouteSignalApplicationService systemRouteService() {
        return new RouteSignalApplicationService(request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, user) -> null), new com.huawei.finance.front.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public RouteSignalResult routeInitial(UserContext user, ChatSession session, ChatCommand command,
                                                  List<AttachmentRef> attachments,
                                                  com.huawei.finance.front.one.domain.memory.MemoryContext memory) {
                return RouteSignalResult.of(RouteTarget.systemResponse("partial answer"));
            }

            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                return Flux.just(RouteSignalFrame.result(routeInitial(request.user(), request.session(),
                        request.command(), request.attachments(), request.memory())));
            }
        };
    }

    private FinanceEXChatService stopService(InMemorySessionRepository sessions, InMemoryMessageRepository messages,
                                             InMemoryRunRepository runs, InMemoryEventStore events) {
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.finance.front.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        SessionApplicationService sessionService = new SessionApplicationService(
                sessions, messages, ids, permissionChecker);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(
                events, new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(
                runs, new NeverCancelRunCache(), events, permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry);
        InMemoryInteractionRequestRepository interactionRequests = new InMemoryInteractionRequestRepository();
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(
                interactionRequests, ids, permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService terminalCommitService = new ChatRunTerminalCommitService(
                streamService, sessionService, runs, leaseService, runtimeBindingRepository(), interactionService,
                Duration.ofDays(3));
        AgentRuntimeExecutor runtimeExecutor = new AgentRuntimeExecutor(noopRuntime(), limiter);
        ChatRunStopCoordinator stopCoordinator = new ChatRunStopCoordinator(
                sessionService, streamService, runService, leaseService, executionRegistry,
                runtimeExecutor, interactionService, terminalCommitService, ids);
        return new FinanceEXChatService(
                sessionService,
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                runtimeRouteService(),
                intentRecordService(),
                new SystemResponseExecutor(),
                runtimeExecutor,
                documentFacade(),
                streamService,
                runService,
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                stopCoordinator,
                interactionService,
                terminalCommitService,
                ids,
                reactor.core.scheduler.Schedulers.boundedElastic(),
                new com.huawei.finance.front.one.application.config.DomainAgentProperties(),
                null
        );
    }

    private void seedRunningRun(InMemorySessionRepository sessions, InMemoryMessageRepository messages,
                                InMemoryRunRepository runs, UserContext user,
                                String runId, String sessionId, String userMessageId) {
        Instant now = Instant.parse("2026-06-11T00:00:00Z");
        sessions.save(new ChatSession(sessionId, user.tenantId(), user.ownerUserId(), "测试会话", "ACTIVE", "web",
                userMessageId, sessionId, null, null, 1L, null, now, now));
        messages.save(new ChatMessage(userMessageId, user.tenantId(), user.ownerUserId(), sessionId,
                null, 1L, 0, 1, "user", "帮我处理一下", null, runId,
                "NORMAL", false, null, null, null, null, null, now));
        runs.save(new ChatRun(runId, user.tenantId(), user.ownerUserId(), sessionId, ChatRunStatus.RUNNING,
                "AGENT_RUNTIME", null, "relay", null, ChatRunMode.NEXT, null, userMessageId,
                null, null, null, null, now, null, Map.of(), now, now));
    }

    private RouteSignalApplicationService runtimeRouteService() {
        return new RouteSignalApplicationService(request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, user) -> null), new com.huawei.finance.front.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public RouteSignalResult routeInitial(UserContext user, ChatSession session, ChatCommand command,
                                                  List<AttachmentRef> attachments,
                                                  com.huawei.finance.front.one.domain.memory.MemoryContext memory) {
                return RouteSignalResult.of(RouteTarget.agentRuntime("test-runtime"));
            }

            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                return Flux.just(RouteSignalFrame.result(routeInitial(request.user(), request.session(),
                        request.command(), request.attachments(), request.memory())));
            }
        };
    }

    private IntentRecognitionRecordService intentRecordService() {
        return new IntentRecognitionRecordService(new IntentRecordProperties(), record -> {
        }, new FixedIdGenerator(), new ObjectMapper(), Runnable::run);
    }

    private DocumentFacade documentFacade() {
        return new DocumentFacade() {
            @Override public Mono<UploadedDocument> upload(UserContext user, DocumentUploadCommand command) { return Mono.empty(); }
            @Override public Mono<DocumentLibraryPage> list(UserContext user, DocumentLibraryQuery query) { return Mono.empty(); }
            @Override public Mono<UploadedDocument> get(UserContext user, String documentId) { return Mono.empty(); }
            @Override public Mono<UploadedDocument> update(UserContext user, String documentId, DocumentUpdateCommand command) { return Mono.empty(); }
            @Override public Mono<UploadedDocument> delete(UserContext user, String documentId) { return Mono.empty(); }
            @Override public Mono<com.huawei.finance.front.one.domain.document.DocumentDownload> prepareDownload(UserContext user, String documentId) { return Mono.empty(); }
            @Override public Mono<UploadedDocument> prepareAccess(UserContext user, String documentId) { return Mono.empty(); }
            @Override public Mono<StoredObjectContent> download(UserContext user, String documentId) { return Mono.empty(); }
            @Override public List<AttachmentRef> resolveAttachmentsForUser(UserContext user, List<AttachmentRef> attachments) { return List.of(); }
            @Override public List<UploadedDocument> resolveDocumentsForUser(UserContext user, List<AttachmentRef> attachments) { return List.of(); }
        };
    }

    private DomainAgentExecutor domainAgentExecutor(DocumentFacade documentFacade, WorkloadConcurrencyLimiter limiter) {
        return new DomainAgentExecutor(new DomainAgentClient() {
            @Override public Flux<ChatEvent> query(DomainAgentRequest request) { return Flux.empty(); }
            @Override public Mono<Void> cancel(DomainAgentCancelRequest request) { return Mono.empty(); }
        }, documentFacade, limiter);
    }

    private void awaitEvent(InMemoryEventStore events, String type) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (events.events.stream().anyMatch(event -> type.equals(event.type()))) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for event " + type, ex);
            }
        }
        throw new AssertionError("Timed out waiting for event " + type + ", actual events="
                + events.events.stream().map(ChatEvent::type).toList());
    }

    private void awaitInteractionStatus(InMemoryInteractionRequestRepository interactions, String interactionId,
                                        ChatInteractionStatus status) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            ChatInteractionRequest request = interactions.requests.get(interactionId);
            if (request != null && request.status() == status) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for Interaction status " + status, ex);
            }
        }
        ChatInteractionRequest actual = interactions.requests.get(interactionId);
        throw new AssertionError("Timed out waiting for Interaction status " + status
                + ", actual=" + (actual == null ? null : actual.status())
                + ", continueRunId=" + (actual == null ? null : actual.continueRunId())
                + ", markWaitingForRunCalls=" + interactions.markWaitingForRunCalls.get());
    }

    private RouteSignalApplicationService countingRuntimeRouteService(AtomicInteger routeCalls) {
        return new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.finance.front.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                return Flux.defer(() -> {
                    routeCalls.incrementAndGet();
                    return Flux.just(RouteSignalFrame.result(
                            RouteSignalResult.of(RouteTarget.agentRuntime("test-runtime"))));
                });
            }
        };
    }

    private FinanceEXChatService defaultFinanceService(InMemorySessionRepository sessions,
                                                       InMemoryMessageRepository messages,
                                                       InMemoryRunRepository runs,
                                                       InMemoryEventStore events) {
        return defaultFinanceService(sessions, messages, runs, events, runtimeRouteService());
    }

    private FinanceEXChatService defaultFinanceService(InMemorySessionRepository sessions,
                                                       InMemoryMessageRepository messages,
                                                       InMemoryRunRepository runs,
                                                       InMemoryEventStore events,
                                                       RouteSignalApplicationService routeService) {
        return defaultFinanceService(sessions, messages, runs, events, routeService, noopRuntime());
    }

    private FinanceEXChatService defaultFinanceService(InMemorySessionRepository sessions,
                                                       InMemoryMessageRepository messages,
                                                       InMemoryRunRepository runs,
                                                       InMemoryEventStore events,
                                                       RouteSignalApplicationService routeService,
                                                       AgentRuntime runtime) {
        return defaultFinanceService(sessions, messages, runs, events, routeService, runtime, true);
    }

    private FinanceEXChatService defaultFinanceService(InMemorySessionRepository sessions,
                                                       InMemoryMessageRepository messages,
                                                       InMemoryRunRepository runs,
                                                       InMemoryEventStore events,
                                                       RouteSignalApplicationService routeService,
                                                       AgentRuntime runtime,
                                                       boolean legacyDomainAgentCompatibility) {
        return defaultFinanceService(sessions, messages, runs, events, routeService, runtime,
                legacyDomainAgentCompatibility, new NeverCancelRunCache());
    }

    private FinanceEXChatService defaultFinanceService(InMemorySessionRepository sessions,
                                                       InMemoryMessageRepository messages,
                                                       InMemoryRunRepository runs,
                                                       InMemoryEventStore events,
                                                       RouteSignalApplicationService routeService,
                                                       AgentRuntime runtime,
                                                       boolean legacyDomainAgentCompatibility,
                                                       ChatRunCache runCache) {
        return defaultFinanceService(sessions, messages, runs, events, routeService, runtime,
                legacyDomainAgentCompatibility, runCache, new InMemoryExecutionRepository());
    }

    private FinanceEXChatService defaultFinanceService(InMemorySessionRepository sessions,
                                                       InMemoryMessageRepository messages,
                                                       InMemoryRunRepository runs,
                                                       InMemoryEventStore events,
                                                       RouteSignalApplicationService routeService,
                                                       AgentRuntime runtime,
                                                       boolean legacyDomainAgentCompatibility,
                                                       ChatRunCache runCache,
                                                       InMemoryExecutionRepository executions) {
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.finance.front.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry
        );
        DocumentFacade documents = documentFacade();
        AgentRuntimeExecutor runtimeExecutor = new AgentRuntimeExecutor(runtime, limiter);
        if (!legacyDomainAgentCompatibility) {
            return new FinanceEXChatService(
                    new SessionApplicationService(sessions, messages, ids, permissionChecker),
                    new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                    new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids,
                            Duration.ofDays(3), "relay"),
                    routeService,
                    intentRecordService(),
                    new SystemResponseExecutor(),
                    runtimeExecutor,
                    documents,
                    new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                            permissionChecker, sessions,
                            new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()),
                    new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                    leaseService,
                    new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                    executionRegistry,
                    new RunAdmissionControlService(new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                    ids
            );
        }
        return new FinanceEXChatService(
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids,
                        Duration.ofDays(3), "relay"),
                routeService,
                intentRecordService(),
                domainAgentExecutor(documents, limiter),
                new SystemResponseExecutor(),
                runtimeExecutor,
                documents,
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                ids
        );
    }

    private FinanceEXChatService financeServiceWithTerminalCommit(InMemorySessionRepository sessions,
                                                                   InMemoryMessageRepository messages,
                                                                   InMemoryRunRepository runs,
                                                                   InMemoryEventStore events,
                                                                   RouteSignalApplicationService routeService,
                                                                   AgentRuntime runtime) {
        return financeServiceWithTerminalCommit(sessions, messages, runs, events, routeService, runtime,
                new InMemoryExecutionRepository());
    }

    private FinanceEXChatService financeServiceWithTerminalCommit(InMemorySessionRepository sessions,
                                                                   InMemoryMessageRepository messages,
                                                                   InMemoryRunRepository runs,
                                                                   InMemoryEventStore events,
                                                                   RouteSignalApplicationService routeService,
                                                                   AgentRuntime runtime,
                                                                   InMemoryExecutionRepository executions) {
        return financeServiceWithTerminalCommit(sessions, messages, runs, events, routeService, runtime,
                executions, new ChatRunOperationalProperties());
    }

    private FinanceEXChatService financeServiceWithTerminalCommit(InMemorySessionRepository sessions,
                                                                   InMemoryMessageRepository messages,
                                                                   InMemoryRunRepository runs,
                                                                   InMemoryEventStore events,
                                                                   RouteSignalApplicationService routeService,
                                                                   AgentRuntime runtime,
                                                                   InMemoryExecutionRepository executions,
                                                                   ChatRunOperationalProperties runProperties) {
        return financeServiceWithTerminalCommit(sessions, messages, runs, events, routeService, runtime,
                executions, runProperties, new NeverCancelRunCache());
    }

    private FinanceEXChatService financeServiceWithTerminalCommit(InMemorySessionRepository sessions,
                                                                   InMemoryMessageRepository messages,
                                                                   InMemoryRunRepository runs,
                                                                   InMemoryEventStore events,
                                                                   RouteSignalApplicationService routeService,
                                                                   AgentRuntime runtime,
                                                                   InMemoryExecutionRepository executions,
                                                                   ChatRunOperationalProperties runProperties,
                                                                   ChatRunCache runCache) {
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.finance.front.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        RuntimeBindingRepository bindings = runtimeBindingRepository();
        InMemoryInteractionRequestRepository interactionRequests = new InMemoryInteractionRequestRepository();
        SessionApplicationService sessionService = new SessionApplicationService(
                sessions, messages, ids, permissionChecker);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(
                events, new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(
                runs, runCache, events, permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                runProperties,
                ids,
                executionRegistry
        );
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(
                interactionRequests, ids, permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService terminalCommitService = new ChatRunTerminalCommitService(
                streamService, sessionService, runs, leaseService, bindings, interactionService, Duration.ofDays(3));
        AgentRuntimeExecutor runtimeExecutor = new AgentRuntimeExecutor(runtime, limiter);
        ChatRunStopCoordinator stopCoordinator = new ChatRunStopCoordinator(
                sessionService, streamService, runService, leaseService, executionRegistry,
                runtimeExecutor, interactionService, terminalCommitService, ids);
        return new FinanceEXChatService(
                sessionService,
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(bindings, runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                routeService,
                intentRecordService(),
                new SystemResponseExecutor(),
                runtimeExecutor,
                documentFacade(),
                streamService,
                runService,
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(
                        new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                stopCoordinator,
                interactionService,
                terminalCommitService,
                ids,
                reactor.core.scheduler.Schedulers.boundedElastic(),
                new com.huawei.finance.front.one.application.config.DomainAgentProperties(),
                null,
                runProperties
        );
    }

    private FinanceEXChatService financeServiceWithDomainClient(InMemorySessionRepository sessions,
                                                                InMemoryMessageRepository messages,
                                                                InMemoryRunRepository runs,
                                                                InMemoryEventStore events,
                                                                RouteSignalApplicationService routeService,
                                                                DomainAgentClient domainClient,
                                                                AgentRuntime relayRuntime) {
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.finance.front.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                new InMemoryExecutionRepository(),
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry);
        DocumentFacade documents = documentFacade();
        return new FinanceEXChatService(
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids,
                        Duration.ofDays(3), "relay"),
                routeService,
                intentRecordService(),
                new DomainAgentExecutor(domainClient, documents, limiter),
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(relayRuntime, limiter),
                documents,
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, new NeverCancelRunCache(), events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                ids);
    }

    private AgentRuntime noopRuntime() {
        return new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) { return Flux.empty(); }
            @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
        };
    }

    private ChatLiveEventBus liveEventBus() {
        return new ChatLiveEventBus() {
            @Override public void publish(String topicId, ChatEvent event) {}
            @Override public Flux<ChatEvent> subscribe(String topicId) { return Flux.never(); }
        };
    }

    private LongTermMemoryStore longTermMemory() {
        return new LongTermMemoryStore() {
            @Override public List<LongTermMemoryItem> searchRelevant(String tenantId, String userId, String query, int topK) { return List.of(); }
            @Override public void save(LongTermMemoryItem item) {}
        };
    }

    private RuntimeBindingRepository runtimeBindingRepository() {
        return new RuntimeBindingRepository() {
            @Override public Optional<RuntimeBinding> findById(String bindingId) { return Optional.empty(); }
            @Override public RuntimeBinding save(RuntimeBinding binding) { return binding; }
            @Override public Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId, String provider) { return Optional.empty(); }
        };
    }

    private RuntimeBindingCache runtimeBindingCache() {
        return new RuntimeBindingCache() {
            @Override public Optional<RuntimeBinding> get(String tenantId, String userId, String sessionId) { return Optional.empty(); }
            @Override public void put(RuntimeBinding binding) {}
            @Override public void evict(String tenantId, String userId, String sessionId) {}
        };
    }

    private static class CountingCancelRunCache implements ChatRunCache {
        private final AtomicInteger checks = new AtomicInteger();
        @Override public Optional<ChatRun> getActive(String tenantId, String userId, String sessionId) { return Optional.empty(); }
        @Override public boolean tryClaimActive(ChatRun run) { return true; }
        @Override public void putActive(ChatRun run) {}
        @Override public void evictActive(String tenantId, String userId, String sessionId) {}
        @Override public void markCancellationRequested(String runId) {}
        @Override public ChatRunCancelSignal cancellationSignal(String runId) {
            return checks.incrementAndGet() >= 4 ? ChatRunCancelSignal.REQUESTED : ChatRunCancelSignal.NOT_REQUESTED;
        }
    }

    private static class NeverCancelRunCache implements ChatRunCache {
        @Override public Optional<ChatRun> getActive(String tenantId, String userId, String sessionId) { return Optional.empty(); }
        @Override public boolean tryClaimActive(ChatRun run) { return true; }
        @Override public void putActive(ChatRun run) {}
        @Override public void evictActive(String tenantId, String userId, String sessionId) {}
        @Override public void markCancellationRequested(String runId) {}
        @Override public ChatRunCancelSignal cancellationSignal(String runId) {
            return ChatRunCancelSignal.NOT_REQUESTED;
        }
    }

    private static final class BlockingPutRunCache extends NeverCancelRunCache {
        private final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        @Override
        public void putActive(ChatRun run) {
            entered.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean awaitPut() {
            try {
                return entered.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void releasePut() {
            release.countDown();
        }
    }

    private static class AlwaysCancelledRunCache extends NeverCancelRunCache {
        @Override public ChatRunCancelSignal cancellationSignal(String runId) {
            return ChatRunCancelSignal.REQUESTED;
        }
    }

    private static class InMemoryRunRepository implements ChatRunRepository {
        private final Map<String, ChatRun> runs = new HashMap<>();
        private final AtomicInteger ownerTerminalFenceAttempts = new AtomicInteger();
        private boolean rejectOwnerTerminalFences;
        @Override public synchronized ChatRun save(ChatRun run) { runs.put(run.id(), run); return run; }
        @Override public synchronized boolean tryFenceOwnerTerminalCommit(OwnerTerminalFence fence) {
            ownerTerminalFenceAttempts.incrementAndGet();
            ChatRun current = runs.get(fence.runId());
            if (current == null || current.status() != ChatRunStatus.RUNNING) {
                return false;
            }
            if (rejectOwnerTerminalFences) {
                runs.put(current.id(), current.cancelling("TEST_STOP"));
                return false;
            }
            return true;
        }
        @Override public synchronized Optional<ChatRun> findById(String runId) { return Optional.ofNullable(runs.get(runId)); }
        @Override public Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId) {
            return findById(runId).filter(run -> tenantId.equals(run.tenantId())).filter(run -> userId.equals(run.userId()));
        }
        @Override public Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId) { return Optional.empty(); }
    }

    private static class FailingResolvedRouteRunRepository extends InMemoryRunRepository {
        @Override
        public ChatRun save(ChatRun run) {
            if (run != null && run.status() == ChatRunStatus.RUNNING && run.routeType() != null
                    && run.firstSeq() != null) {
                throw new IllegalStateException("route diagnostic db down");
            }
            return super.save(run);
        }
    }

    private static class InMemoryEventStore implements ChatEventStore {
        private long seq;
        private final List<ChatEvent> events = new CopyOnWriteArrayList<>();
        @Override public ChatEvent append(ChatEvent event) {
            ChatEvent stored = new StoredChatEvent(event.runId(), event.sessionId(), ++seq, event.type(), Instant.now(), event.payload());
            events.add(stored);
            return stored;
        }
        @Override public ChatEvent appendWithExecutionGuard(ChatEvent event, com.huawei.finance.front.one.domain.chat.RunExecutionClaim claim) { return append(event); }
        @Override public List<ChatEvent> findByOwnerAndSessionAfterSeq(String tenantId, String userId, String sessionId, long afterSeq) {
            return events.stream()
                    .filter(event -> sessionId.equals(event.sessionId()))
                    .filter(event -> event.sequence() > afterSeq)
                    .toList();
        }
        @Override public List<ChatEvent> findByOwnerAndRunAfterSeq(String tenantId, String userId, String sessionId, String runId, long afterSeq) {
            return events.stream()
                    .filter(event -> sessionId.equals(event.sessionId()))
                    .filter(event -> runId.equals(event.runId()))
                    .filter(event -> event.sequence() > afterSeq)
                    .toList();
        }
        @Override public long findLatestSeqByOwnerAndSession(String tenantId, String userId, String sessionId) { return seq; }
    }

    private static class RejectingRunStartedEventStore extends InMemoryEventStore {
        private final AtomicInteger guardedAttempts = new AtomicInteger();

        @Override
        public ChatEvent appendWithExecutionGuard(ChatEvent event, RunExecutionClaim claim) {
            guardedAttempts.incrementAndGet();
            if (event != null && "run.started".equals(event.type())) {
                throw new ChatEventAppendRejectedException("test fencing rejection");
            }
            return super.appendWithExecutionGuard(event, claim);
        }
    }

    private static final class BlockingRunStartedEventStore extends InMemoryEventStore {
        private final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        @Override
        public ChatEvent appendWithExecutionGuard(ChatEvent event, RunExecutionClaim claim) {
            if (event != null && "run.started".equals(event.type())) {
                boolean interrupted = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException ex) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw new ChatEventAppendRejectedException("test delayed run.started rejection");
            }
            return super.appendWithExecutionGuard(event, claim);
        }

        private void releaseRunStarted() {
            release.countDown();
        }
    }

    private static class InMemoryExecutionRepository implements ChatRunExecutionRepository {
        private final Map<String, ChatRunExecution> executions = new HashMap<>();
        private volatile boolean rejectOwnerRunningChecks;

        @Override
        public ChatRunExecution createForRun(ChatRun run, String executionId, String ownerInstanceId, Duration leaseDuration) {
            Instant now = Instant.now();
            ChatRunExecution execution = new ChatRunExecution(executionId, run.id(), run.tenantId(), run.userId(),
                    run.sessionId(), ChatRunExecutionStatus.RUNNING, ownerInstanceId, now, now.plus(leaseDuration),
                    1L, null, null, 0, null, null, Map.of(), now, now);
            executions.put(run.id(), execution);
            return execution;
        }

        @Override public Optional<ChatRunExecution> findByRunId(String runId) { return Optional.ofNullable(executions.get(runId)); }
        @Override public boolean isCurrentOwnerRunning(RunExecutionClaim claim) {
            return !rejectOwnerRunningChecks && ChatRunExecutionRepository.super.isCurrentOwnerRunning(claim);
        }
        @Override public boolean heartbeat(String runId, String ownerInstanceId, Duration leaseDuration) { return true; }
        @Override public boolean markTerminal(String runId, ChatRunExecutionStatus terminalStatus) {
            ChatRunExecution current = executions.get(runId);
            if (current == null || terminalStatus == null) {
                return false;
            }
            ChatRunExecution next = new ChatRunExecution(current.id(), current.runId(), current.tenantId(),
                    current.userId(), current.sessionId(), terminalStatus, current.ownerInstanceId(),
                    current.heartbeatAt(), current.leaseUntil(), current.fencingToken(),
                    current.recoveryStrategy(), current.recoveredByInstanceId(), current.recoveryAttempts(),
                    current.recoveryLeaseUntil(), current.runtimeResumeToken(), current.metadata(),
                    current.createdAt(), Instant.now());
            executions.put(runId, next);
            return true;
        }
        @Override public List<ChatRunExecution> findLeaseExpired(int limit) { return List.of(); }
        @Override public List<ChatRunExecution> findRecoveryExpired(int limit) { return List.of(); }
        @Override public Optional<ChatRunExecution> tryClaimRecovering(String runId, String recoveredByInstanceId, String strategy, Duration recoveryLeaseDuration) { return Optional.empty(); }
        @Override public Optional<ChatRunExecution> markTakeoverRunning(String runId, String ownerInstanceId, Duration leaseDuration) { return Optional.empty(); }
        @Override public boolean isLeaseExpired(String runId, Instant now) { return false; }
    }

    private static final class FailingExecutionRepository extends InMemoryExecutionRepository {
        @Override
        public ChatRunExecution createForRun(ChatRun run, String executionId, String ownerInstanceId,
                                             Duration leaseDuration) {
            throw new IllegalStateException("execution db down");
        }
    }

    private static class InMemorySessionRepository implements SessionRepository {
        private final Map<String, ChatSession> sessions = new HashMap<>();
        @Override public Optional<ChatSession> findById(String sessionId) { return Optional.ofNullable(sessions.get(sessionId)); }
        @Override public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
            return findById(sessionId).filter(session -> tenantId.equals(session.tenantId())).filter(session -> userId.equals(session.userId()));
        }
        @Override public List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId) { return List.of(); }
        @Override public ChatSessionPage pageByTenantIdAndUserId(String tenantId, String userId, String cursor, int limit) {
            return new ChatSessionPage(List.of(), null);
        }
        @Override public ChatSession save(ChatSession session) { sessions.put(session.id(), session); return session; }
    }

    private static final class BlockingSessionRepository extends InMemorySessionRepository {
        private final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        private volatile boolean blockReads;

        @Override
        public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
            if (blockReads) {
                boolean interrupted = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException ex) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.findByTenantIdAndUserIdAndId(tenantId, userId, sessionId);
        }

        private void blockReads() {
            blockReads = true;
        }

        private void releaseReads() {
            release.countDown();
        }
    }

    private static class CapturingRuntimeBindingRepository implements RuntimeBindingRepository {
        private RuntimeBinding saved;
        private final List<RuntimeBinding> savedHistory = new CopyOnWriteArrayList<>();

        @Override public Optional<RuntimeBinding> findById(String bindingId) {
            return Optional.ofNullable(saved).filter(binding -> binding.id().equals(bindingId));
        }
        @Override public Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId, String provider) {
            return Optional.ofNullable(saved)
                    .filter(binding -> tenantId.equals(binding.tenantId()))
                    .filter(binding -> userId.equals(binding.userId()))
                    .filter(binding -> sessionId.equals(binding.chatSessionId()))
                    .filter(binding -> provider.equals(binding.provider()))
                    .filter(binding -> binding.status() == RuntimeBindingStatus.ACTIVE);
        }
        @Override public List<RuntimeBinding> findActiveBySession(String tenantId, String userId, String sessionId,
                                                                  String provider) {
            return findActive(tenantId, userId, sessionId, provider).stream().toList();
        }
        @Override public List<RuntimeBinding> findActiveBySession(String tenantId, String userId, String sessionId) {
            return Optional.ofNullable(saved)
                    .filter(binding -> tenantId.equals(binding.tenantId()))
                    .filter(binding -> userId.equals(binding.userId()))
                    .filter(binding -> sessionId.equals(binding.chatSessionId()))
                    .filter(binding -> binding.status() == RuntimeBindingStatus.ACTIVE)
                    .stream()
                    .toList();
        }
        @Override public RuntimeBinding save(RuntimeBinding binding) {
            saved = binding;
            savedHistory.add(binding);
            return binding;
        }
    }

    private static class InMemoryInteractionRequestRepository implements ChatInteractionRequestRepository {
        private final Map<String, ChatInteractionRequest> requests = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicInteger markWaitingForRunCalls = new AtomicInteger();

        @Override public ChatInteractionRequest insert(ChatInteractionRequest request) {
            requests.put(request.id(), request);
            return request;
        }
        @Override public Optional<ChatInteractionRequest> findByOwnerAndId(String tenantId, String userId, String interactionId) {
            return Optional.ofNullable(requests.get(interactionId))
                    .filter(request -> tenantId.equals(request.tenantId()))
                    .filter(request -> userId.equals(request.userId()));
        }
        @Override public Optional<ChatInteractionRequest> findWaitingBySession(String tenantId, String userId, String sessionId) {
            return requests.values().stream()
                    .filter(request -> tenantId.equals(request.tenantId()))
                    .filter(request -> userId.equals(request.userId()))
                    .filter(request -> sessionId.equals(request.sessionId()))
                    .filter(ChatInteractionRequest::waiting)
                    .findFirst();
        }
        @Override public boolean claimInteractionResponse(ChatInteractionClaimCommand command) {
            ChatInteractionRequest current = requests.get(command.interactionId());
            if (current == null || !command.tenantId().equals(current.tenantId())
                    || !command.userId().equals(current.userId()) || !current.waiting()) {
                return false;
            }
            requests.put(current.id(), new ChatInteractionRequest(current.id(), current.tenantId(), current.userId(),
                    current.sessionId(), current.sourceRunId(), command.continueRunId(), current.userMessageId(),
                    current.assistantMessageId(), current.runtimeProvider(), current.runtimeBindingId(),
                    current.runtimeSessionId(), current.approvalId(), current.interactionType(), ChatInteractionStatus.RESPONDING,
                    current.requestPayload(), command.responsePayload(), current.expiresAt(), current.answeredAt(),
                    current.cancelledAt(), current.createdAt(), command.now()));
            return true;
        }
        @Override public int markAnswered(String tenantId, String userId, String interactionId, Instant answeredAt) {
            ChatInteractionRequest current = requests.get(interactionId);
            if (current == null || !tenantId.equals(current.tenantId()) || !userId.equals(current.userId())) {
                return 0;
            }
            requests.put(interactionId, new ChatInteractionRequest(current.id(), current.tenantId(), current.userId(),
                    current.sessionId(), current.sourceRunId(), current.continueRunId(), current.userMessageId(),
                    current.assistantMessageId(), current.runtimeProvider(), current.runtimeBindingId(),
                    current.runtimeSessionId(), current.approvalId(), current.interactionType(), ChatInteractionStatus.ANSWERED,
                    current.requestPayload(), current.responsePayload(), current.expiresAt(), answeredAt,
                    current.cancelledAt(), current.createdAt(), Instant.now()));
            return 1;
        }
        @Override public int markWaiting(String tenantId, String userId, String interactionId) {
            ChatInteractionRequest current = requests.get(interactionId);
            if (current == null || !tenantId.equals(current.tenantId()) || !userId.equals(current.userId())) {
                return 0;
            }
            requests.put(interactionId, new ChatInteractionRequest(current.id(), current.tenantId(), current.userId(),
                    current.sessionId(), current.sourceRunId(), null, current.userMessageId(),
                    current.assistantMessageId(), current.runtimeProvider(), current.runtimeBindingId(),
                    current.runtimeSessionId(), current.approvalId(), current.interactionType(), ChatInteractionStatus.WAITING,
                    current.requestPayload(), current.responsePayload(), current.expiresAt(), current.answeredAt(),
                    current.cancelledAt(), current.createdAt(), Instant.now()));
            return 1;
        }
        @Override public int markWaitingForRun(String tenantId, String userId, String interactionId,
                                               String continueRunId) {
            markWaitingForRunCalls.incrementAndGet();
            ChatInteractionRequest current = requests.get(interactionId);
            if (current == null || !tenantId.equals(current.tenantId()) || !userId.equals(current.userId())
                    || current.status() != ChatInteractionStatus.RESPONDING
                    || !continueRunId.equals(current.continueRunId())) {
                return 0;
            }
            return markWaiting(tenantId, userId, interactionId);
        }
        @Override public int cancelOpenBySession(String tenantId, String userId, String sessionId, Instant cancelledAt) { return 0; }
        @Override public int markExpired(String tenantId, String userId, String interactionId) { return 0; }
    }

    private static class InMemoryMessageRepository implements ChatMessageRepository {
        private final List<ChatMessage> messages = new ArrayList<>();
        private final List<ChatMessagePart> parts = new ArrayList<>();
        @Override public ChatMessage save(ChatMessage message) {
            messages.add(message);
            if (message.parts() != null) {
                parts.addAll(message.parts());
            }
            return message;
        }
        @Override public ChatMessage updateAssistantMessage(ChatMessage message) {
            for (int index = 0; index < messages.size(); index++) {
                ChatMessage existing = messages.get(index);
                if (!message.id().equals(existing.id())) {
                    continue;
                }
                List<ChatMessagePart> mergedParts = new ArrayList<>(
                        existing.parts() == null ? List.of() : existing.parts());
                if (message.parts() != null) {
                    mergedParts.addAll(message.parts());
                    parts.addAll(message.parts());
                }
                ChatMessage updated = message.withParts(List.copyOf(mergedParts));
                messages.set(index, updated);
                return updated;
            }
            throw new IllegalArgumentException("assistant 消息不存在: " + message.id());
        }
        @Override public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
            return messages.stream().filter(message -> sessionId.equals(message.sessionId()))
                    .sorted(Comparator.comparing(ChatMessage::createdAt).reversed()).limit(limit).toList();
        }
        @Override public ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String cursor, int limit) {
            return new ChatMessagePage(messages, null);
        }
        @Override public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
            return messages.stream().filter(message -> messageId.equals(message.id())).findFirst();
        }
    }

    private static class FailingMessageRepository extends InMemoryMessageRepository {
        private boolean failSaves;

        @Override
        public ChatMessage save(ChatMessage message) {
            if (failSaves) {
                throw new IllegalStateException("message db down");
            }
            return super.save(message);
        }
    }

    private static class FixedIdGenerator implements IdGenerator {
        @Override public String newId(String prefix, IdGenerateContext context) { return prefix + "_1"; }
    }
}
