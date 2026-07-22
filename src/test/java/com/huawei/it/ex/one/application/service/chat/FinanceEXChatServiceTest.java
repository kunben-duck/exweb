package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.command.DocumentUpdateCommand;
import com.huawei.it.ex.one.application.command.DocumentUploadCommand;
import com.huawei.it.ex.one.application.config.ChatInteractionProperties;
import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.config.IntentFailureStrategy;
import com.huawei.it.ex.one.application.config.IntentRecordProperties;
import com.huawei.it.ex.one.application.config.MemoryProperties;
import com.huawei.it.ex.one.application.config.RouteSignalProperties;
import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.facade.ResolvedChatAttachments;
import com.huawei.it.ex.one.application.integration.agent.AgentModeBindingContext;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteraction;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentClient;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventStore;
import com.huawei.it.ex.one.application.integration.conversation.ChatInteractionRequestRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunCache;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.application.integration.intent.IntentService;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.application.integration.memory.LongTermMemoryStore;
import com.huawei.it.ex.one.application.integration.runtime.RuntimeBindingCache;
import com.huawei.it.ex.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.it.ex.one.application.service.memory.MemoryApplicationService;
import com.huawei.it.ex.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.it.ex.one.application.service.routing.IntentRecognitionRecordService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalFrame;
import com.huawei.it.ex.one.application.service.routing.RouteSignalRequest;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.DomainAgentExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.SystemResponseExecutor;
import com.huawei.it.ex.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatInteractionUnavailableException;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessageAttachment;
import com.huawei.it.ex.one.domain.chat.ChatMessagePage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatMessagePartDraft;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunCancelSignal;
import com.huawei.it.ex.one.domain.chat.ChatRunExecution;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStopResult;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ChatSessionPage;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.chat.RunCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RunStartedEvent;
import com.huawei.it.ex.one.domain.chat.RunWaitingUserEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;
import com.huawei.it.ex.one.domain.document.DocumentLibraryPage;
import com.huawei.it.ex.one.domain.document.DocumentLibraryQuery;
import com.huawei.it.ex.one.domain.document.StoredObjectContent;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.memory.LongTermMemoryItem;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.AgentModeSelection;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;
import com.huawei.it.ex.one.domain.usecase.UseCaseMatchResult;
import com.huawei.it.ex.one.infrastructure.runtime.intentagent.BlockingIntentAgentRuntime;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
class FinanceEXChatServiceTest {
    @Test
    void clarificationAnswerUsesTrustedAttachmentNamesForTextAndAttachmentOnlyTurns() {
        List<AttachmentRef> attachments = List.of(
                new AttachmentRef("doc1", "方案.pdf", "application/pdf", 1L),
                new AttachmentRef("doc2", "测算.xls", "application/vnd.ms-excel", 2L));

        assertThat(FinanceEXChatService.clarificationAnswerWithAttachments(null, attachments))
                .isEqualTo("[用户上传文档] 方案.pdf，测算.xls");
        assertThat(FinanceEXChatService.clarificationAnswerWithAttachments("帮我看下这个文档", attachments))
                .isEqualTo("帮我看下这个文档 [用户上传文档] 方案.pdf，测算.xls");
        assertThat(FinanceEXChatService.nextMessageWithAttachments(ChatRunMode.NEXT, null, attachments))
                .isEmpty();
        assertThat(FinanceEXChatService.nextMessageWithAttachments(ChatRunMode.NEXT, "  ", attachments))
                .isEmpty();
        assertThat(FinanceEXChatService.nextMessageWithAttachments(ChatRunMode.NEXT, "分析文档", attachments))
                .isEqualTo("分析文档");
        assertThat(FinanceEXChatService.nextMessageWithAttachments(ChatRunMode.EDIT_USER, null, attachments))
                .isNull();
        assertThat(FinanceEXChatService.nextMessageWithAttachments(ChatRunMode.NEXT, null, List.of()))
                .isNull();
    }

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
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
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
        assertThat(sessions.sessions.values()).singleElement()
                .satisfies(session -> {
                    assertThat(session.latestMessageSeq()).isZero();
                    assertThat(session.hasUnread()).isFalse();
                });
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
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        SessionApplicationService sessionService = new SessionApplicationService(sessions, messages, ids,
                permissionChecker);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.it.ex.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(), events,
                permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(), ids,
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
                "msg-user", "msg-assistant", "relay", null, null, null,
                ChatInteractionType.AGENT_CLARIFICATION, ChatInteractionStatus.WAITING,
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
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        SessionApplicationService sessionService = new SessionApplicationService(sessions, messages, ids,
                permissionChecker);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.it.ex.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(), events,
                permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(), ids,
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
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                new ChatRunStopCoordinator(sessionService, streamService, runService, leaseService, executionRegistry,
                        new AgentRuntimeExecutor(noopRuntime(), limiter), interactionService, ids),
                interactionService, terminalCommitService, ids, reactor.core.scheduler.Schedulers.boundedElastic(),
                new com.huawei.it.ex.one.application.config.DomainAgentProperties());
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
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.ANSWERED);
        assertThat(messages.messages)
                .filteredOn(message -> "user".equals(message.role()) && "账务".equals(message.content()))
                .singleElement()
                .extracting(ChatMessage::parentMessageId)
                .isEqualTo("msg-assistant");
    }

    @Test
    void interactionAppTagMismatchIsRejectedBeforeClaim() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        Instant now = Instant.now();
        ChatSession session = sessions.save(new ChatSession(
                "session1", user.tenantId(), user.ownerUserId(), "测试会话", "ACTIVE", "web",
                "fund-app", "资金助手", null, "session1", null, null, 0L, null, now, now));
        ChatInteractionRequest waiting = new ChatInteractionRequest(
                "interaction1", user.tenantId(), user.ownerUserId(), session.id(), "run-source", null,
                "msg-user", "msg-assistant", "intent-agent", null, null, null,
                ChatInteractionType.INTENT_CLARIFICATION, ChatInteractionStatus.WAITING,
                Map.of("originalQuery", "原问题", "clarifyQuestion", "请补充范围"), Map.of(),
                now.plus(Duration.ofHours(1)), null, null, now, now);
        interactions.insert(waiting);
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, runtimeRouteService(), refusingDomainAgentClient(),
                noopRuntime(), runtimeBindingRepository(),
                new com.huawei.it.ex.one.application.config.DomainAgentProperties(), liveEventBus(),
                interactions);

        assertThatThrownBy(() -> service.startRun(user, new ChatCommand(
                        null, null, null, "another-session", null, "web", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, waiting.id(), null, null, Map.of("请补充范围", "账务")),
                        RuntimeForwardHeaders.empty()).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId 与 Interaction 所属会话不一致");

        assertThatThrownBy(() -> service.startRun(user, new ChatCommand(
                        null, null, null, session.id(), null, "web", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, waiting.id(), null, null, Map.of("请补充范围", "账务"),
                        "tax-app", "税务助手"), RuntimeForwardHeaders.empty()).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appId 与已有会话不一致");

        assertThat(interactions.claimCalls).hasValue(0);
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(runs.runs).isEmpty();
        assertThat(events.events).isEmpty();
    }

    @Test
    void currentAttachmentFailureKeepsWaitingButHistoricalFailureCancelsInteraction() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        Instant now = Instant.now();
        ChatSession session = sessions.save(new ChatSession(
                "session1", user.tenantId(), user.ownerUserId(), "测试会话", "ACTIVE", "web", now, now));
        ChatInteractionRequest waiting = new ChatInteractionRequest(
                "interaction1", user.tenantId(), user.ownerUserId(), session.id(), "run-source", null,
                "msg-user", "msg-assistant", "intent-agent", null, null, null,
                ChatInteractionType.INTENT_CLARIFICATION, ChatInteractionStatus.WAITING,
                Map.of("originalQuery", "原问题", "clarifyQuestion", "请上传文档"), Map.of(),
                now.plus(Duration.ofHours(1)), null, null, now, now);
        interactions.insert(waiting);
        DocumentFacade unavailableDocuments = documentFacade((routeUser, attachments) -> {
            String documentId = attachments.getFirst().documentId();
            if ("doc-transient".equals(documentId)) {
                throw new org.springframework.dao.DataAccessResourceFailureException("document database timeout");
            }
            throw new SecurityException("文档不存在或不属于当前用户: " + documentId);
        });
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, runtimeRouteService(), refusingDomainAgentClient(),
                noopRuntime(), runtimeBindingRepository(),
                new com.huawei.it.ex.one.application.config.DomainAgentProperties(), liveEventBus(),
                interactions, runtimeBindingCache(), null, unavailableDocuments);

        assertThatThrownBy(() -> service.startRun(user, new ChatCommand(
                        null, null, null, session.id(), null, "web", null,
                        List.of(new AttachmentRef("doc-current", "forged.pdf", "application/pdf", 1L)), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, waiting.id(), null, null, Map.of("请上传文档", "当前附件")),
                        RuntimeForwardHeaders.empty()).block())
                .isInstanceOf(SecurityException.class);
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.WAITING);

        ChatInteractionRequest withTransientHistoricalAttachment = new ChatInteractionRequest(
                waiting.id(), waiting.tenantId(), waiting.userId(), waiting.sessionId(), waiting.sourceRunId(),
                null, waiting.userMessageId(), waiting.assistantMessageId(), waiting.runtimeProvider(),
                waiting.runtimeBindingId(), waiting.runtimeSessionId(), waiting.approvalId(),
                waiting.interactionType(), ChatInteractionStatus.WAITING,
                Map.of("originalQuery", "原问题", "clarifyQuestion", "请继续补充",
                        "_intentClarificationDocumentIds", List.of("doc-transient")),
                Map.of(), waiting.expiresAt(), null, null, waiting.createdAt(), Instant.now());
        interactions.insert(withTransientHistoricalAttachment);
        assertThatThrownBy(() -> service.startRun(user, new ChatCommand(
                        null, null, null, session.id(), null, "web", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, waiting.id(), null, null, Map.of("请继续补充", "继续")),
                        RuntimeForwardHeaders.empty()).block())
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.WAITING);

        ChatInteractionRequest withHistoricalAttachment = new ChatInteractionRequest(
                waiting.id(), waiting.tenantId(), waiting.userId(), waiting.sessionId(), waiting.sourceRunId(),
                null, waiting.userMessageId(), waiting.assistantMessageId(), waiting.runtimeProvider(),
                waiting.runtimeBindingId(), waiting.runtimeSessionId(), waiting.approvalId(),
                waiting.interactionType(), ChatInteractionStatus.WAITING,
                Map.of("originalQuery", "原问题", "clarifyQuestion", "请继续补充",
                        "_intentClarificationDocumentIds", List.of("doc-history")),
                Map.of(), waiting.expiresAt(), null, null, waiting.createdAt(), Instant.now());
        interactions.insert(withHistoricalAttachment);

        assertThatThrownBy(() -> service.startRun(user, new ChatCommand(
                        null, null, null, session.id(), null, "web", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, waiting.id(), null, null, Map.of("请继续补充", "继续")),
                        RuntimeForwardHeaders.empty()).block())
                .isInstanceOfSatisfying(ChatInteractionUnavailableException.class,
                        ex -> assertThat(ex.code()).isEqualTo("INTERACTION_ATTACHMENT_UNAVAILABLE"));

        assertThat(interactions.claimCalls).hasValue(0);
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.CANCELLED);
        assertThat(runs.runs).isEmpty();
        assertThat(messages.messages).isEmpty();
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
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public RouteSignalResult routeInitial(UserContext routeUser, ChatSession session, ChatCommand command,
                                                  List<AttachmentRef> attachments,
                                                  com.huawei.it.ex.one.domain.memory.MemoryContext memory) {
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
                new com.huawei.it.ex.one.application.config.ChatWebSocketProperties());
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
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
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
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                new ChatRunStopCoordinator(sessionService, streamService, runService, leaseService,
                        executionRegistry, runtimeExecutor, interactionService, terminalCommitService, ids),
                interactionService, terminalCommitService, ids, eventScheduler,
                new com.huawei.it.ex.one.application.config.DomainAgentProperties(), null, properties);
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
                intentAgent((command, memory, routeUser) -> new com.huawei.it.ex.one.domain.intent.IntentDecision(
                        "domain_agent_finance_knowledge",
                        "财经知识助手",
                        com.huawei.it.ex.one.domain.intent.TaskComplexity.SIMPLE,
                        0.91,
                        true,
                        "domain_agent_finance_knowledge",
                        Map.of(),
                        List.of(),
                        Map.of())),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
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
        assertThat(messages.parts).anySatisfy(part -> {
            assertThat(part.partType()).isEqualTo("METADATA");
            assertThat(part.payload()).containsEntry("metadataType", "selected_domain_agent")
                    .containsEntry("intentId", "domain_agent_finance_knowledge")
                    .containsEntry("intentName", "财经知识助手");
            assertThat(part.payload().get("intentResult")).isInstanceOfSatisfying(Map.class,
                    intentResult -> assertThat(intentResult)
                            .containsEntry("intentId", "domain_agent_finance_knowledge")
                            .containsEntry("intentName", "财经知识助手"));
        });
        ChatEvent completed = events.events.stream()
                .filter(event -> "run.completed".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(sessions.sessions.values()).singleElement()
                .satisfies(session -> {
                    assertThat(session.latestMessageSeq()).isEqualTo(completed.sequence());
                    assertThat(session.lastReadSeq()).isZero();
                    assertThat(session.hasUnread()).isTrue();
                });
    }

    @Test
    void committedBatchPublishesEveryStoredEventBeforePostProcessingFailureClosesRun() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        FailingSecondStreamingObservationRunRepository runs =
                new FailingSecondStreamingObservationRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return Flux.just(
                        RuntimeEvent.progress(request.runId(), request.sessionId(), Map.of(
                                "source", "relay", "sourceType", "progress-1", "message", "one")),
                        RuntimeEvent.progress(request.runId(), request.sessionId(), Map.of(
                                "source", "relay", "sourceType", "progress-2", "message", "two")),
                        RuntimeEvent.progress(request.runId(), request.sessionId(), Map.of(
                                "source", "relay", "sourceType", "progress-3", "message", "three")),
                        com.huawei.it.ex.one.domain.chat.MessageCompletedEvent.of(
                                request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, runtimeRouteService(), runtime);
        com.huawei.it.ex.one.application.config.ChatStreamProperties batchProperties =
                new com.huawei.it.ex.one.application.config.ChatStreamProperties();
        service.setChatEventBatcher(new ChatEventBatcher(batchProperties, new ObjectMapper()));

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())).collectList())
                .assertNext(stream -> {
                    assertThat(stream).extracting(ChatEvent::type)
                            .containsSubsequence("run.started", "runtime.progress", "runtime.progress",
                                    "runtime.progress", "run.failed")
                            .doesNotContain("run.completed");
                    assertThat(stream).filteredOn(event -> "runtime.progress".equals(event.type()))
                            .extracting(event -> event.payload().get("sourceType"))
                            .contains("progress-1", "progress-2", "progress-3");
                })
                .verifyComplete();

        assertThat(events.events).filteredOn(event -> "runtime.progress".equals(event.type()))
                .extracting(event -> event.payload().get("sourceType"))
                .contains("progress-1", "progress-2", "progress-3");
        String failedRunId = events.events.stream()
                .filter(event -> "run.failed".equals(event.type()))
                .map(ChatEvent::runId)
                .findFirst()
                .orElseThrow();
        assertThat(runs.findById(failedRunId)).get()
                .extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.FAILED);
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
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
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

    private static RuntimeEvent domainAgentRefusalEvent(String runId, String sessionId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", "agent.refusal");
        payload.put("metadataType", "domain_agent_control");
        payload.put("supervisorAction", "REROUTE");
        payload.put("type", "agent.refusal");
        payload.put("code", "FN-EX-CAHT-BIZ-DAG-001");
        payload.put("reasonCode", "OUT_OF_DOMAIN");
        payload.put("recoverable", false);
        payload.put("reason", "cannot answer this domain");
        return RuntimeEvent.metadata(runId, sessionId, payload);
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
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
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
        AtomicInteger oldAgentTailSubscriptions = new AtomicInteger();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public RouteSignalResult routeInitial(UserContext routeUser, ChatSession session, ChatCommand command,
                                                  List<AttachmentRef> attachments,
                                                  com.huawei.it.ex.one.domain.memory.MemoryContext memory) {
                blockingRouteInitialCalled.set(true);
                throw new AssertionError("blocking routeInitial must not be used for DomainAgent reroute");
            }

            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                if (routeCalls.incrementAndGet() == 1) {
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                            "agent-a", "intent-agent", 1.0, "initial intent route"))));
                }
                assertThat(events.events).anySatisfy(event -> assertThat(event.payload())
                        .containsEntry("sourceType", "agent.refusal")
                        .containsEntry("code", "FN-EX-CAHT-BIZ-DAG-001"));
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
                    return Flux.concat(
                            Flux.just(
                                    MessageSnapshotEvent.of(request.runId(), request.sessionId(), "obsolete answer"),
                                    domainAgentRefusalEvent(request.runId(), request.sessionId())),
                            Flux.defer(() -> {
                                oldAgentTailSubscriptions.incrementAndGet();
                                return Flux.just(MessageDeltaEvent.of(
                                        request.runId(), request.sessionId(), "late old-agent output"));
                            }));
                }
                return Flux.just(
                        MessageDeltaEvent.of(request.runId(), request.sessionId(), "rerouted answer"),
                        com.huawei.it.ex.one.domain.chat.MessageCompletedEvent.of(
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

        assertThat(oldAgentTailSubscriptions).hasValue(0);
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role()))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.content()).isEqualTo("rerouted answer");
                    assertThat(message.parts()).extracting(ChatMessagePart::partType)
                            .contains("MESSAGE_SNAPSHOT", "DOMAIN_AGENT_REFUSAL", "ANSWER");
                });
    }

    @Test
    void legacyRefusalCodeWithoutControlTypeDoesNotTriggerReroute() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = countingRuntimeRouteService(routeCalls);
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public String provider() {
                return "domain-agent";
            }

            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return Flux.just(
                        RuntimeEvent.progress(request.runId(), request.sessionId(), Map.of(
                                "code", "DOMAIN_REJECT",
                                "reasonCode", "OUT_OF_DOMAIN")),
                        MessageDeltaEvent.of(request.runId(), request.sessionId(), "current agent answer"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = defaultFinanceService(
                sessions, messages, runs, events, routeService, runtime, false);

        StepVerifier.create(service.executeRun(user, new ChatCommand(
                        null, null, null, null, null, "web", "hello", List.of(), Map.of(),
                        "DOMAIN_AGENT", "agent-a", ChatRunMode.NEXT, null, null, null))
                        .collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .contains("runtime.progress", "message.delta", "run.completed")
                        .doesNotContain("run.waiting_user"))
                .verifyComplete();

        assertThat(routeCalls).hasValue(0);
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role()))
                .singleElement()
                .extracting(ChatMessage::content)
                .isEqualTo("current agent answer");
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
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                if (routeCalls.incrementAndGet() == 1) {
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                            "agent-a", "intent-agent", 1.0, "initial intent route"))));
                }
                var noMatch = new com.huawei.it.ex.one.domain.intent.IntentDecision(
                        "finance.runtime.no_intent", "未识别到可用意图",
                        com.huawei.it.ex.one.domain.intent.TaskComplexity.COMPLEX,
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
                        domainAgentRefusalEvent(request.runId(), request.sessionId()),
                        com.huawei.it.ex.one.domain.chat.MessageCompletedEvent.of(
                                request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        AtomicInteger relayCalls = new AtomicInteger();
        AtomicReference<RuntimeSessionMode> relaySessionMode = new AtomicReference<>();
        AtomicReference<TraceContext> relayTraceContext = new AtomicReference<>();
        AtomicReference<Map<String, Object>> relayMetadata = new AtomicReference<>();
        AgentRuntime relay = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                relayCalls.incrementAndGet();
                relaySessionMode.set(request.runtimeSessionMode());
                relayTraceContext.set(request.traceContext());
                relayMetadata.set(request.metadata());
                return Flux.just(
                        MessageDeltaEvent.of(request.runId(), request.sessionId(), "relay answer"),
                        com.huawei.it.ex.one.domain.chat.MessageCompletedEvent.of(
                                request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClient(
                sessions, messages, runs, events, routeService, domainClient, relay);

        StepVerifier.create(service.executeRun(user, new TraceContext("refusal-reroute-trace"),
                        new ChatCommand("cmd1", null, null, null, null, "web", "hello",
                                List.of(new AttachmentRef("doc1", "forged-name.txt", "text/plain", 1L)),
                                Map.of("sceneParam", Map.of(
                                        "region", "CN",
                                        "docList", List.of(Map.of("docId", "forged"))))),
                        RuntimeForwardHeaders.empty()).collectList())
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
        assertThat(relaySessionMode).hasValue(RuntimeSessionMode.NEW);
        assertThat(relayTraceContext).hasValue(new TraceContext("refusal-reroute-trace"));
        assertThat(relayMetadata.get().get("sceneParam")).isInstanceOfSatisfying(Map.class,
                sceneParam -> {
                    assertThat(sceneParam).containsEntry("region", "CN");
                    assertThat(sceneParam.get("docList")).isEqualTo(List.of(Map.of(
                            "providerLocatorType", "DOC_ID",
                            "docId", "provider-doc1",
                            "docName", "invoice.pdf",
                            "docSize", 128L)));
                });
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role()))
                .singleElement()
                .extracting(ChatMessage::content)
                .isEqualTo("relay answer");
        assertThat(runs.runs.values()).singleElement()
                .extracting(ChatRun::runtimeProvider)
                .isEqualTo("relay");
    }

    @Test
    void domainAgentRefusalNoMatchResumesHistoricalRelaySession() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        MultiBindingRuntimeBindingRepository bindings = new MultiBindingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        Instant now = Instant.now();
        sessions.save(new ChatSession("session1", user.tenantId(), user.ownerUserId(),
                "test", "ACTIVE", "web", now, now));
        bindings.save(new RuntimeBinding("relay-binding", user.tenantId(), user.ownerUserId(), "session1",
                "relay", "relay-leaf", "relay-session-1", RuntimeBindingStatus.RESUMABLE, "old-run",
                null, now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(30)),
                Map.of("runtimeSessionEstablished", true)));
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                if (routeCalls.incrementAndGet() == 1) {
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                            "agent-a", "intent-agent", 1.0, "initial intent route"))));
                }
                var noMatch = new com.huawei.it.ex.one.domain.intent.IntentDecision(
                        "finance.runtime.no_intent", "未识别到可用意图",
                        com.huawei.it.ex.one.domain.intent.TaskComplexity.COMPLEX,
                        0.0, false, null, Map.of("routeAction", "NO_MATCH"), List.of(), Map.of());
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.ofIntent(
                        RouteTarget.agentRuntime("intent-agent", 0.0, "no match routes to relay"),
                        noMatch, 1L, 0.85)));
            }
        };
        DomainAgentClient domainClient = refusingDomainAgentClient();
        AtomicReference<RuntimeSessionMode> relaySessionMode = new AtomicReference<>();
        AtomicReference<String> relayRuntimeSessionId = new AtomicReference<>();
        AgentRuntime relay = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                relaySessionMode.set(request.runtimeSessionMode());
                relayRuntimeSessionId.set(request.runtimeSessionId());
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "relay answer"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, relay, bindings,
                new com.huawei.it.ex.one.application.config.DomainAgentProperties());

        StepVerifier.create(service.startRun(user, new ChatCommand("cmd1", null, null,
                        "session1", null, "web", "hello", List.of(), Map.of()),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitEvent(events, "run.completed");
        assertThat(relaySessionMode).hasValue(RuntimeSessionMode.RESUME);
        assertThat(relayRuntimeSessionId).hasValue("relay-session-1");
        assertThat(bindings.bindingsForProvider("relay"))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.id()).isEqualTo("relay-binding");
                    assertThat(binding.runtimeSessionId()).isEqualTo("relay-session-1");
                    assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.RESUMABLE);
                });
    }

    @Test
    void automaticDomainAgentRefusalRepeatedCandidateCancelsBinding() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        MultiBindingRuntimeBindingRepository bindings = new MultiBindingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = repeatedDomainAgentRouteService(routeCalls, "agent-a");
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
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, refusingDomainAgentClient(), relay, bindings,
                new com.huawei.it.ex.one.application.config.DomainAgentProperties());

        StepVerifier.create(service.startRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of()), RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitEvent(events, "run.completed");
        assertThat(routeCalls).hasValue(2);
        assertThat(relayCalls).hasValue(0);
        assertThat(bindings.bindingsForProvider("domain-agent"))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
                    assertThat(binding.metadata())
                            .containsEntry("lastRejectCode", "FN-EX-CAHT-BIZ-DAG-001");
                });
    }

    @Test
    void automaticDomainAgentRefusalAtRerouteLimitCancelsBindingButManualSelectionKeepsIt() {
        com.huawei.it.ex.one.application.config.DomainAgentProperties properties =
                new com.huawei.it.ex.one.application.config.DomainAgentProperties();
        properties.setMaxReroutes(0);

        MultiBindingRuntimeBindingRepository automaticBindings = new MultiBindingRuntimeBindingRepository();
        InMemoryEventStore automaticEvents = new InMemoryEventStore();
        FinanceEXChatService automaticService = financeServiceWithDomainClientAndBindings(
                new InMemorySessionRepository(), new InMemoryMessageRepository(), new InMemoryRunRepository(),
                automaticEvents, repeatedDomainAgentRouteService(new AtomicInteger(), "agent-a"),
                refusingDomainAgentClient(), noopRuntime(), automaticBindings, properties);
        UserContext user = new UserContext("tenant1", "user1", "User One");

        StepVerifier.create(automaticService.startRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of()), RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();
        awaitEvent(automaticEvents, "run.completed");

        assertThat(automaticBindings.bindingsForProvider("domain-agent"))
                .singleElement()
                .extracting(RuntimeBinding::status)
                .isEqualTo(RuntimeBindingStatus.CANCELLED);

        MultiBindingRuntimeBindingRepository manualBindings = new MultiBindingRuntimeBindingRepository();
        InMemoryEventStore manualEvents = new InMemoryEventStore();
        FinanceEXChatService manualService = financeServiceWithDomainClientAndBindings(
                new InMemorySessionRepository(), new InMemoryMessageRepository(), new InMemoryRunRepository(),
                manualEvents, repeatedDomainAgentRouteService(new AtomicInteger(), "unused"),
                refusingDomainAgentClient(), noopRuntime(), manualBindings, properties);

        StepVerifier.create(manualService.startRun(user, new ChatCommand(
                        "cmd2", null, null, null, null, "web", "hello", List.of(), Map.of(),
                        "DOMAIN_AGENT", "agent-a", ChatRunMode.NEXT, null, null, null),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();
        awaitEvent(manualEvents, "run.completed");

        assertThat(manualBindings.bindingsForProvider("domain-agent"))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
                    assertThat(binding.metadata()).containsEntry("routeSource", "front-selected");
                });
    }

    @Test
    void automaticRefusalCancelsBindingBeforePublishAndStopDoesNotReuseIt() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        MultiBindingRuntimeBindingRepository bindings = new MultiBindingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                int call = routeCalls.incrementAndGet();
                if (call == 1) {
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                            "agent-a", "intent-agent", 1.0, "initial intent route"))));
                }
                if (call == 2) {
                    return Flux.never();
                }
                return Flux.just(RouteSignalFrame.result(
                        RouteSignalResult.of(RouteTarget.agentRuntime("intent-agent", 1.0, "rerouted"))));
            }
        };
        AtomicInteger domainCalls = new AtomicInteger();
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                domainCalls.incrementAndGet();
                return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
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
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "relay answer"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        AtomicReference<RuntimeBindingStatus> bindingStatusAtRefusalPublish = new AtomicReference<>();
        ChatLiveEventBus eventBus = new ChatLiveEventBus() {
            @Override
            public void publish(String topicId, ChatEvent event) {
                if (event != null && event.payload() != null
                        && "agent.refusal".equals(event.payload().get("sourceType"))
                        && "FN-EX-CAHT-BIZ-DAG-001".equals(event.payload().get("code"))) {
                    bindings.bindingsForProvider("domain-agent").stream()
                            .findFirst()
                            .map(RuntimeBinding::status)
                            .ifPresent(bindingStatusAtRefusalPublish::set);
                }
            }

            @Override
            public Flux<ChatEvent> subscribe(String topicId) {
                return Flux.never();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, relay, bindings,
                new com.huawei.it.ex.one.application.config.DomainAgentProperties(), eventBus);
        AtomicReference<ChatRunStartResult> firstRun = new AtomicReference<>();

        StepVerifier.create(service.startRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of()), RuntimeForwardHeaders.empty()))
                .assertNext(firstRun::set)
                .verifyComplete();

        awaitValue(bindingStatusAtRefusalPublish, RuntimeBindingStatus.CANCELLED,
                "automatic refusal binding status at publish");
        StepVerifier.create(service.stopRun(user, firstRun.get().runId(), RuntimeForwardHeaders.empty()))
                .expectNextCount(1)
                .verifyComplete();
        awaitEvent(events, "run.cancelled");

        StepVerifier.create(service.startRun(user, new ChatCommand("cmd2", null, null,
                        firstRun.get().sessionId(), null, "web", "next question", List.of(), Map.of()),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();
        awaitEvent(events, "run.completed");

        assertThat(routeCalls).hasValue(3);
        assertThat(domainCalls).hasValue(1);
        assertThat(relayCalls).hasValue(1);
    }

    @Test
    void automaticRefusalCommitAndCacheSyncDoNotBlockMainEventIo() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        ThreadCapturingRuntimeBindingRepository bindings = new ThreadCapturingRuntimeBindingRepository();
        BlockingRuntimeBindingCache bindingCache = new BlockingRuntimeBindingCache();
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicInteger relayCalls = new AtomicInteger();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                if (routeCalls.incrementAndGet() == 1) {
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                            "agent-a", "intent-agent", 1.0, "initial intent route"))));
                }
                return Flux.just(RouteSignalFrame.result(
                        RouteSignalResult.of(RouteTarget.agentRuntime("intent-agent", 1.0, "relay fallback"))));
            }
        };
        AgentRuntime relay = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                relayCalls.incrementAndGet();
                return Flux.just(
                        MessageSnapshotEvent.of(request.runId(), request.sessionId(), "relay answer"),
                        com.huawei.it.ex.one.domain.chat.MessageCompletedEvent.of(
                                request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        reactor.core.scheduler.Scheduler controlScheduler = reactor.core.scheduler.Schedulers.newBoundedElastic(
                1, 16, "test-domain-agent-control-io");
        try {
            FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                    sessions, messages, runs, events, routeService, refusingDomainAgentClient(), relay, bindings,
                    new com.huawei.it.ex.one.application.config.DomainAgentProperties(), liveEventBus(),
                    new InMemoryInteractionRequestRepository(), bindingCache, controlScheduler);

            StepVerifier.create(service.startRun(user, new ChatCommand("cmd1", null, null,
                            null, null, "web", "hello", List.of(), Map.of()), RuntimeForwardHeaders.empty()))
                    .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                    .verifyComplete();

            assertThat(bindingCache.awaitEvictionStarted()).isTrue();
            awaitAtomicValue(routeCalls, 2, "refusal reroute decision");
            awaitAtomicValue(relayCalls, 1, "relay invocation");
            awaitEvent(events, "run.completed");
            assertThat(bindings.cancellationThread.get()).startsWith("test-domain-agent-control-io");
            assertThat(routeCalls).hasValue(2);
            assertThat(relayCalls).hasValue(1);
        } finally {
            bindingCache.releaseEviction();
            controlScheduler.dispose();
        }
    }

    @Test
    void refusalIntentClarificationLoadsCancelledExpiredBindingById() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
        MultiBindingRuntimeBindingRepository bindings = new MultiBindingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        Instant now = Instant.now();
        sessions.save(new ChatSession("session1", user.tenantId(), user.ownerUserId(), "测试会话", "ACTIVE", "web",
                "msg-user", "msg-assistant", null, null, 1L, null, now, now));
        messages.save(new ChatMessage("msg-user", user.tenantId(), user.ownerUserId(), "session1",
                null, 1L, 0, 1, "user", "原始问题", null, "run-source",
                "NORMAL", false, null, null, null, null, null, now));
        messages.save(new ChatMessage("msg-assistant", user.tenantId(), user.ownerUserId(), "session1",
                "msg-user", 2L, 1, 1, "assistant", "请补充具体场景", null, "run-source",
                "NORMAL", false, null, null, null, null, null, now));
        AgentModeProfile rejectedMode = new AgentModeProfile(List.of(
                new AgentModeSelection("thinking", "deep", "深度思考")));
        bindings.save(new RuntimeBinding("binding-domain-a", user.tenantId(), user.ownerUserId(), "session1",
                "domain-agent", "msg-assistant", "domain-session-a", RuntimeBindingStatus.CANCELLED, "run-source",
                now.minus(Duration.ofMinutes(1)), now.minus(Duration.ofDays(1)), now,
                AgentModeBindingContext.apply(Map.of(
                        "domainAgentId", "agent-a",
                        "routeSource", "intent-agent",
                        "lastRejectCode", "FN-EX-CAHT-BIZ-DAG-001"), rejectedMode)));
        Map<String, Object> rerouteContext = Map.ofEntries(
                Map.entry("currentProvider", "domain-agent"),
                Map.entry("currentTargetId", "agent-a"),
                Map.entry("currentBindingId", "binding-domain-a"),
                Map.entry("currentRouteSource", "intent-agent"),
                Map.entry("refusalCode", "FN-EX-CAHT-BIZ-DAG-001"),
                Map.entry("refusalReasonCode", "OUT_OF_DOMAIN"),
                Map.entry("refusalRecoverable", false),
                Map.entry("refusalReason", "当前请求不在该领域 Agent 处理范围内"),
                Map.entry("rerouteCount", 0),
                Map.entry("rejectedDomainAgentIds", List.of("agent-a")),
                Map.entry("originalQuery", "原始问题"));
        ChatInteractionRequest waiting = new ChatInteractionRequest(
                "interaction1", user.tenantId(), user.ownerUserId(), "session1", "run-source", null,
                "msg-user", "msg-assistant", "intent-agent", null, null, null,
                ChatInteractionType.INTENT_CLARIFICATION, ChatInteractionStatus.WAITING,
                Map.of("source", "intent-agent",
                        "sourceType", "intent-clarification-request",
                        "interactionType", "INTENT_CLARIFICATION",
                        "originalQuery", "原始问题",
                        "clarifyQuestion", "请补充具体场景",
                        "domainAgentRerouteContext", rerouteContext),
                Map.of(), now.plus(Duration.ofHours(1)), null, null, now, now);
        interactions.insert(waiting);
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                        "agent-b", "intent-agent", 1.0, "clarification resolved"))));
            }
        };
        AtomicInteger agentBCalls = new AtomicInteger();
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                if ("agent-b".equals(request.domainAgentId())) {
                    agentBCalls.incrementAndGet();
                    return Flux.just(MessageSnapshotEvent.of(
                            request.runId(), request.sessionId(), "agent-b answer"));
                }
                return Flux.error(new AssertionError("refused agent must not be called again"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, noopRuntime(), bindings,
                new com.huawei.it.ex.one.application.config.DomainAgentProperties(), liveEventBus(),
                interactions);

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, "session1", null, "web", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, waiting.id(), null, null, Map.of("请补充具体场景", "账务审批")),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();
        awaitEvent(events, "run.completed");

        assertThat(events.events).extracting(ChatEvent::type).doesNotContain("run.failed");
        assertThat(agentBCalls).hasValue(1);
        assertThat(bindings.bindingsForProvider("domain-agent"))
                .filteredOn(binding -> "agent-a".equals(binding.metadata().get("domainAgentId")))
                .singleElement()
                .extracting(RuntimeBinding::status)
                .isEqualTo(RuntimeBindingStatus.CANCELLED);
        assertThat(bindings.bindingsForProvider("domain-agent"))
                .filteredOn(binding -> "agent-b".equals(binding.metadata().get("domainAgentId")))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
                    assertThat(AgentModeBindingContext.fromBinding(binding)).isNull();
                });
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
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                if (routeCalls.incrementAndGet() == 1) {
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                            "agent-a", "intent-agent", 1.0, "initial intent route"))));
                }
                var failure = new com.huawei.it.ex.one.domain.intent.IntentDecision(
                        "finance.runtime.degraded", "意图服务不可用",
                        com.huawei.it.ex.one.domain.intent.TaskComplexity.COMPLEX,
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
                return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
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
    void frontSelectedRefusalRequiresConfirmationAndReusesAssistantForNewAgentAnswer() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
        CapturingRuntimeBindingRepository bindings = new CapturingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        IdGenerator ids = new SequentialIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        SessionApplicationService sessionService = new SessionApplicationService(sessions, messages, ids,
                permissionChecker);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.it.ex.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(), events,
                permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions, (ApplicationInstanceIdProvider) () -> "instance-test",
                new ChatRunOperationalProperties(), ids, executionRegistry);
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(
                interactions, ids, permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService terminalCommitService = new ChatRunTerminalCommitService(
                streamService, sessionService, runs, leaseService, bindings, interactionService, Duration.ZERO);
        RuntimeBindingApplicationService bindingService = new RuntimeBindingApplicationService(
                bindings, runtimeBindingCache(), ids, Duration.ZERO, "relay");
        AtomicInteger rerouteDecisions = new AtomicInteger();
        AtomicReference<Map<String, Object>> rerouteMetadata = new AtomicReference<>();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                rerouteMetadata.set(request.command().metadata());
                if (rerouteDecisions.incrementAndGet() == 1) {
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                            "agent-b", "intent-agent", 1.0, "rerouted after refusal"))));
                }
                var noMatch = new com.huawei.it.ex.one.domain.intent.IntentDecision(
                        "finance.runtime.no_intent", "未识别到可用意图",
                        com.huawei.it.ex.one.domain.intent.TaskComplexity.COMPLEX,
                        0.0, false, null, Map.of("routeAction", "NO_MATCH"), List.of(), Map.of());
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.ofIntent(
                        RouteTarget.agentRuntime("intent-agent", 0.0, "no match routes to relay"),
                        noMatch, 1L, 0.85)));
            }
        };
        AtomicInteger agentACalls = new AtomicInteger();
        AtomicInteger agentBCalls = new AtomicInteger();
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                if ("agent-a".equals(request.domainAgentId())) {
                    agentACalls.incrementAndGet();
                    return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
                }
                if (agentBCalls.incrementAndGet() == 1) {
                    return Flux.just(MessageSnapshotEvent.of(
                            request.runId(), request.sessionId(), "agent-b final answer"));
                }
                return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        AtomicInteger relayCalls = new AtomicInteger();
        AgentRuntime relayRuntime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                relayCalls.incrementAndGet();
                return Flux.just(MessageSnapshotEvent.of(
                        request.runId(), request.sessionId(), "relay final answer"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        DocumentFacade documents = documentFacade();
        DomainAgentExecutor domainExecutor = new DomainAgentExecutor(domainClient, documents, limiter);
        AgentRuntimeExecutor relayExecutor = new AgentRuntimeExecutor(relayRuntime, limiter);
        ChatRunStopCoordinator stopCoordinator = new ChatRunStopCoordinator(
                sessionService, streamService, runService, leaseService, executionRegistry,
                relayExecutor, domainExecutor, ids);
        FinanceEXChatService service = new FinanceEXChatService(
                sessionService,
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                bindingService,
                routeService,
                intentRecordService(),
                domainExecutor,
                new SystemResponseExecutor(),
                relayExecutor,
                documents,
                streamService,
                runService,
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                stopCoordinator,
                interactionService,
                terminalCommitService,
                ids,
                reactor.core.scheduler.Schedulers.boundedElastic(),
                new com.huawei.it.ex.one.application.config.DomainAgentProperties());

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, null, null, "web", "原问题", List.of(),
                        SelectedIntentContext.attach(Map.of("scene", "manual"), "intent-a", "领域 A"),
                        "DOMAIN_AGENT", "agent-a", ChatRunMode.NEXT, null, null, null),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitEvent(events, "run.waiting_user");
        ChatInteractionRequest waiting = interactions.requests.values().stream()
                .filter(request -> request.status() == ChatInteractionStatus.WAITING)
                .findFirst()
                .orElseThrow();
        assertThat(waiting.interactionType()).isEqualTo(ChatInteractionType.ROUTE_SWITCH_CONFIRMATION);
        assertThat(waiting.requestPayload())
                .containsEntry("currentTargetId", "agent-a")
                .containsEntry("candidateProvider", "domain-agent")
                .containsEntry("candidateTargetId", "agent-b")
                .containsEntry("refusalCode", "FN-EX-CAHT-BIZ-DAG-001");
        ChatMessage waitingAssistant = messages.messages.stream()
                .filter(message -> "assistant".equals(message.role()))
                .findFirst()
                .orElseThrow();
        assertThat(waitingAssistant.parts()).extracting(ChatMessagePart::partType)
                .contains("DOMAIN_AGENT_REFUSAL", "ROUTE_SWITCH_CONFIRMATION_REQUEST")
                .doesNotContain("ANSWER");
        assertThat(waitingAssistant.parts()).filteredOn(part -> "DOMAIN_AGENT_REFUSAL".equals(part.partType()))
                .singleElement()
                .satisfies(part -> assertThat(part.payload())
                        .containsEntry("domainAgentId", "agent-a")
                        .containsEntry("code", "FN-EX-CAHT-BIZ-DAG-001"));
        assertThat(agentACalls).hasValue(1);
        assertThat(agentBCalls).hasValue(0);
        assertThat(rerouteMetadata.get())
                .containsEntry("scene", "manual")
                .containsEntry("routeTrigger", "domain_reject")
                .containsKey("lastIntentRejectReason");
        assertThat(SelectedIntentContext.intentId(rerouteMetadata.get())).isNull();
        assertThat(SelectedIntentContext.intentName(rerouteMetadata.get())).isNull();

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, waiting.sessionId(), null, "web", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, waiting.id(), true, null, Map.of()), RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitEvent(events, "run.completed");
        assertThat(agentBCalls).hasValue(1);
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role()))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.id()).isEqualTo(waitingAssistant.id());
                    assertThat(message.content()).isEqualTo("agent-b final answer");
                    assertThat(message.parts()).extracting(ChatMessagePart::partType)
                            .contains("DOMAIN_AGENT_REFUSAL", "ROUTE_SWITCH_CONFIRMATION_REQUEST",
                                    "ROUTE_SWITCH_CONFIRMATION_RESPONSE", "MESSAGE_SNAPSHOT", "ANSWER");
                    assertThat(message.parts()).filteredOn(part -> "ANSWER".equals(part.partType())).hasSize(1);
                });
        assertThat(bindings.saved.metadata())
                .containsEntry("domainAgentId", "agent-b")
                .containsEntry("routeSource", "user-confirmed");

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, waiting.sessionId(), null, "web", "第二个问题", List.of(), Map.of(),
                        null, null, ChatRunMode.NEXT, null, null, null), RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        ChatInteractionRequest relayWaiting = awaitWaitingInteraction(interactions, waiting.id());
        assertThat(relayWaiting.interactionType()).isEqualTo(ChatInteractionType.ROUTE_SWITCH_CONFIRMATION);
        assertThat(relayWaiting.requestPayload())
                .containsEntry("currentRouteSource", "user-confirmed")
                .containsEntry("candidateProvider", "relay")
                .containsEntry("candidateTargetId", "relay");
        assertThat(relayCalls).hasValue(0);

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, relayWaiting.sessionId(), null, "web", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, relayWaiting.id(), false, null, Map.of()), RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitEventCount(events, "run.completed", 2);
        assertThat(relayCalls).hasValue(0);
        assertThat(bindings.saved.metadata())
                .containsEntry("domainAgentId", "agent-b")
                .containsEntry("routeSource", "user-confirmed");
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role()))
                .filteredOn(message -> "已保留当前领域 Agent，本轮不切换处理能力。".equals(message.content()))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.parts()).extracting(ChatMessagePart::partType)
                            .contains("ROUTE_SWITCH_CONFIRMATION_RESPONSE", "ROUTE_SWITCH_DECLINED", "ANSWER");
                    assertThat(message.parts()).filteredOn(part -> "ANSWER".equals(part.partType())).hasSize(1);
                });

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, waiting.sessionId(), null, "web", "第三个问题", List.of(), Map.of(),
                        null, null, ChatRunMode.NEXT, null, null, null), RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        ChatInteractionRequest nextRelayWaiting = awaitWaitingInteraction(
                interactions, waiting.id(), relayWaiting.id());
        assertThat(nextRelayWaiting.requestPayload())
                .containsEntry("currentRouteSource", "user-confirmed")
                .containsEntry("candidateProvider", "relay");

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, nextRelayWaiting.sessionId(), null, "web", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, nextRelayWaiting.id(), true, null, Map.of()), RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitEventCount(events, "run.completed", 3);
        assertThat(relayCalls).hasValue(1);
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role()))
                .filteredOn(message -> "relay final answer".equals(message.content()))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.parts()).extracting(ChatMessagePart::partType)
                            .contains("DOMAIN_AGENT_REFUSAL", "ROUTE_SWITCH_CONFIRMATION_REQUEST",
                                    "ROUTE_SWITCH_CONFIRMATION_RESPONSE", "MESSAGE_SNAPSHOT", "ANSWER");
                    assertThat(message.parts()).filteredOn(part -> "ANSWER".equals(part.partType())).hasSize(1);
                });
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
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                new InMemoryExecutionRepository(),
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry
        );
        AgentRuntime runtime = new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                Map<String, Object> toolPayload = new HashMap<>();
                toolPayload.put("sourceType", "tool_call_streaming");
                toolPayload.put("toolName", "search");
                toolPayload.put("inputPreview", "查询报销流程");
                toolPayload.put("optionalDetail", null);
                return Flux.just(
                        MessageDeltaEvent.of(request.runId(), request.sessionId(), "草稿"),
                        RuntimeEvent.tool(request.runId(), request.sessionId(), toolPayload),
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
                        new com.huawei.it.ex.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
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
        assertThat(assistant.parts().getFirst().payload()).containsKey("optionalDetail");
        assertThat(assistant.parts().getFirst().payload().get("optionalDetail")).isNull();
        ChatMessagePart answerPart = assistant.parts().getLast();
        assertThat(answerPart.payload()).containsEntry(
                "serverTimestampMs", answerPart.createdAt().toEpochMilli());
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
    void assistantAssemblyAddsServerTimestampToEventPartPayloads() {
        Instant createdAt = Instant.parse("2026-07-12T08:00:00Z");
        Map<String, Object> thinkingPayload = new HashMap<>();
        thinkingPayload.put("sourceType", "thinking-operation-start");
        thinkingPayload.put("status", "STARTED");
        thinkingPayload.put("optionalDetail", null);
        thinkingPayload.put("serverTimestampMs", -1L);
        List<ChatEvent> events = List.of(
                new RuntimeEvent("run1", "session1", 0, createdAt,
                        "runtime.thinking", thinkingPayload),
                new RuntimeEvent("run1", "session1", 0, createdAt.plusMillis(10),
                        "runtime.progress", Map.of("sourceType", "relay-progress", "message", "处理中")),
                new RuntimeEvent("run1", "session1", 0, createdAt.plusMillis(20),
                        "runtime.tool", Map.of("sourceType", "tool-execution", "toolName", "search")),
                new RuntimeEvent("run1", "session1", 0, createdAt.plusMillis(30),
                        "runtime.card", Map.of("sourceType", "cardList")),
                new MessageSnapshotEvent("run1", "session1", 0, createdAt.plusMillis(40), "answer",
                        Map.of("content", "answer", "sourceType", "generate-response"))
        );
        AssistantAssembly assistant = new AssistantAssembly();

        events.forEach(assistant::observe);

        assertThat(assistant.parts()).extracting(ChatMessagePartDraft::partType)
                .containsExactly("THINKING", "PROGRESS", "TOOL", "CARD", "MESSAGE_SNAPSHOT");
        assertThat(assistant.parts()).extracting(part -> part.payload().get("serverTimestampMs"))
                .containsExactly(createdAt.toEpochMilli(), createdAt.plusMillis(10).toEpochMilli(),
                        createdAt.plusMillis(20).toEpochMilli(), createdAt.plusMillis(30).toEpochMilli(),
                        createdAt.plusMillis(40).toEpochMilli());
        assertThat(assistant.parts().getFirst().payload()).containsEntry("optionalDetail", null);
        assertThat(thinkingPayload).containsEntry("serverTimestampMs", -1L);
    }

    @Test
    void assistantAssemblyUsesIntentClarificationQuestionAsContentAndSkipsResponsePart() {
        AssistantAssembly assistant = new AssistantAssembly();
        assistant.observe(RuntimeEvent.card("run1", "session1", Map.of(
                "source", "intent-agent",
                "sourceType", "intent-clarification-request",
                "interactionType", "INTENT_CLARIFICATION",
                "clarifyQuestion", "您具体指哪个方案？")));
        assistant.observe(RuntimeEvent.card("run1", "session1", Map.of(
                "source", "chatservice",
                "sourceType", "intent-clarification-response",
                "interactionType", "INTENT_CLARIFICATION",
                "answerText", "账务审批方案")));

        assertThat(assistant.finalContent()).isEqualTo("您具体指哪个方案？");
        assertThat(assistant.parts()).extracting(ChatMessagePartDraft::partType)
                .containsExactly("INTENT_CLARIFICATION_REQUEST");
        assertThat(assistant.parts()).extracting(ChatMessagePartDraft::contentText)
                .containsExactly("您具体指哪个方案？");
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
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        SessionApplicationService sessionService = new SessionApplicationService(sessions, messages, ids, permissionChecker);
        ChatStreamApplicationService chatStreamService = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.it.ex.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(), events,
                permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry
        );
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(interactionRequests, ids,
                permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService terminalCommitService = new ChatRunTerminalCommitService(
                chatStreamService, sessionService, runs, leaseService, bindings, interactionService, Duration.ofDays(3));
        AgentRuntime runtime = new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("source", "relay");
                payload.put("sourceType", "approval-request");
                payload.put("operation_type", "questionnaire");
                payload.put("approval_id", "approval-1");
                payload.put("message", "Please answer the following questions");
                payload.put("runtimeSessionId", request.sessionId());
                payload.put("optionalDetail", null);
                return Flux.just(RuntimeEvent.card(request.runId(), request.sessionId(), payload));
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
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                new ChatRunStopCoordinator(sessionService, chatStreamService, runService, leaseService,
                        executionRegistry, new AgentRuntimeExecutor(runtime, interaction, limiter),
                        domainAgentExecutor(documentFacade(), limiter), ids),
                interactionService,
                terminalCommitService,
                ids,
                reactor.core.scheduler.Schedulers.boundedElastic(),
                new com.huawei.it.ex.one.application.config.DomainAgentProperties()
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
                    assertThat(request.requestPayload()).containsKey("optionalDetail");
                    assertThat(request.requestPayload().get("optionalDetail")).isNull();
                });
        assertThat(events.events).extracting(ChatEvent::type)
                .containsExactly("run.started", "runtime.card", "run.waiting_user");
        ChatEvent waitingUser = events.events.stream()
                .filter(event -> "run.waiting_user".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(sessions.sessions.get(run.sessionId()))
                .satisfies(session -> {
                    assertThat(session.latestMessageSeq()).isEqualTo(waitingUser.sequence());
                    assertThat(session.hasUnread()).isTrue();
                });
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
        IdGenerator ids = new SequentialIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        SessionApplicationService sessionService = new SessionApplicationService(sessions, messages, ids, permissionChecker);
        ChatStreamApplicationService chatStreamService = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.it.ex.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(), events,
                permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry
        );
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(interactionRequests, ids,
                permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService terminalCommitService = new ChatRunTerminalCommitService(
                chatStreamService, sessionService, runs, leaseService, runtimeBindingRepository(), interactionService,
                Duration.ofDays(3));
        AtomicReference<String> runtimeQuery = new AtomicReference<>();
        AtomicReference<Map<String, Object>> runtimeMetadata = new AtomicReference<>();
        AtomicReference<List<AttachmentRef>> runtimeAttachments = new AtomicReference<>();
        AtomicReference<List<UploadedDocument>> runtimeDocuments = new AtomicReference<>();
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                runtimeQuery.set(request.message());
                runtimeMetadata.set(request.metadata());
                runtimeAttachments.set(request.attachments());
                runtimeDocuments.set(request.documents());
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "最终回答"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        AgentRuntimeExecutor runtimeExecutor = new AgentRuntimeExecutor(runtime, limiter);
        RouteSignalApplicationService routeService = runtimeRouteService();
        DocumentFacade documentsFacade = documentFacade();
        FinanceEXChatService service = new FinanceEXChatService(
                sessionService,
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids,
                        Duration.ofDays(3), "relay"),
                routeService,
                intentRecordService(),
                domainAgentExecutor(documentsFacade, limiter),
                new SystemResponseExecutor(),
                runtimeExecutor,
                documentsFacade,
                chatStreamService,
                runService,
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                new ChatRunStopCoordinator(sessionService, chatStreamService, runService, leaseService,
                        executionRegistry, runtimeExecutor,
                        domainAgentExecutor(documentsFacade, limiter), ids),
                interactionService,
                terminalCommitService,
                ids,
                reactor.core.scheduler.Schedulers.boundedElastic(),
                new com.huawei.it.ex.one.application.config.DomainAgentProperties()
        );
        Instant now = Instant.now();
        sessions.save(new ChatSession("session1", user.tenantId(), user.ownerUserId(), "测试会话", "ACTIVE", "web",
                "msg-user", "msg-assistant", null, null, 1L, null, now, now));
        messages.save(new ChatMessage("msg-user", user.tenantId(), user.ownerUserId(), "session1",
                null, 1L, 0, 1, "user", "再帮我看下方案", null, "run-source",
                "NORMAL", false, null, null, null, null, null, now));
        messages.save(new ChatMessage("msg-assistant", user.tenantId(), user.ownerUserId(), "session1",
                "msg-user", 2L, 1, 1, "assistant", "您提到的方案具体是指哪个方案？", null, "run-source",
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
                        null, null, null, "session1", null, "web", null,
                        List.of(new AttachmentRef("doc1", "forged-name.txt", "text/plain", 1L)),
                        Map.of(
                                "language", "zh_CN",
                                "sceneParam", Map.of(
                                        "region", "CN",
                                        "docList", List.of(Map.of("docId", "forged")))),
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
                        .containsEntry("answerText", "我是说账务审批的方案")
                        .doesNotContainKey("approval_id"));
        assertThat(runs.runs.values()).allSatisfy(run -> assertThat(run.status()).isNotEqualTo(ChatRunStatus.RUNNING));
        assertThat(interactionRequests.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.ANSWERED);
        ChatMessage answer = messages.messages.stream()
                .filter(message -> "user".equals(message.role()))
                .filter(message -> "我是说账务审批的方案".equals(message.content()))
                .findFirst()
                .orElseThrow();
        assertThat(answer.parentMessageId()).isEqualTo(waiting.assistantMessageId());
        assertThat(messages.attachments).singleElement().satisfies(attachment -> {
            assertThat(attachment.messageId()).isEqualTo(answer.id());
            assertThat(attachment.documentId()).isEqualTo("doc1");
            assertThat(attachment.name()).isEqualTo("invoice.pdf");
        });
        ChatMessage finalAssistant = messages.messages.stream()
                .filter(message -> "assistant".equals(message.role()))
                .filter(message -> answer.id().equals(message.parentMessageId()))
                .findFirst()
                .orElseThrow();
        assertThat(finalAssistant.id()).isNotEqualTo(waiting.assistantMessageId());
        assertThat(finalAssistant.parts()).extracting(ChatMessagePart::partType)
                .doesNotContain("INTENT_CLARIFICATION_RESPONSE");
        assertThat(runtimeQuery).hasValue(
                "user:再帮我看下方案；澄清问:您提到的方案具体是指哪个方案？；用户:我是说账务审批的方案 [用户上传文档] invoice.pdf");
        assertThat(runtimeAttachments.get()).extracting(AttachmentRef::name).containsExactly("invoice.pdf");
        assertThat(runtimeDocuments.get()).extracting(UploadedDocument::id).containsExactly("doc1");
        assertThat(runtimeMetadata.get()).containsEntry("language", "zh_CN");
        Map<?, ?> runtimeSceneParam = (Map<?, ?>) runtimeMetadata.get().get("sceneParam");
        assertThat(runtimeSceneParam.get("region")).isEqualTo("CN");
        assertThat(runtimeSceneParam.get("docList"))
                .isEqualTo(List.of(Map.of(
                        "providerLocatorType", "DOC_ID",
                        "docId", "provider-doc1",
                        "docName", "invoice.pdf",
                        "docSize", 128L)));
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
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        SessionApplicationService sessionService = new SessionApplicationService(sessions, messages, ids, permissionChecker);
        ChatStreamApplicationService chatStreamService = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.it.ex.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(), events,
                permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
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
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
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
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                new ChatRunStopCoordinator(sessionService, chatStreamService, runService, leaseService,
                        executionRegistry, new AgentRuntimeExecutor(noopRuntime(), limiter),
                        domainAgentExecutor(documentFacade(), limiter), ids),
                interactionService,
                terminalCommitService,
                ids,
                reactor.core.scheduler.Schedulers.boundedElastic(),
                new com.huawei.it.ex.one.application.config.DomainAgentProperties()
        );
        Instant now = Instant.now();
        sessions.save(new ChatSession("session1", user.tenantId(), user.ownerUserId(), "测试会话", "ACTIVE", "web",
                "msg-user", "msg-assistant", null, null, 1L, null, now, now));
        messages.save(new ChatMessage("msg-user", user.tenantId(), user.ownerUserId(), "session1",
                null, 1L, 0, 1, "user", "再帮我看下方案", null, "run-source",
                "NORMAL", false, null, null, null, null, null, now));
        messages.save(new ChatMessage("msg-assistant", user.tenantId(), user.ownerUserId(), "session1",
                "msg-user", 2L, 1, 1, "assistant", "请补充范围", null, "run-source",
                "NORMAL", false, null, null, null, null,
                "{\"finishReason\":\"WAITING_USER\"}", now));
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
        assertThat(interactionRequests.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.ANSWERED);
        assertThat(messages.messages)
                .filteredOn(message -> "user".equals(message.role()) && "答案".equals(message.content()))
                .singleElement()
                .extracting(ChatMessage::parentMessageId)
                .isEqualTo("msg-assistant");
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
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                new InMemoryExecutionRepository(),
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
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
                        new com.huawei.it.ex.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
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
    void attachmentOnlyNextUsesTrustedFileNameOnlyForIntentQuery() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicReference<ChatCommand> routedCommand = new AtomicReference<>();
        AtomicReference<String> intentQuery = new AtomicReference<>();
        AtomicReference<AgentRuntimeRequest> runtimeRequest = new AtomicReference<>();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                routedCommand.set(request.command());
                intentQuery.set(request.intentQuery());
                var noMatch = new com.huawei.it.ex.one.domain.intent.IntentDecision(
                        "relay", "no_match", com.huawei.it.ex.one.domain.intent.TaskComplexity.COMPLEX,
                        0.0, false, null, Map.of("routeAction", "NO_MATCH"), List.of(), Map.of());
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.ofIntent(
                        RouteTarget.agentRuntime("intent-agent", 0.0, "no match routes to relay"),
                        noMatch, 1L, 0.85)));
            }
        };
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                runtimeRequest.set(request);
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "文档回答"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                return Flux.empty();
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, runtime,
                new CapturingRuntimeBindingRepository(),
                new com.huawei.it.ex.one.application.config.DomainAgentProperties(),
                liveEventBus());

        StepVerifier.create(service.executeRun(user, new TraceContext("relay-trace-attachment"), new ChatCommand(
                                "cmd-attachment-only", null, null, null, null, "web", null,
                                List.of(new AttachmentRef("doc1", "forged-name.txt", "text/plain", 1L)),
                                Map.of(
                                        "language", "zh_CN",
                                        "sceneParam", Map.of(
                                                "region", "CN",
                                                "docList", List.of(Map.of("docId", "forged"))))),
                                RuntimeForwardHeaders.empty())
                        .collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .containsExactly("run.started", "message.snapshot", "run.completed"))
                .verifyComplete();

        assertThat(routedCommand.get()).isNotNull();
        assertThat(routedCommand.get().message()).isEmpty();
        assertThat(intentQuery).hasValue("[用户上传文档] invoice.pdf");
        assertThat(runtimeRequest.get()).isNotNull();
        assertThat(runtimeRequest.get().traceContext().traceId()).isEqualTo("relay-trace-attachment");
        assertThat(runtimeRequest.get().message()).isEmpty();
        assertThat(runtimeRequest.get().attachments()).extracting(AttachmentRef::name)
                .containsExactly("invoice.pdf");
        assertThat(runtimeRequest.get().metadata()).containsEntry("language", "zh_CN");
        assertThat(runtimeRequest.get().metadata().get("sceneParam")).isInstanceOfSatisfying(Map.class,
                sceneParam -> {
                    assertThat(sceneParam).containsEntry("region", "CN");
                    assertThat(sceneParam.get("docList")).isEqualTo(List.of(Map.of(
                            "providerLocatorType", "DOC_ID",
                            "docId", "provider-doc1",
                            "docName", "invoice.pdf",
                            "docSize", 128L)));
                });
        assertThat(messages.messages).filteredOn(message -> "user".equals(message.role()))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.content()).isEmpty();
                    assertThat(messages.attachments.stream()
                            .filter(attachment -> message.id().equals(attachment.messageId())))
                            .singleElement()
                            .satisfies(attachment -> {
                                assertThat(attachment.documentId()).isEqualTo("doc1");
                                assertThat(attachment.name()).isEqualTo("invoice.pdf");
                            });
                });
    }

    @Test
    void firstIntentDomainAgentRouteBuildsTrustedProviderDocumentMetadata() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        var intent = new com.huawei.it.ex.one.domain.intent.IntentDecision(
                "finance.document", "文档分析", com.huawei.it.ex.one.domain.intent.TaskComplexity.SIMPLE,
                0.95, true, "skill-document", Map.of("routeAction", "ROUTE_SINGLE"), List.of(), Map.of());
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.ofIntent(
                        RouteTarget.domainAgent("skill-document", "intent-agent", 0.95, "intent matched"),
                        intent, 1L, 0.85)));
            }
        };
        AtomicReference<DomainAgentRequest> captured = new AtomicReference<>();
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                captured.set(request);
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "图片分析结果"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, noopRuntime(),
                new CapturingRuntimeBindingRepository(),
                new com.huawei.it.ex.one.application.config.DomainAgentProperties());

        StepVerifier.create(service.executeRun(user, new ChatCommand(
                                "cmd-intent-document", null, null, null, null, "web", "分析这张图片",
                                List.of(new AttachmentRef("doc1", "forged.png", "image/png", 1L)),
                                Map.of("sceneParam", Map.of("region", "CN"))))
                        .collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .contains("run.started", "message.snapshot", "run.completed"))
                .verifyComplete();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().query()).isEqualTo("分析这张图片");
        assertThat(captured.get().metadata().get("sceneParam")).isInstanceOfSatisfying(Map.class,
                sceneParam -> {
                    assertThat(sceneParam).containsEntry("region", "CN");
                    assertThat(sceneParam.get("docList")).isEqualTo(List.of(Map.of(
                            "providerLocatorType", "DOC_ID",
                            "docId", "provider-doc1",
                            "docName", "invoice.pdf",
                            "docSize", 128L)));
                });
    }

    @Test
    void explicitDomainAgentTargetRoutesAndBindsDomainAgent() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        NeverCancelRunCache runCache = new NeverCancelRunCache();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        IdGenerator ids = new SequentialIdGenerator();
        CapturingRuntimeBindingRepository bindings = new CapturingRuntimeBindingRepository();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                new InMemoryExecutionRepository(),
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
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
        SessionApplicationService sessionService =
                new SessionApplicationService(sessions, messages, ids, permissionChecker);
        ChatRunApplicationService runService =
                new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions);

        FinanceEXChatService service = new FinanceEXChatService(
                sessionService,
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(bindings, runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                runtimeRouteService(),
                intentRecordService(),
                domainAgentExecutor,
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(noopRuntime(), limiter),
                documents,
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.it.ex.one.application.config.ChatWebSocketProperties()),
                runService,
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                ids
        );
        Map<String, Object> selectedMetadata = SelectedIntentContext.attach(
                Map.of("skillId", "skill-other"), "fund_management", "资金管理");
        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", null,
                        List.of(new AttachmentRef("doc1", "forged-name.txt", "text/plain", 1L)), selectedMetadata,
                        "DOMAIN_AGENT", "skill-tax", ChatRunMode.NEXT, null, null, null,
                        null, null, null, null, Map.of(), "fund-app", "资金助手"),
                        RuntimeForwardHeaders.fromCookieHeader("sid=abc", 8192)))
                .expectNextMatches(event -> "run.started".equals(event.type()))
                .expectNextMatches(event -> "runtime.metadata".equals(event.type())
                        && "DOMAIN_AGENT".equals(event.payload().get("targetType"))
                        && "skill-tax".equals(event.payload().get("targetId"))
                        && "selected_domain_agent".equals(event.payload().get("metadataType"))
                        && "fund_management".equals(event.payload().get("intentId"))
                        && "资金管理".equals(event.payload().get("intentName")))
                .expectNextMatches(event -> "message.delta".equals(event.type()))
                .expectNextMatches(event -> "run.completed".equals(event.type()))
                .verifyComplete();

        assertThat(capturedHeaders.get()).isNotNull();
        assertThat(capturedHeaders.get().cookieHeader()).isEqualTo("sid=abc");
        assertThat(capturedRequest.get()).isNotNull();
        assertThat(capturedRequest.get().query()).isEmpty();
        assertThat(capturedRequest.get().documents()).extracting(UploadedDocument::id).containsExactly("doc1");
        assertThat(capturedRequest.get().metadata())
                .containsExactlyEntriesOf(Map.of("skillId", "skill-other"));
        assertThat(bindings.saved.metadata()).containsEntry("intentCode", "fund_management")
                .containsEntry("intentName", "资金管理");
        ChatRun run = runs.runs.values().stream().findFirst().orElseThrow();
        assertThat(run.routeType()).isEqualTo("DOMAIN_AGENT");
        assertThat(run.agentCode()).isEqualTo("skill-tax");
        assertThat(run.runtimeProvider()).isEqualTo("domain-agent");
        assertThat(run.metadata()).containsExactlyEntriesOf(Map.of("skillId", "skill-other"));
        ChatSession taggedSession = sessions.findById(run.sessionId()).orElseThrow();
        assertThat(taggedSession.appId()).isEqualTo("fund-app");
        assertThat(taggedSession.appName()).isEqualTo("资金助手");
        assertThat(messages.messages).filteredOn(message -> "user".equals(message.role()))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.content()).isEmpty();
                    assertThat(messages.attachments.stream()
                            .filter(attachment -> message.id().equals(attachment.messageId())))
                            .singleElement()
                            .satisfies(attachment -> {
                                assertThat(attachment.documentId()).isEqualTo("doc1");
                                assertThat(attachment.name()).isEqualTo("invoice.pdf");
                            });
                });
        assertThat(messages.parts).anySatisfy(part -> {
            assertThat(part.partType()).isEqualTo("METADATA");
            assertThat(part.payload()).containsEntry("targetId", "skill-tax")
                    .containsEntry("domainAgentId", "skill-tax")
                    .containsEntry("metadataType", "selected_domain_agent")
                    .containsEntry("intentId", "fund_management")
                    .containsEntry("intentName", "资金管理");
        });

        String sessionId = run.sessionId();
        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd2", null, null,
                        sessionId, null, "web", null,
                        List.of(new AttachmentRef("doc2", "another-forged-name.txt", "text/plain", 1L)), Map.of())))
                .expectNextMatches(event -> "run.started".equals(event.type()))
                .expectNextMatches(event -> "runtime.metadata".equals(event.type())
                        && "runtime-binding".equals(event.payload().get("routeSource"))
                        && "fund_management".equals(event.payload().get("intentId"))
                        && "资金管理".equals(event.payload().get("intentName")))
                .expectNextMatches(event -> "message.delta".equals(event.type()))
                .expectNextMatches(event -> "run.completed".equals(event.type()))
                .verifyComplete();

        assertThat(capturedRequest.get().query()).isEmpty();
        assertThat(capturedRequest.get().documents()).extracting(UploadedDocument::id).containsExactly("doc2");
        assertThat(messages.parts.stream()
                .filter(part -> "METADATA".equals(part.partType()))
                .filter(part -> "selected_domain_agent".equals(part.payload().get("metadataType"))))
                .hasSize(2)
                .allSatisfy(part -> assertThat(part.payload())
                        .containsEntry("intentId", "fund_management")
                        .containsEntry("intentName", "资金管理"));
    }

    @Test
    void explicitDomainAgentNextCancelsWaitingInteractionAndUsesCurrentRequest() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
        MultiBindingRuntimeBindingRepository bindings = new MultiBindingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        Instant now = Instant.now();
        ChatSession session = sessions.save(new ChatSession(
                "session-direct", user.tenantId(), user.ownerUserId(), "测试会话", "ACTIVE", "web",
                "msg-wait-assistant", "session-direct", null, null, 2L, null, now, now));
        messages.save(new ChatMessage(
                "msg-old-user", user.tenantId(), user.ownerUserId(), session.id(), null,
                1L, 0, 1, "user", "旧问题", null, "run-wait", "NORMAL", false,
                null, null, null, null, null, now));
        messages.save(new ChatMessage(
                "msg-wait-assistant", user.tenantId(), user.ownerUserId(), session.id(), "msg-old-user",
                2L, 1, 1, "assistant", "请补充信息", null, "run-wait", "NORMAL", false,
                null, null, null, null, "{\"finishReason\":\"WAITING_USER\"}", now));
        ChatRun waitingRun = new ChatRun(
                "run-wait", user.tenantId(), user.ownerUserId(), session.id(), ChatRunStatus.WAITING_USER,
                "AGENT_RUNTIME", null, "relay", "relay-active-session", ChatRunMode.NEXT,
                null, "msg-old-user", "msg-wait-assistant", 1L, 2L, null, now, now,
                Map.of(), now, now);
        runs.save(waitingRun);
        ChatInteractionRequest waiting = new ChatInteractionRequest(
                "interaction-wait", user.tenantId(), user.ownerUserId(), session.id(), waitingRun.id(), null,
                "msg-old-user", "msg-wait-assistant", "relay", "binding-relay-active",
                "relay-active-session", null, ChatInteractionType.AGENT_CLARIFICATION,
                ChatInteractionStatus.WAITING, Map.of("clarifyQuestion", "请补充信息"), Map.of(),
                now.plus(Duration.ofHours(1)), null, null, now, now);
        interactions.insert(waiting);
        bindings.save(new RuntimeBinding(
                "binding-relay-history", user.tenantId(), user.ownerUserId(), session.id(), "relay",
                "msg-old-user", "relay-history-session", RuntimeBindingStatus.RESUMABLE, "run-history",
                null, now.minus(Duration.ofDays(1)), now.minus(Duration.ofDays(1)),
                Map.of("runtimeSessionEstablished", true)));
        bindings.save(new RuntimeBinding(
                "binding-relay-active", user.tenantId(), user.ownerUserId(), session.id(), "relay",
                "msg-wait-assistant", "relay-active-session", RuntimeBindingStatus.ACTIVE, waitingRun.id(),
                null, now, now, Map.of("runtimeSessionEstablished", true)));

        AtomicInteger routeCalls = new AtomicInteger();
        AtomicReference<DomainAgentRequest> capturedRequest = new AtomicReference<>();
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                capturedRequest.set(request);
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "直连回答"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, countingRuntimeRouteService(routeCalls), domainClient,
                noopRuntime(), bindings, new com.huawei.it.ex.one.application.config.DomainAgentProperties(),
                liveEventBus(), interactions);
        AgentModeProfile requestedMode = new AgentModeProfile(List.of(
                new AgentModeSelection("thinking", "deep", "深度思考")));

        StepVerifier.create(service.executeRun(user, new ChatCommand(
                        "cmd-ordinary", null, null, session.id(), null, "web", "普通追问",
                        List.of(), Map.of())))
                .expectError(ChatInteractionUnavailableException.class)
                .verify();
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(messages.messages).hasSize(2);

        StepVerifier.create(service.executeRun(user, new ChatCommand(
                                "cmd-direct", null, null, session.id(), null, "web", "本轮直连问题",
                                List.of(), Map.of("scene", "current"), "DOMAIN_AGENT", "fund-agent",
                                ChatRunMode.NEXT, null, null, null,
                                null, null, null, null, Map.of(), null, null, requestedMode))
                        .collectList())
                .assertNext(stream -> {
                    assertThat(stream).extracting(ChatEvent::type)
                            .containsExactly("run.started", "runtime.metadata", "message.snapshot", "run.completed");
                    assertThat(stream).filteredOn(event -> "selected_domain_agent".equals(
                                    event.payload().get("metadataType")))
                            .allSatisfy(event -> assertThat(event.payload()).doesNotContainKey("agentMode"));
                })
                .verifyComplete();

        assertThat(routeCalls).hasValue(0);
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.CANCELLED);
        assertThat(runs.runs.get(waitingRun.id()).status()).isEqualTo(ChatRunStatus.WAITING_USER);
        assertThat(capturedRequest.get()).isNotNull();
        assertThat(capturedRequest.get().query()).isEqualTo("本轮直连问题");
        assertThat(capturedRequest.get().domainAgentId()).isEqualTo("fund-agent");
        assertThat(capturedRequest.get().metadata()).containsExactlyEntriesOf(Map.of("scene", "current"));
        assertThat(messages.messages).filteredOn(message -> "user".equals(message.role()))
                .filteredOn(message -> "本轮直连问题".equals(message.content()))
                .singleElement()
                .extracting(ChatMessage::parentMessageId)
                .isEqualTo("msg-wait-assistant");
        assertThat(bindings.bindingsForProvider("relay"))
                .extracting(RuntimeBinding::status)
                .containsExactlyInAnyOrder(RuntimeBindingStatus.RESUMABLE, RuntimeBindingStatus.CANCELLED);
        assertThat(bindings.bindingsForProvider("domain-agent"))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
                    assertThat(binding.metadata()).containsEntry("routeSource", "front-selected")
                            .containsEntry("domainAgentId", "fund-agent");
                    assertThat(AgentModeBindingContext.fromBinding(binding)).isEqualTo(requestedMode);
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
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                new InMemoryExecutionRepository(),
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
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
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
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
                        new com.huawei.it.ex.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
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
    void recordsEffectiveRelayRouteBeforeRuntimeAndKeepsItWhenRuntimeFails() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        CapturingRouteMemoryService routeMemory = new CapturingRouteMemoryService();
        AtomicBoolean recordedBeforeRuntime = new AtomicBoolean();
        AtomicReference<AgentRuntimeRequest> runtimeRequest = new AtomicReference<>();
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                runtimeRequest.set(request);
                recordedBeforeRuntime.set(routeMemory.routeDecisions.size() == 1);
                return Flux.error(new IllegalStateException("relay failed after route binding"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                var intent = new com.huawei.it.ex.one.domain.intent.IntentDecision(
                        "multi_intent", "多意图", com.huawei.it.ex.one.domain.intent.TaskComplexity.COMPLEX,
                        0.7, false, null, Map.of(
                                "routeAction", "ROUTE_MULTI",
                                "candidateIntentNames", List.of("财经智能问数", "财经知识助手")),
                        List.of(), Map.of());
                RouteTarget route = RouteTarget.agentRuntime("intent-agent", 0.7, "multiple intents");
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.ofIntent(route, intent, 5L, 0.0)));
            }
        };
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, routeService, runtime,
                new InMemoryExecutionRepository(), new ChatRunOperationalProperties(),
                new NeverCancelRunCache(), routeMemory);
        UserContext user = new UserContext("tenant1", "user1", "User One");

        StepVerifier.create(service.executeRun(user, new ChatCommand(
                        "cmd-route-failure", null, null, null, null, "web",
                        "需要处理一个复杂任务",
                        List.of(new AttachmentRef("doc1", "forged-name.txt", "text/plain", 1L)),
                        Map.of())).collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .contains("run.started", "run.failed"))
                .verifyComplete();

        assertThat(recordedBeforeRuntime).isTrue();
        assertThat(runtimeRequest.get().message()).isEqualTo("需要处理一个复杂任务");
        assertThat(routeMemory.routeDecisions).singleElement().satisfies(command -> {
            assertThat(command.query())
                    .isEqualTo("需要处理一个复杂任务 [用户上传文档] invoice.pdf");
            assertThat(command.intent().intentCode()).isEqualTo("relay");
            assertThat(command.intent().intentName()).isEqualTo("no_match");
            assertThat(command.intent().slots())
                    .containsEntry("routeAction", "ROUTE_MULTI")
                    .containsEntry("candidateIntentNames", List.of("财经智能问数", "财经知识助手"));
        });
        assertThat(messages.messages).filteredOn(message -> "user".equals(message.role()))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.content()).isEqualTo("需要处理一个复杂任务");
                    assertThat(messages.attachments).singleElement()
                            .satisfies(attachment -> assertThat(attachment.name()).isEqualTo("invoice.pdf"));
                });
        assertThat(runs.runs.values()).singleElement()
                .satisfies(run -> assertThat(run.status()).isEqualTo(ChatRunStatus.FAILED));
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
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                new InMemoryExecutionRepository(),
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
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
                        new com.huawei.it.ex.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
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
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                new InMemoryExecutionRepository(),
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
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
                        new com.huawei.it.ex.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
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
                        new com.huawei.it.ex.one.application.config.ChatWebSocketProperties()),
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                runs,
                new ChatRunLeaseApplicationService(executions,
                        (ApplicationInstanceIdProvider) () -> "instance-test",
                        new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
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
        assertThat(bindings.saved.expiresAt()).isNull();
        assertThat(bindings.saved.metadata()).containsEntry("runtimeSessionEstablished", true);
    }

    @Test
    void terminalCommitReleasesRelayRouteButKeepsSessionResumableAfterCompletedRun() {
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
                        new com.huawei.it.ex.one.application.config.ChatWebSocketProperties()),
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                runs,
                new ChatRunLeaseApplicationService(executions,
                        (ApplicationInstanceIdProvider) () -> "instance-test",
                        new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
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
        assertThat(bindings.saved.status()).isEqualTo(RuntimeBindingStatus.RESUMABLE);
        assertThat(bindings.saved.leafMessageId()).isEqualTo("msg-assistant");
        assertThat(bindings.saved.runtimeSessionId()).isEqualTo("runtime-session-1");
        assertThat(bindings.saved.expiresAt()).isNull();
        assertThat(bindings.saved.metadata()).containsEntry("runtimeSessionEstablished", true);
    }

    @Test
    void terminalCommitWaitingUserCreatesNextIntentClarificationAssistant() {
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
                ChatInteractionStatus.ANSWERED,
                Map.of("sourceType", "intent-clarification-request", "originalQuery", "看下方案"),
                Map.of("questionnaireAnswers", Map.of("方向", "规范"), "answerText", "规范"),
                now.plus(Duration.ofHours(1)),
                now,
                null,
                now,
                now);
        interactionRequests.insert(previous);
        ChatMessage answerMessage = messages.save(new ChatMessage(
                "msg-answer", user.tenantId(), user.ownerUserId(), session.id(), "msg-assistant",
                3L, 2, 1, "user", "规范", null, "run1",
                "NORMAL", false, null, null, null, null, null, now));
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(interactionRequests, ids,
                permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService commitService = new ChatRunTerminalCommitService(
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.it.ex.one.application.config.ChatWebSocketProperties()),
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                runs,
                new ChatRunLeaseApplicationService(executions,
                        (ApplicationInstanceIdProvider) () -> "instance-test",
                        new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
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
                answerMessage,
                "msg-assistant-next",
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
                        new ChatRunMessagePlan(ChatRunMode.NEXT, previous.assistantMessageId(), answerMessage, null),
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
        ChatMessage originalAssistant = messages.findByOwnerAndId(
                user.tenantId(), user.ownerUserId(), "msg-assistant").orElseThrow();
        assertThat(originalAssistant.runId()).isEqualTo("run-old");
        ChatMessage nextAssistant = messages.findByOwnerAndId(
                user.tenantId(), user.ownerUserId(), "msg-assistant-next").orElseThrow();
        assertThat(nextAssistant.parentMessageId()).isEqualTo(answerMessage.id());
        assertThat(nextAssistant.content()).isEqualTo("你想看哪个方向？");
        assertThat(nextAssistant.runId()).isEqualTo("run1");
        assertThat(nextAssistant.parts()).extracting(ChatMessagePart::partType)
                .contains("INTENT_CLARIFICATION_REQUEST");
    }

    private RouteSignalApplicationService systemRouteService() {
        return new RouteSignalApplicationService(request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, user) -> null), new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public RouteSignalResult routeInitial(UserContext user, ChatSession session, ChatCommand command,
                                                  List<AttachmentRef> attachments,
                                                  com.huawei.it.ex.one.domain.memory.MemoryContext memory) {
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
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        SessionApplicationService sessionService = new SessionApplicationService(
                sessions, messages, ids, permissionChecker);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(
                events, new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.it.ex.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(
                runs, new NeverCancelRunCache(), events, permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
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
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                stopCoordinator,
                interactionService,
                terminalCommitService,
                ids,
                reactor.core.scheduler.Schedulers.boundedElastic(),
                new com.huawei.it.ex.one.application.config.DomainAgentProperties(),
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
                intentAgent((command, memory, user) -> null), new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public RouteSignalResult routeInitial(UserContext user, ChatSession session, ChatCommand command,
                                                  List<AttachmentRef> attachments,
                                                  com.huawei.it.ex.one.domain.memory.MemoryContext memory) {
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
        return documentFacade(null);
    }

    private DocumentFacade documentFacade(
            java.util.function.BiFunction<UserContext, List<AttachmentRef>, ResolvedChatAttachments> resolver) {
        return new DocumentFacade() {
            @Override public Mono<UploadedDocument> upload(UserContext user, DocumentUploadCommand command) { return Mono.empty(); }
            @Override public Mono<DocumentLibraryPage> list(UserContext user, DocumentLibraryQuery query) { return Mono.empty(); }
            @Override public Mono<UploadedDocument> get(UserContext user, String documentId) { return Mono.empty(); }
            @Override public Mono<UploadedDocument> update(UserContext user, String documentId, DocumentUpdateCommand command) { return Mono.empty(); }
            @Override public Mono<UploadedDocument> delete(UserContext user, String documentId) { return Mono.empty(); }
            @Override public Mono<com.huawei.it.ex.one.domain.document.DocumentDownload> prepareDownload(UserContext user, String documentId) { return Mono.empty(); }
            @Override public Mono<UploadedDocument> prepareAccess(UserContext user, String documentId) { return Mono.empty(); }
            @Override public Mono<StoredObjectContent> download(UserContext user, String documentId) { return Mono.empty(); }
            @Override public List<AttachmentRef> resolveAttachmentsForUser(UserContext user, List<AttachmentRef> attachments) {
                return resolveChatAttachmentsForUser(user, attachments).attachments();
            }
            @Override public List<UploadedDocument> resolveDocumentsForUser(UserContext user, List<AttachmentRef> attachments) {
                return resolveChatAttachmentsForUser(user, attachments).documents();
            }
            @Override public ResolvedChatAttachments resolveChatAttachmentsForUser(UserContext user, List<AttachmentRef> attachments) {
                if (resolver != null) {
                    return resolver.apply(user, attachments);
                }
                if (attachments == null || attachments.isEmpty()) {
                    return ResolvedChatAttachments.empty();
                }
                Instant now = Instant.now();
                List<AttachmentRef> trusted = attachments.stream()
                        .map(attachment -> new AttachmentRef(
                                attachment.documentId(), "invoice.pdf", "application/pdf", 128L, 32L, "LOCAL_UPLOAD"))
                        .toList();
                List<UploadedDocument> documents = trusted.stream()
                        .map(attachment -> new UploadedDocument(
                                attachment.documentId(), user.tenantId(), user.ownerUserId(), null,
                                attachment.name(), "api-store", attachment.documentId(), attachment.contentType(),
                                attachment.sizeBytes(), "AVAILABLE", attachment.source(), attachment.tokenSize(),
                                "{\"providerDocument\":{\"providerLocatorType\":\"DOC_ID\","
                                        + "\"docId\":\"provider-" + attachment.documentId() + "\","
                                        + "\"docName\":\"" + attachment.name() + "\",\"docSize\":128}}",
                                now, now))
                        .toList();
                return new ResolvedChatAttachments(trusted, documents);
            }
            @Override public Map<String, Object> replaceRuntimeDocumentMetadata(Map<String, Object> metadata, List<UploadedDocument> documents) {
                Map<String, Object> copy = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
                Map<String, Object> scene = copy.get("sceneParam") instanceof Map<?, ?> value
                        ? new LinkedHashMap<>((Map<String, Object>) value)
                        : new LinkedHashMap<>();
                scene.remove("docList");
                if (documents != null && !documents.isEmpty()) {
                    scene.put("docList", documents.stream()
                            .map(document -> Map.<String, Object>of(
                                    "providerLocatorType", "DOC_ID",
                                    "docId", "provider-" + document.id(),
                                    "docName", document.originalName(),
                                    "docSize", document.sizeBytes()))
                            .toList());
                }
                if (!scene.isEmpty() || copy.containsKey("sceneParam")) {
                    copy.put("sceneParam", scene);
                }
                return Map.copyOf(copy);
            }
        };
    }

    private DomainAgentExecutor domainAgentExecutor(DocumentFacade documentFacade, WorkloadConcurrencyLimiter limiter) {
        return new DomainAgentExecutor(new DomainAgentClient() {
            @Override public Flux<ChatEvent> query(DomainAgentRequest request) { return Flux.empty(); }
            @Override public Mono<Void> cancel(DomainAgentCancelRequest request) { return Mono.empty(); }
        }, documentFacade, limiter);
    }

    private void awaitEvent(InMemoryEventStore events, String type) {
        awaitEventCount(events, type, 1);
    }

    private void awaitEventCount(InMemoryEventStore events, String type, long expectedCount) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (events.events.stream().filter(event -> type.equals(event.type())).count() >= expectedCount) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for event " + type, ex);
            }
        }
        throw new AssertionError("Timed out waiting for " + expectedCount + " event(s) " + type + ", actual events="
                + events.events.stream().map(ChatEvent::type).toList());
    }

    private <T> void awaitValue(AtomicReference<T> reference, T expected, String label) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (java.util.Objects.equals(reference.get(), expected)) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + label, ex);
            }
        }
        throw new AssertionError("Timed out waiting for " + label + ", expected=" + expected
                + ", actual=" + reference.get());
    }

    private void awaitAtomicValue(AtomicInteger value, int expected, String label) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (value.get() == expected) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + label, ex);
            }
        }
        throw new AssertionError("Timed out waiting for " + label + ", expected=" + expected
                + ", actual=" + value.get());
    }

    private ChatInteractionRequest awaitWaitingInteraction(InMemoryInteractionRequestRepository interactions,
                                                            String... excludedInteractionIds) {
        java.util.Set<String> excluded = excludedInteractionIds == null
                ? java.util.Set.of()
                : java.util.Set.of(excludedInteractionIds);
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ChatInteractionRequest> waiting = interactions.requests.values().stream()
                    .filter(request -> !excluded.contains(request.id()))
                    .filter(request -> request.status() == ChatInteractionStatus.WAITING)
                    .findFirst();
            if (waiting.isPresent()) {
                return waiting.get();
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for route switch Interaction", ex);
            }
        }
        throw new AssertionError("Timed out waiting for another route switch Interaction, actual="
                + interactions.requests.values());
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
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
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
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
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
                            new com.huawei.it.ex.one.application.config.ChatWebSocketProperties()),
                    new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                    leaseService,
                    new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                    executionRegistry,
                    new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
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
                        new com.huawei.it.ex.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
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
        return financeServiceWithTerminalCommit(sessions, messages, runs, events, routeService, runtime,
                executions, runProperties, runCache, null);
    }

    private FinanceEXChatService financeServiceWithTerminalCommit(InMemorySessionRepository sessions,
                                                                   InMemoryMessageRepository messages,
                                                                   InMemoryRunRepository runs,
                                                                   InMemoryEventStore events,
                                                                   RouteSignalApplicationService routeService,
                                                                   AgentRuntime runtime,
                                                                   InMemoryExecutionRepository executions,
                                                                   ChatRunOperationalProperties runProperties,
                                                                   ChatRunCache runCache,
                                                                   RouteMemoryApplicationService routeMemoryService) {
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        RuntimeBindingRepository bindings = runtimeBindingRepository();
        InMemoryInteractionRequestRepository interactionRequests = new InMemoryInteractionRequestRepository();
        SessionApplicationService sessionService = new SessionApplicationService(
                sessions, messages, ids, permissionChecker);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(
                events, new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.it.ex.one.application.config.ChatWebSocketProperties());
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
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(
                        new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                stopCoordinator,
                interactionService,
                terminalCommitService,
                ids,
                reactor.core.scheduler.Schedulers.boundedElastic(),
                new com.huawei.it.ex.one.application.config.DomainAgentProperties(),
                routeMemoryService,
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
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                new InMemoryExecutionRepository(),
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
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
                        new com.huawei.it.ex.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, new NeverCancelRunCache(), events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                ids);
    }

    private FinanceEXChatService financeServiceWithDomainClientAndBindings(
            InMemorySessionRepository sessions,
            InMemoryMessageRepository messages,
            InMemoryRunRepository runs,
            InMemoryEventStore events,
            RouteSignalApplicationService routeService,
            DomainAgentClient domainClient,
            AgentRuntime relayRuntime,
            RuntimeBindingRepository bindings,
            com.huawei.it.ex.one.application.config.DomainAgentProperties domainAgentProperties) {
        return financeServiceWithDomainClientAndBindings(sessions, messages, runs, events, routeService,
                domainClient, relayRuntime, bindings, domainAgentProperties, liveEventBus());
    }

    private FinanceEXChatService financeServiceWithDomainClientAndBindings(
            InMemorySessionRepository sessions,
            InMemoryMessageRepository messages,
            InMemoryRunRepository runs,
            InMemoryEventStore events,
            RouteSignalApplicationService routeService,
            DomainAgentClient domainClient,
            AgentRuntime relayRuntime,
            RuntimeBindingRepository bindings,
            com.huawei.it.ex.one.application.config.DomainAgentProperties domainAgentProperties,
            ChatLiveEventBus eventBus) {
        return financeServiceWithDomainClientAndBindings(sessions, messages, runs, events, routeService,
                domainClient, relayRuntime, bindings, domainAgentProperties, eventBus,
                new InMemoryInteractionRequestRepository());
    }

    private FinanceEXChatService financeServiceWithDomainClientAndBindings(
            InMemorySessionRepository sessions,
            InMemoryMessageRepository messages,
            InMemoryRunRepository runs,
            InMemoryEventStore events,
            RouteSignalApplicationService routeService,
            DomainAgentClient domainClient,
            AgentRuntime relayRuntime,
            RuntimeBindingRepository bindings,
            com.huawei.it.ex.one.application.config.DomainAgentProperties domainAgentProperties,
            ChatLiveEventBus eventBus,
            InMemoryInteractionRequestRepository interactions) {
        return financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, relayRuntime, bindings,
                domainAgentProperties, eventBus, interactions, runtimeBindingCache(), null);
    }

    private FinanceEXChatService financeServiceWithDomainClientAndBindings(
            InMemorySessionRepository sessions,
            InMemoryMessageRepository messages,
            InMemoryRunRepository runs,
            InMemoryEventStore events,
            RouteSignalApplicationService routeService,
            DomainAgentClient domainClient,
            AgentRuntime relayRuntime,
            RuntimeBindingRepository bindings,
            com.huawei.it.ex.one.application.config.DomainAgentProperties domainAgentProperties,
            ChatLiveEventBus eventBus,
            InMemoryInteractionRequestRepository interactions,
            RuntimeBindingCache bindingCache,
            reactor.core.scheduler.Scheduler domainAgentControlIoScheduler) {
        return financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, relayRuntime, bindings,
                domainAgentProperties, eventBus, interactions, bindingCache, domainAgentControlIoScheduler,
                documentFacade());
    }

    private FinanceEXChatService financeServiceWithDomainClientAndBindings(
            InMemorySessionRepository sessions,
            InMemoryMessageRepository messages,
            InMemoryRunRepository runs,
            InMemoryEventStore events,
            RouteSignalApplicationService routeService,
            DomainAgentClient domainClient,
            AgentRuntime relayRuntime,
            RuntimeBindingRepository bindings,
            com.huawei.it.ex.one.application.config.DomainAgentProperties domainAgentProperties,
            ChatLiveEventBus eventBus,
            InMemoryInteractionRequestRepository interactions,
            RuntimeBindingCache bindingCache,
            reactor.core.scheduler.Scheduler domainAgentControlIoScheduler,
            DocumentFacade documents) {
        IdGenerator ids = new SequentialIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        ChatRunOperationalProperties runProperties = new ChatRunOperationalProperties();
        SessionApplicationService sessionService = new SessionApplicationService(
                sessions, messages, ids, permissionChecker);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(
                events, new LocalChatEventStreamRegistry(), eventBus, runs, permissionChecker, sessions,
                new com.huawei.it.ex.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(
                runs, new NeverCancelRunCache(), events, permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions, (ApplicationInstanceIdProvider) () -> "instance-test", runProperties, ids,
                executionRegistry);
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(
                interactions, ids, permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService terminalCommitService = new ChatRunTerminalCommitService(
                streamService, sessionService, runs, leaseService, bindings, interactionService, Duration.ZERO);
        RuntimeBindingApplicationService bindingService = new RuntimeBindingApplicationService(
                bindings, bindingCache, ids, Duration.ZERO, "relay");
        DomainAgentExecutor domainExecutor = new DomainAgentExecutor(domainClient, documents, limiter);
        AgentRuntimeExecutor relayExecutor = new AgentRuntimeExecutor(relayRuntime, limiter);
        ChatRunStopCoordinator stopCoordinator = new ChatRunStopCoordinator(
                sessionService, streamService, runService, leaseService, executionRegistry,
                relayExecutor, domainExecutor, ids);
        FinanceEXChatService service = new FinanceEXChatService(
                sessionService,
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                bindingService,
                routeService,
                intentRecordService(),
                domainExecutor,
                new SystemResponseExecutor(),
                relayExecutor,
                documents,
                streamService,
                runService,
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(
                        new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                stopCoordinator,
                interactionService,
                terminalCommitService,
                ids,
                reactor.core.scheduler.Schedulers.boundedElastic(),
                domainAgentProperties);
        if (domainAgentControlIoScheduler != null) {
            service.setDomainAgentControlIoScheduler(domainAgentControlIoScheduler);
        }
        service.setRunAdmissionCommitService(
                new ChatRunAdmissionCommitService(sessionService, runService, interactionService, bindingService));
        return service;
    }

    private RouteSignalApplicationService repeatedDomainAgentRouteService(AtomicInteger routeCalls,
                                                                           String domainAgentId) {
        return new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                routeCalls.incrementAndGet();
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                        domainAgentId, "intent-agent", 1.0, "test intent route"))));
            }
        };
    }

    private DomainAgentClient refusingDomainAgentClient() {
        return new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
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

    private static final class FailingSecondStreamingObservationRunRepository extends InMemoryRunRepository {
        private final AtomicInteger streamingObservationSaves = new AtomicInteger();

        @Override
        public ChatRun save(ChatRun run) {
            if (run != null && run.status() == ChatRunStatus.RUNNING
                    && run.firstSeq() != null && run.lastSeq() != null
                    && run.lastSeq() > run.firstSeq()
                    && streamingObservationSaves.incrementAndGet() == 2) {
                throw new IllegalStateException("run observation db down");
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
        @Override public ChatEvent appendWithExecutionGuard(ChatEvent event, com.huawei.it.ex.one.domain.chat.RunExecutionClaim claim) { return append(event); }
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
        @Override public List<RuntimeBinding> findResumableBySession(String tenantId, String userId, String sessionId,
                                                                     String provider) {
            return Optional.ofNullable(saved)
                    .filter(binding -> tenantId.equals(binding.tenantId()))
                    .filter(binding -> userId.equals(binding.userId()))
                    .filter(binding -> sessionId.equals(binding.chatSessionId()))
                    .filter(binding -> provider.equals(binding.provider()))
                    .filter(binding -> binding.status() == RuntimeBindingStatus.RESUMABLE)
                    .stream()
                    .toList();
        }
        @Override public RuntimeBinding save(RuntimeBinding binding) {
            saved = binding;
            savedHistory.add(binding);
            return binding;
        }
    }

    private static class MultiBindingRuntimeBindingRepository implements RuntimeBindingRepository {
        private final Map<String, RuntimeBinding> bindings = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Optional<RuntimeBinding> findById(String bindingId) {
            return Optional.ofNullable(bindings.get(bindingId));
        }

        @Override
        public Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId,
                                                   String provider) {
            return findActiveBySession(tenantId, userId, sessionId, provider).stream()
                    .max(Comparator.comparing(RuntimeBinding::updatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())));
        }

        @Override
        public List<RuntimeBinding> findActiveBySession(String tenantId, String userId, String sessionId,
                                                        String provider) {
            return matching(tenantId, userId, sessionId).stream()
                    .filter(binding -> provider.equals(binding.provider()))
                    .filter(binding -> binding.status() == RuntimeBindingStatus.ACTIVE)
                    .toList();
        }

        @Override
        public List<RuntimeBinding> findActiveBySession(String tenantId, String userId, String sessionId) {
            return matching(tenantId, userId, sessionId).stream()
                    .filter(binding -> binding.status() == RuntimeBindingStatus.ACTIVE)
                    .toList();
        }

        @Override
        public List<RuntimeBinding> findResumableBySession(String tenantId, String userId, String sessionId,
                                                           String provider) {
            return matching(tenantId, userId, sessionId).stream()
                    .filter(binding -> provider.equals(binding.provider()))
                    .filter(binding -> binding.status() == RuntimeBindingStatus.RESUMABLE)
                    .toList();
        }

        @Override
        public RuntimeBinding save(RuntimeBinding binding) {
            bindings.put(binding.id(), binding);
            return binding;
        }

        private List<RuntimeBinding> bindingsForProvider(String provider) {
            return bindings.values().stream()
                    .filter(binding -> provider.equals(binding.provider()))
                    .sorted(Comparator.comparing(RuntimeBinding::createdAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        }

        private List<RuntimeBinding> matching(String tenantId, String userId, String sessionId) {
            return bindings.values().stream()
                    .filter(binding -> tenantId.equals(binding.tenantId()))
                    .filter(binding -> userId.equals(binding.userId()))
                    .filter(binding -> sessionId.equals(binding.chatSessionId()))
                    .toList();
        }
    }

    private static final class ThreadCapturingRuntimeBindingRepository
            extends MultiBindingRuntimeBindingRepository {
        private final AtomicReference<String> cancellationThread = new AtomicReference<>();

        @Override
        public RuntimeBinding save(RuntimeBinding binding) {
            if (binding != null && binding.status() == RuntimeBindingStatus.CANCELLED) {
                cancellationThread.compareAndSet(null, Thread.currentThread().getName());
            }
            return super.save(binding);
        }
    }

    private static final class BlockingRuntimeBindingCache implements RuntimeBindingCache {
        private final AtomicInteger puts = new AtomicInteger();
        private final AtomicBoolean blockingEvictionClaimed = new AtomicBoolean(false);
        private final java.util.concurrent.CountDownLatch evictionStarted = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseEviction = new java.util.concurrent.CountDownLatch(1);

        @Override
        public Optional<RuntimeBinding> get(String tenantId, String userId, String sessionId) {
            return Optional.empty();
        }

        @Override
        public void put(RuntimeBinding binding) {
            puts.incrementAndGet();
        }

        @Override
        public void evict(String tenantId, String userId, String sessionId) {
            if (puts.get() <= 0 || !blockingEvictionClaimed.compareAndSet(false, true)) {
                return;
            }
            evictionStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    releaseEviction.await();
                    break;
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean awaitEvictionStarted() {
            try {
                return evictionStarted.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void releaseEviction() {
            releaseEviction.countDown();
        }
    }

    private static class InMemoryInteractionRequestRepository implements ChatInteractionRequestRepository {
        private final Map<String, ChatInteractionRequest> requests = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicInteger markWaitingForRunCalls = new AtomicInteger();
        private final AtomicInteger claimCalls = new AtomicInteger();

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
            claimCalls.incrementAndGet();
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
        @Override
        public int cancelOpenBySession(String tenantId, String userId, String sessionId, Instant cancelledAt) {
            int cancelled = 0;
            for (ChatInteractionRequest current : List.copyOf(requests.values())) {
                if (!tenantId.equals(current.tenantId()) || !userId.equals(current.userId())
                        || !sessionId.equals(current.sessionId())
                        || (current.status() != ChatInteractionStatus.WAITING
                        && current.status() != ChatInteractionStatus.RESPONDING)) {
                    continue;
                }
                requests.put(current.id(), new ChatInteractionRequest(
                        current.id(), current.tenantId(), current.userId(), current.sessionId(),
                        current.sourceRunId(), current.continueRunId(), current.userMessageId(),
                        current.assistantMessageId(), current.runtimeProvider(), current.runtimeBindingId(),
                        current.runtimeSessionId(), current.approvalId(), current.interactionType(),
                        ChatInteractionStatus.CANCELLED, current.requestPayload(), current.responsePayload(),
                        current.expiresAt(), current.answeredAt(), cancelledAt, current.createdAt(), cancelledAt));
                cancelled++;
            }
            return cancelled;
        }
        @Override
        public int cancelWaitingById(String tenantId, String userId, String interactionId, Instant cancelledAt) {
            ChatInteractionRequest current = requests.get(interactionId);
            if (current == null || !tenantId.equals(current.tenantId()) || !userId.equals(current.userId())
                    || current.status() != ChatInteractionStatus.WAITING) {
                return 0;
            }
            requests.put(current.id(), new ChatInteractionRequest(
                    current.id(), current.tenantId(), current.userId(), current.sessionId(),
                    current.sourceRunId(), current.continueRunId(), current.userMessageId(),
                    current.assistantMessageId(), current.runtimeProvider(), current.runtimeBindingId(),
                    current.runtimeSessionId(), current.approvalId(), current.interactionType(),
                    ChatInteractionStatus.CANCELLED, current.requestPayload(), current.responsePayload(),
                    current.expiresAt(), current.answeredAt(), cancelledAt, current.createdAt(), cancelledAt));
            return 1;
        }
        @Override public int markExpired(String tenantId, String userId, String interactionId) { return 0; }
    }

    private static class InMemoryMessageRepository implements ChatMessageRepository {
        private final List<ChatMessage> messages = new ArrayList<>();
        private final List<ChatMessagePart> parts = new ArrayList<>();
        private final List<ChatMessageAttachment> attachments = new ArrayList<>();
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
        @Override public ChatMessageAttachment saveAttachment(ChatMessageAttachment attachment) {
            attachments.add(attachment);
            return attachment;
        }
        @Override public List<ChatMessageAttachment> findAttachments(String tenantId, String userId, String messageId) {
            return attachments.stream().filter(attachment -> messageId.equals(attachment.messageId())).toList();
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

    private static final class CapturingRouteMemoryService extends RouteMemoryApplicationService {
        private final List<RouteMemoryRouteCommand> routeDecisions = new CopyOnWriteArrayList<>();

        private CapturingRouteMemoryService() {
            super(null, null, null);
        }

        @Override
        public void recordRouteDecision(RouteMemoryRouteCommand command) {
            routeDecisions.add(command);
        }
    }

    private static class FixedIdGenerator implements IdGenerator {
        @Override public String newId(String prefix, IdGenerateContext context) { return prefix + "_1"; }
    }

    private static class SequentialIdGenerator implements IdGenerator {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public String newId(String prefix, IdGenerateContext context) {
            return prefix + "_" + sequence.incrementAndGet();
        }
    }
}
