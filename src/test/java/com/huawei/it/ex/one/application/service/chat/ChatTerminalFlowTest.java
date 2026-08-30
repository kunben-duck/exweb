/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.ChatInteractionProperties;
import com.huawei.it.ex.one.application.config.MemoryProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.application.service.memory.MemoryApplicationService;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.SystemResponseExecutor;
import com.huawei.it.ex.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatRunExecution;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.chat.RunCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RunWaitingUserEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
class ChatTerminalFlowTest extends ChatFlowTestSupport {
    @Test
    void relayStopWaitsForCancellationBarrierBeforeTerminalCommit() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        seedRunningRun(sessions, messages, runs, user, "run1", "session1", "msg-user");
        Sinks.One<Void> cancelConfirmation = Sinks.one();
        AtomicBoolean cancelSubscribed = new AtomicBoolean(false);
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return Flux.empty();
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.defer(() -> {
                    cancelSubscribed.set(true);
                    return cancelConfirmation.asMono();
                });
            }
        };
        FinanceEXChatService service = stopService(sessions, messages, runs, events, runtime);

        StepVerifier.create(service.stopRun(user, "run1", RuntimeForwardHeaders.empty()))
                .then(() -> {
                    assertThat(cancelSubscribed).isTrue();
                    assertThat(runs.findById("run1").orElseThrow().status())
                            .isEqualTo(ChatRunStatus.CANCELLING);
                    assertThat(events.events).noneMatch(event -> "run.cancelled".equals(event.type()));
                })
                .expectNoEvent(Duration.ofMillis(30))
                .then(cancelConfirmation::tryEmitEmpty)
                .assertNext(result -> assertThat(result.status()).isEqualTo(ChatRunStatus.CANCELLED))
                .verifyComplete();

        assertThat(events.events).anyMatch(event -> "run.cancelled".equals(event.type()));
    }

    @Test
    void relayStopStillCommitsWhenCancellationBarrierFails() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        seedRunningRun(sessions, messages, runs, user, "run1", "session1", "msg-user");
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return Flux.empty();
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.error(new IllegalStateException("relay stop failed"));
            }
        };
        FinanceEXChatService service = stopService(sessions, messages, runs, events, runtime);

        var result = service.stopRun(user, "run1", RuntimeForwardHeaders.empty()).block();

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(ChatRunStatus.CANCELLED);
        assertThat(events.events).anyMatch(event -> "run.cancelled".equals(event.type()));
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

        FinanceEXChatService service = ChatFlowTestFixture.service(
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
        assertThat(savedInteraction.runtimeSessionId()).isEqualTo(binding.runtimeSessionId());
        assertThat(savedInteraction.approvalId()).isEqualTo("approval-1");
        assertThat(savedInteraction.assistantMessageId()).isEqualTo("msg-assistant");
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
}
