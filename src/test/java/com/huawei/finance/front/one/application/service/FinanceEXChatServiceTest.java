package com.huawei.finance.front.one.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.config.MemoryProperties;
import com.huawei.finance.front.one.application.config.RouteSignalProperties;
import com.huawei.finance.front.one.application.facade.DocumentFacade;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntime;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.conversation.ChatEventStore;
import com.huawei.finance.front.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.finance.front.one.application.integration.conversation.ChatReadCursorCache;
import com.huawei.finance.front.one.application.integration.conversation.ChatReadCursorRepository;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunCache;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.memory.ChatMessageRepository;
import com.huawei.finance.front.one.application.integration.memory.LongTermMemoryStore;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingCache;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.finance.front.one.application.command.DocumentUpdateCommand;
import com.huawei.finance.front.one.application.command.DocumentUploadCommand;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import com.huawei.finance.front.one.domain.chat.ChatReadCursor;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunCancelSignal;
import com.huawei.finance.front.one.domain.chat.ChatRunExecution;
import com.huawei.finance.front.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
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
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FinanceEXChatServiceTest {
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
        ChatReadCursorApplicationService readCursorService = readCursorService(sessions);
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
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(mismatchedRuntime, limiter),
                documentFacade(),
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        readCursorService, permissionChecker, sessions,
                        new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, runCache, events, readCursorService, permissionChecker, sessions),
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
        ChatReadCursorApplicationService readCursorService = readCursorService(sessions);
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
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(noopRuntime(), limiter),
                documentFacade(),
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        readCursorService, permissionChecker, sessions,
                        new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, runCache, events, readCursorService, permissionChecker, sessions),
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
            @Override public Mono<StoredObjectContent> download(UserContext user, String documentId) { return Mono.empty(); }
            @Override public List<AttachmentRef> resolveAttachmentsForUser(UserContext user, List<AttachmentRef> attachments) { return List.of(); }
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

    private ChatReadCursorApplicationService readCursorService(SessionRepository sessions) {
        com.huawei.finance.front.one.application.config.ChatReadCursorProperties properties =
                new com.huawei.finance.front.one.application.config.ChatReadCursorProperties();
        properties.setDatabaseFlushInterval(Duration.ZERO);
        return new ChatReadCursorApplicationService(
                new EmptyReadCursorRepository(),
                new EmptyReadCursorCache(),
                new PermissionChecker(),
                sessions,
                properties
        );
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
        @Override public List<ChatEvent> findByOwnerAndSessionAfterSeq(String tenantId, String userId, String sessionId, long afterSeq) { return List.of(); }
        @Override public List<ChatEvent> findByOwnerAndRunAfterSeq(String tenantId, String userId, String sessionId, String runId, long afterSeq) { return List.of(); }
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

    private static class EmptyReadCursorRepository implements ChatReadCursorRepository {
        @Override public Optional<ChatReadCursor> find(String tenantId, String userId, String sessionId) {
            return Optional.empty();
        }
        @Override public ChatReadCursor upsert(String tenantId, String userId, String sessionId, long lastConsumedSeq) {
            return new ChatReadCursor("cursor1", tenantId, userId, sessionId, lastConsumedSeq, Instant.now());
        }
    }

    private static class EmptyReadCursorCache implements ChatReadCursorCache {
        @Override public Optional<ChatReadCursor> find(String tenantId, String userId, String sessionId) {
            return Optional.empty();
        }
        @Override public void put(ChatReadCursor cursor) {}
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
        @Override public ChatMessage save(ChatMessage message) { messages.add(message); return message; }
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
