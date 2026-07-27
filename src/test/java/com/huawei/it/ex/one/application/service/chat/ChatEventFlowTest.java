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
class ChatEventFlowTest extends ChatFlowTestSupport {
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

        FinanceEXChatService service = ChatFlowTestFixture.service(
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

        FinanceEXChatService service = ChatFlowTestFixture.service(
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

        FinanceEXChatService service = ChatFlowTestFixture.service(
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
}
