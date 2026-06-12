package com.huawei.finance.front.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.command.DocumentUpdateCommand;
import com.huawei.finance.front.one.application.command.DocumentUploadCommand;
import com.huawei.finance.front.one.application.config.MemoryProperties;
import com.huawei.finance.front.one.application.config.RouteSignalProperties;
import com.huawei.finance.front.one.application.facade.DocumentFacade;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntime;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.agent.LegacySkillAgentClient;
import com.huawei.finance.front.one.application.integration.agent.LegacySkillAgentRequest;
import com.huawei.finance.front.one.application.integration.agent.LegacySkillCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.application.integration.conversation.ChatEventStore;
import com.huawei.finance.front.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunCache;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.finance.front.one.application.integration.memory.ChatMessageRepository;
import com.huawei.finance.front.one.application.integration.memory.LongTermMemoryStore;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingCache;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.finance.front.one.application.service.memory.MemoryApplicationService;
import com.huawei.finance.front.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.finance.front.one.application.service.routing.RouteSignalResult;
import com.huawei.finance.front.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.finance.front.one.application.service.runtime.LegacySkillExecutor;
import com.huawei.finance.front.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.finance.front.one.application.service.runtime.SubAgentExecutor;
import com.huawei.finance.front.one.application.service.runtime.SystemResponseExecutor;
import com.huawei.finance.front.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePart;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunCancelSignal;
import com.huawei.finance.front.one.domain.chat.ChatRunExecution;
import com.huawei.finance.front.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.finance.front.one.domain.chat.ChatRunMode;
import com.huawei.finance.front.one.domain.chat.ChatRunStatus;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.domain.chat.MessageSnapshotEvent;
import com.huawei.finance.front.one.domain.chat.RuntimeEvent;
import com.huawei.finance.front.one.domain.chat.StoredChatEvent;
import com.huawei.finance.front.one.domain.document.DocumentLibraryPage;
import com.huawei.finance.front.one.domain.document.DocumentLibraryQuery;
import com.huawei.finance.front.one.domain.document.StoredObjectContent;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import com.huawei.finance.front.one.domain.memory.LongTermMemoryItem;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import com.huawei.finance.front.one.domain.usecase.UseCaseMatchResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
class FinanceEXChatServiceTest {
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
                new SubAgentExecutor(new com.huawei.finance.front.one.application.integration.agent.SubAgentClient() {
                    @Override public Flux<ChatEvent> query(com.huawei.finance.front.one.domain.agent.AgentQueryRequest request) {
                        return Flux.empty();
                    }
                    @Override public Mono<Void> cancel(com.huawei.finance.front.one.domain.agent.SubAgentCancelRequest request) {
                        return Mono.empty();
                    }
                }, limiter),
                legacySkillExecutor(documentFacade(), limiter),
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
                .expectNextMatches(event -> "run.completed".equals(event.type()))
                .verifyComplete();

        ChatMessage assistant = messages.messages.stream()
                .filter(message -> "assistant".equals(message.role()))
                .findFirst()
                .orElseThrow();
        assertThat(assistant.content()).isEqualTo("最终\nMarkdown **正文**");
        assertThat(assistant.parts()).extracting(ChatMessagePart::partType)
                .containsExactly("TOOL", "ANSWER");
        assertThat(assistant.parts()).extracting(ChatMessagePart::contentText)
                .containsExactly("search: 查询报销流程", "最终\nMarkdown **正文**");
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
                        Map.of("sourceType", "legacy-card",
                                "cardUrl", "https://cards.example/render/1",
                                "intent", "CreditSales",
                                "skillId", "skill-card")));
            }
            @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
        };

        FinanceEXChatService service = new FinanceEXChatService(
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                runtimeRouteService(),
                new SubAgentExecutor(new com.huawei.finance.front.one.application.integration.agent.SubAgentClient() {
                    @Override public Flux<ChatEvent> query(com.huawei.finance.front.one.domain.agent.AgentQueryRequest request) {
                        return Flux.empty();
                    }
                    @Override public Mono<Void> cancel(com.huawei.finance.front.one.domain.agent.SubAgentCancelRequest request) {
                        return Mono.empty();
                    }
                }, limiter),
                legacySkillExecutor(documentFacade(), limiter),
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
                .expectNextMatches(event -> "run.completed".equals(event.type()))
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
    void selectedSkillIdRoutesToLegacySkillWithoutRuntimeBinding() {
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
        LegacySkillExecutor legacySkillExecutor = new LegacySkillExecutor(new LegacySkillAgentClient() {
            @Override public Flux<ChatEvent> query(LegacySkillAgentRequest request) {
                capturedHeaders.set(request.forwardHeaders());
                return Flux.just(MessageDeltaEvent.of(request.runId(), request.sessionId(), "legacy answer"));
            }
            @Override public Mono<Void> cancel(LegacySkillCancelRequest request) { return Mono.empty(); }
        }, documents, limiter);

        FinanceEXChatService service = new FinanceEXChatService(
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                runtimeRouteService(),
                new SubAgentExecutor(new com.huawei.finance.front.one.application.integration.agent.SubAgentClient() {
                    @Override public Flux<ChatEvent> query(com.huawei.finance.front.one.domain.agent.AgentQueryRequest request) {
                        return Flux.empty();
                    }
                    @Override public Mono<Void> cancel(com.huawei.finance.front.one.domain.agent.SubAgentCancelRequest request) {
                        return Mono.empty();
                    }
                }, limiter),
                legacySkillExecutor,
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
                        null, null, "web", "hello", List.of(), Map.of("selectedSkillId", "skill-tax")),
                        RuntimeForwardHeaders.fromCookieHeader("sid=abc", 8192)))
                .expectNextMatches(event -> "run.started".equals(event.type()))
                .expectNextMatches(event -> "message.delta".equals(event.type()))
                .expectNextMatches(event -> "run.completed".equals(event.type()))
                .verifyComplete();

        assertThat(capturedHeaders.get()).isNotNull();
        assertThat(capturedHeaders.get().cookieHeader()).isEqualTo("sid=abc");
        ChatRun run = runs.runs.values().iterator().next();
        assertThat(run.routeType()).isEqualTo("EXPLICIT_SKILL");
        assertThat(run.agentCode()).isEqualTo("skill-tax");
        assertThat(run.runtimeProvider()).isNull();
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
                new SubAgentExecutor(new com.huawei.finance.front.one.application.integration.agent.SubAgentClient() {
                    @Override public Flux<ChatEvent> query(com.huawei.finance.front.one.domain.agent.AgentQueryRequest request) {
                        return Flux.empty();
                    }
                    @Override public Mono<Void> cancel(com.huawei.finance.front.one.domain.agent.SubAgentCancelRequest request) {
                        return Mono.empty();
                    }
                }, limiter),
                legacySkillExecutor(documentFacade(), limiter),
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
                new SubAgentExecutor(new com.huawei.finance.front.one.application.integration.agent.SubAgentClient() {
                    @Override public Flux<ChatEvent> query(com.huawei.finance.front.one.domain.agent.AgentQueryRequest request) {
                        return Flux.empty();
                    }
                    @Override public Mono<Void> cancel(com.huawei.finance.front.one.domain.agent.SubAgentCancelRequest request) {
                        return Mono.empty();
                    }
                }, limiter),
                legacySkillExecutor(documentFacade(), limiter),
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

        assertThat(service.stopRun(user, "run1", RuntimeForwardHeaders.empty()).block().status())
                .isEqualTo(ChatRunStatus.CANCELLED);

        ChatMessage assistant = messages.messages.stream()
                .filter(message -> "assistant".equals(message.role()))
                .findFirst()
                .orElseThrow();
        assertThat(assistant.content()).isEqualTo("已输出的部分回答");
        assertThat(assistant.metadataJson()).contains("\"partial\":true").contains("USER_STOP");
        assertThat(assistant.parts()).extracting(ChatMessagePart::partType)
                .containsExactly("TOOL", "ANSWER");
        assertThat(runs.findById("run1").orElseThrow().assistantMessageId()).isEqualTo(assistant.id());
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

        assertThat(service.stopRun(user, "run1", RuntimeForwardHeaders.empty()).block().status())
                .isEqualTo(ChatRunStatus.CANCELLED);
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

        assertThat(service.stopRun(user, "run1", RuntimeForwardHeaders.empty()).block().status())
                .isEqualTo(ChatRunStatus.CANCELLED);
        assertThat(messages.messages).extracting(ChatMessage::role).containsExactly("user");
        assertThat(runs.findById("run1").orElseThrow().assistantMessageId()).isNull();
    }

    private RouteSignalApplicationService systemRouteService() {
        return new RouteSignalApplicationService(request -> UseCaseMatchResult.notMatched("disabled"),
                (command, memory, user) -> null, new com.huawei.finance.front.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public RouteSignalResult routeInitial(UserContext user, ChatSession session, ChatCommand command,
                                                  List<AttachmentRef> attachments,
                                                  com.huawei.finance.front.one.domain.memory.MemoryContext memory) {
                return RouteSignalResult.of(RouteTarget.systemResponse("partial answer"));
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
        return new FinanceEXChatService(
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                runtimeRouteService(),
                new SubAgentExecutor(new com.huawei.finance.front.one.application.integration.agent.SubAgentClient() {
                    @Override public Flux<ChatEvent> query(com.huawei.finance.front.one.domain.agent.AgentQueryRequest request) {
                        return Flux.empty();
                    }
                    @Override public Mono<Void> cancel(com.huawei.finance.front.one.domain.agent.SubAgentCancelRequest request) {
                        return Mono.empty();
                    }
                }, limiter),
                legacySkillExecutor(documentFacade(), limiter),
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(noopRuntime(), limiter),
                documentFacade(),
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, new NeverCancelRunCache(), events,
                        permissionChecker, sessions),
                new ChatRunLeaseApplicationService(
                        new InMemoryExecutionRepository(),
                        (ApplicationInstanceIdProvider) () -> "instance-test",
                        new com.huawei.finance.front.one.application.config.ChatRunOperationalProperties(),
                        ids,
                        executionRegistry
                ),
                new ChatDeltaCoalescer(new com.huawei.finance.front.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.finance.front.one.application.config.RunAdmissionProperties()),
                ids
        );
    }

    private void seedRunningRun(InMemorySessionRepository sessions, InMemoryMessageRepository messages,
                                InMemoryRunRepository runs, UserContext user,
                                String runId, String sessionId, String userMessageId) {
        Instant now = Instant.parse("2026-06-11T00:00:00Z");
        sessions.save(new ChatSession(sessionId, user.tenantId(), user.userId(), "测试会话", "ACTIVE", "web",
                userMessageId, sessionId, null, null, 1L, null, now, now));
        messages.save(new ChatMessage(userMessageId, user.tenantId(), user.userId(), sessionId,
                null, 1L, 0, 1, "user", "帮我处理一下", null, runId,
                "NORMAL", false, null, null, null, null, null, now));
        runs.save(new ChatRun(runId, user.tenantId(), user.userId(), sessionId, ChatRunStatus.RUNNING,
                "AGENT_RUNTIME", null, "relay", null, ChatRunMode.NEXT, null, userMessageId,
                null, null, null, null, now, null, Map.of(), now, now));
    }

    private RouteSignalApplicationService runtimeRouteService() {
        return new RouteSignalApplicationService(request -> UseCaseMatchResult.notMatched("disabled"),
                (command, memory, user) -> null, new com.huawei.finance.front.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public RouteSignalResult routeInitial(UserContext user, ChatSession session, ChatCommand command,
                                                  List<AttachmentRef> attachments,
                                                  com.huawei.finance.front.one.domain.memory.MemoryContext memory) {
                return RouteSignalResult.of(RouteTarget.agentRuntime("test-runtime"));
            }
        };
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

    private LegacySkillExecutor legacySkillExecutor(DocumentFacade documentFacade, WorkloadConcurrencyLimiter limiter) {
        return new LegacySkillExecutor(new LegacySkillAgentClient() {
            @Override public Flux<ChatEvent> query(LegacySkillAgentRequest request) { return Flux.empty(); }
            @Override public Mono<Void> cancel(LegacySkillCancelRequest request) { return Mono.empty(); }
        }, documentFacade, limiter);
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

    private static class InMemoryRunRepository implements ChatRunRepository {
        private final Map<String, ChatRun> runs = new HashMap<>();
        @Override public ChatRun save(ChatRun run) { runs.put(run.id(), run); return run; }
        @Override public Optional<ChatRun> findById(String runId) { return Optional.ofNullable(runs.get(runId)); }
        @Override public Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId) {
            return findById(runId).filter(run -> tenantId.equals(run.tenantId())).filter(run -> userId.equals(run.userId()));
        }
        @Override public Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId) { return Optional.empty(); }
    }

    private static class InMemoryEventStore implements ChatEventStore {
        private long seq;
        private final List<ChatEvent> events = new ArrayList<>();
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

    private static class InMemoryExecutionRepository implements ChatRunExecutionRepository {
        private final Map<String, ChatRunExecution> executions = new HashMap<>();

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
        @Override public boolean heartbeat(String runId, String ownerInstanceId, Duration leaseDuration) { return true; }
        @Override public boolean markTerminal(String runId, ChatRunExecutionStatus terminalStatus) { return true; }
        @Override public List<ChatRunExecution> findLeaseExpired(int limit) { return List.of(); }
        @Override public List<ChatRunExecution> findRecoveryExpired(int limit) { return List.of(); }
        @Override public Optional<ChatRunExecution> tryClaimRecovering(String runId, String recoveredByInstanceId, String strategy, Duration recoveryLeaseDuration) { return Optional.empty(); }
        @Override public Optional<ChatRunExecution> markTakeoverRunning(String runId, String ownerInstanceId, Duration leaseDuration) { return Optional.empty(); }
        @Override public boolean isLeaseExpired(String runId, Instant now) { return false; }
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

    private static class FixedIdGenerator implements IdGenerator {
        @Override public String newId(String prefix, IdGenerateContext context) { return prefix + "_1"; }
    }
}
