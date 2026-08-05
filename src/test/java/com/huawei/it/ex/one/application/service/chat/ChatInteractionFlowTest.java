package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.ChatInteractionProperties;
import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.config.MemoryProperties;
import com.huawei.it.ex.one.application.config.RouteSignalProperties;
import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteraction;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.application.service.memory.MemoryApplicationService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalFrame;
import com.huawei.it.ex.one.application.service.routing.RouteSignalRequest;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.SystemResponseExecutor;
import com.huawei.it.ex.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatInteractionUnavailableException;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecution;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStopResult;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.usecase.UseCaseMatchResult;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
class ChatInteractionFlowTest extends ChatFlowTestSupport {
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
    void stoppingInteractionContinuationCancelsMatchingClaim() {
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
        assertThat(released.status()).isEqualTo(ChatInteractionStatus.CANCELLED);
        assertThat(released.continueRunId()).isEqualTo(run.id());
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

        // 模拟旧版本或异常窗口遗留的 CANCELLED run + RESPONDING Interaction。
        ChatInteractionRequest responding = new ChatInteractionRequest(
                released.id(), released.tenantId(), released.userId(), released.sessionId(),
                released.sourceRunId(), run.id(), released.userMessageId(), released.assistantMessageId(),
                released.runtimeProvider(), released.runtimeBindingId(), released.runtimeSessionId(),
                released.approvalId(), released.interactionType(), ChatInteractionStatus.RESPONDING,
                released.requestPayload(), Map.of("问题", "重试答案"), released.expiresAt(),
                released.answeredAt(), null, released.createdAt(), Instant.now());
        interactions.requests.put(responding.id(), responding);
        coordinator.stopRun(user, run.id(), "USER_STOP", RuntimeForwardHeaders.empty()).block();

        ChatInteractionRequest reconciled = interactions.requests.get(waiting.id());
        assertThat(reconciled.status()).isEqualTo(ChatInteractionStatus.CANCELLED);
        assertThat(reconciled.continueRunId()).isEqualTo(run.id());
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
        FinanceEXChatService service = ChatFlowTestFixture.service(
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
                "测试会话", "ACTIVE", "mobile", "msg-user", "msg-assistant", null, null, 1L,
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
                        null, null, null, session.id(), null, null, null, List.of(), Map.of(),
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
    void interactionSessionContextMismatchIsRejectedBeforeClaim() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        Instant now = Instant.now();
        ChatSession session = sessions.save(new ChatSession(
                "session1", user.tenantId(), user.ownerUserId(), "测试会话", "ACTIVE", "mobile",
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
                        null, null, null, "another-session", null, "mobile", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, waiting.id(), null, null, Map.of("请补充范围", "账务")),
                        RuntimeForwardHeaders.empty()).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId 与 Interaction 所属会话不一致");

        assertThatThrownBy(() -> service.startRun(user, new ChatCommand(
                        null, null, null, session.id(), null, "mobile", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, waiting.id(), null, null, Map.of("请补充范围", "账务"),
                        "tax-app", "税务助手"), RuntimeForwardHeaders.empty()).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appId 与已有会话不一致");

        assertThatThrownBy(() -> service.startRun(user, new ChatCommand(
                        null, null, null, session.id(), null, "web", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, waiting.id(), null, null, Map.of("请补充范围", "账务")),
                        RuntimeForwardHeaders.empty()).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel 与已有会话不一致");

        assertThatThrownBy(() -> service.startRun(user, new ChatCommand(
                        null, null, null, session.id(), null, "Mobile", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, waiting.id(), null, null, Map.of("请补充范围", "账务")),
                        RuntimeForwardHeaders.empty()).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel 与已有会话不一致");

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
        FinanceEXChatService service = ChatFlowTestFixture.service(
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
                payload.put("questions", List.of(Map.of(
                        "question", "请选择技术方案",
                        "options", List.of(Map.of("label", "方案A")),
                        "multi_select", false)));
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

        FinanceEXChatService service = ChatFlowTestFixture.service(
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
                    assertThat(request.runtimeBindingId()).isEqualTo(bindings.saved.id());
                    assertThat(request.runtimeSessionId()).isEqualTo(bindings.saved.runtimeSessionId());
                    assertThat(request.approvalId()).isEqualTo("approval-1");
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
        FinanceEXChatService service = ChatFlowTestFixture.service(
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
                        "clarifyTriggerQuery", "账务相关",
                        "clarificationHistory", List.of(Map.of(
                                "type", "clarify",
                                "query", "再帮我看下方案",
                                "clarifyQuestion", "您是想看支付方案还是账务方案？",
                                "answer", "账务相关")),
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
                "用户:再帮我看下方案；系统追问:您是想看支付方案还是账务方案？；用户:账务相关"
                        + "；系统追问:您提到的方案具体是指哪个方案？"
                        + "；用户:我是说账务审批的方案 [用户上传文档] invoice.pdf");
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
        FinanceEXChatService service = ChatFlowTestFixture.service(
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
}
