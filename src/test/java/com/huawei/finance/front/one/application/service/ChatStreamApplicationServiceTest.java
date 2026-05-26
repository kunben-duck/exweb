package com.huawei.finance.front.one.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.integration.conversation.ChatEventStore;
import com.huawei.finance.front.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.finance.front.one.application.integration.conversation.ChatReadCursorCache;
import com.huawei.finance.front.one.application.integration.conversation.ChatReadCursorRepository;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatReadCursor;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunStatus;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import com.huawei.finance.front.one.domain.chat.ChatStreamTopics;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.domain.chat.StoredChatEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ChatStreamApplicationServiceTest {
    @Test
    void appendAssignsSeqAndResumeSessionReplaysOnlyPersistedEvents() {
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        LocalChatEventStreamRegistry registry = new LocalChatEventStreamRegistry();
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                store,
                registry,
                new InMemoryLiveEventBus(),
                new InMemoryRunRepository(),
                readCursorService(),
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()
        );
        ChatEvent first = service.appendAndPublish(MessageDeltaEvent.of("run1", "session1", "hello"));

        service.appendAndPublish(MessageDeltaEvent.of("run1", "session1", " world"));

        StepVerifier.create(service.resumeSession(user(), "session1", first.sequence()))
                .assertNext(event -> {
                    assertThat(event.sequence()).isGreaterThan(first.sequence());
                    assertThat(event.payload()).containsEntry("delta", " world");
                })
                .verifyComplete();
    }

    @Test
    void latestSeqUsesEventStoreFactSource() {
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                store,
                new LocalChatEventStreamRegistry(),
                new InMemoryLiveEventBus(),
                new InMemoryRunRepository(),
                readCursorService(),
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()
        );
        service.appendAndPublish(MessageDeltaEvent.of("run1", "session1", "hello"));

        assertThat(service.latestSeq(user(), "session1")).isEqualTo(1L);
    }

    @Test
    void resumeRunTopicReplaysRunEventsAndReceivesRemoteEvents() {
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        InMemoryLiveEventBus liveEventBus = new InMemoryLiveEventBus();
        InMemoryRunRepository runRepository = new InMemoryRunRepository();
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                store,
                new LocalChatEventStreamRegistry(),
                liveEventBus,
                runRepository,
                readCursorService(),
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()
        );
        runRepository.save(runningRun("run1", "tenant1", "user1"));
        ChatEvent first = service.appendAndPublish(MessageDeltaEvent.of("run1", "session1", "hello"));

        StepVerifier.create(service.resumeRunTopic(new UserContext("tenant1", "user1", "User One"),
                        ChatStreamTopics.runTopic("run1"), 0).take(2))
                .assertNext(event -> assertThat(event.sequence()).isEqualTo(first.sequence()))
                .then(() -> liveEventBus.publish(ChatStreamTopics.runTopic("run1"),
                        new StoredChatEvent("run1", "session1", 2L, "message.delta",
                                Instant.now(), Map.of("delta", " remote"))))
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", " remote"))
                .verifyComplete();
    }

    @Test
    void resumeRunTopicDropsLiveEventsWhoseRunOrSessionDoesNotMatchTopic() {
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        InMemoryLiveEventBus liveEventBus = new InMemoryLiveEventBus();
        InMemoryRunRepository runRepository = new InMemoryRunRepository();
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                store,
                new LocalChatEventStreamRegistry(),
                liveEventBus,
                runRepository,
                readCursorService(),
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()
        );
        runRepository.save(runningRun("run1", "tenant1", "user1"));

        StepVerifier.create(service.resumeRunTopic(user(), ChatStreamTopics.runTopic("run1"), 0).take(1))
                .then(() -> {
                    liveEventBus.publish(ChatStreamTopics.runTopic("run1"),
                            new StoredChatEvent("run2", "session2", 1L, "message.delta",
                                    Instant.now(), Map.of("delta", "wrong-run")));
                    liveEventBus.publish(ChatStreamTopics.runTopic("run1"),
                            new StoredChatEvent("run1", "session2", 2L, "message.delta",
                                    Instant.now(), Map.of("delta", "wrong-session")));
                    liveEventBus.publish(ChatStreamTopics.runTopic("run1"),
                            new StoredChatEvent("run1", "session1", 3L, "message.delta",
                                    Instant.now(), Map.of("delta", "right")));
                })
                .assertNext(event -> {
                    assertThat(event.runId()).isEqualTo("run1");
                    assertThat(event.payload()).containsEntry("delta", "right");
                })
                .verifyComplete();
    }

    @Test
    void resumeRunReplaysOnlyRequestedRunEventsUntilTerminal() {
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        InMemoryRunRepository runRepository = new InMemoryRunRepository();
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                store,
                new LocalChatEventStreamRegistry(),
                new InMemoryLiveEventBus(),
                runRepository,
                readCursorService(),
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()
        );
        runRepository.save(runningRun("run1", "tenant1", "user1"));
        runRepository.save(runningRun("run2", "tenant1", "user1"));
        ChatEvent first = service.appendAndPublish(MessageDeltaEvent.of("run1", "session1", "hello"));
        service.appendAndPublish(MessageDeltaEvent.of("run2", "session1", "other"));
        service.appendAndPublish(MessageDeltaEvent.of("run1", "session1", " world"));
        service.appendAndPublish(new StoredChatEvent("run1", "session1", 0L, "run.completed",
                Instant.now(), Map.of("status", "COMPLETED")));

        StepVerifier.create(service.resumeRun(user(), "run1", first.sequence()))
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", " world"))
                .assertNext(event -> assertThat(event.type()).isEqualTo("run.completed"))
                .verifyComplete();
    }

    @Test
    void resumeRunTailsLiveEventsUntilTerminal() {
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        InMemoryLiveEventBus liveEventBus = new InMemoryLiveEventBus();
        InMemoryRunRepository runRepository = new InMemoryRunRepository();
        LocalChatEventStreamRegistry registry = new LocalChatEventStreamRegistry();
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                store,
                registry,
                liveEventBus,
                runRepository,
                readCursorService(),
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()
        );
        runRepository.save(runningRun("run1", "tenant1", "user1"));
        ChatEvent first = service.appendAndPublish(MessageDeltaEvent.of("run1", "session1", "hello"));

        StepVerifier.create(service.resumeRun(user(), "run1", first.sequence()))
                .then(() -> {
                    service.appendAndPublish(MessageDeltaEvent.of("run1", "session1", " live"));
                    service.appendAndPublish(new StoredChatEvent("run1", "session1", 0L, "run.completed",
                            Instant.now(), Map.of("status", "COMPLETED")));
                })
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", " live"))
                .assertNext(event -> assertThat(event.type()).isEqualTo("run.completed"))
                .verifyComplete();
    }

    @Test
    void acknowledgeRunTopicPersistsReadCursorForRunSession() {
        InMemoryRunRepository runRepository = new InMemoryRunRepository();
        TrackingReadCursorRepository cursorRepository = new TrackingReadCursorRepository();
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                new InMemoryChatEventStore(),
                new LocalChatEventStreamRegistry(),
                new InMemoryLiveEventBus(),
                runRepository,
                readCursorService(cursorRepository),
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()
        );
        runRepository.save(runningRun("run1", "tenant1", "user1"));

        service.acknowledgeRunTopic(user(), ChatStreamTopics.runTopic("run1"), 19L);

        assertThat(cursorRepository.seq).isEqualTo(19L);
    }

    private static class InMemoryChatEventStore implements ChatEventStore {
        private final List<ChatEvent> events = new ArrayList<>();
        private long seq;

        @Override
        public ChatEvent append(ChatEvent event) {
            ChatEvent stored = new StoredChatEvent(
                    event.runId(),
                    event.sessionId(),
                    ++seq,
                    event.type(),
                    Instant.now(),
                    event.payload() == null ? Map.of() : event.payload()
            );
            events.add(stored);
            return stored;
        }

        @Override
        public ChatEvent appendWithExecutionGuard(ChatEvent event, com.huawei.finance.front.one.domain.chat.RunExecutionClaim claim) {
            return append(event);
        }

        @Override
        public List<ChatEvent> findByOwnerAndSessionAfterSeq(String tenantId, String userId, String sessionId, long afterSeq) {
            return events.stream()
                    .filter(event -> sessionId.equals(event.sessionId()))
                    .filter(event -> event.sequence() > afterSeq)
                    .toList();
        }

        @Override
        public List<ChatEvent> findByOwnerAndRunAfterSeq(String tenantId, String userId, String sessionId,
                                                         String runId, long afterSeq) {
            return events.stream()
                    .filter(event -> runId.equals(event.runId()))
                    .filter(event -> sessionId.equals(event.sessionId()))
                    .filter(event -> event.sequence() > afterSeq)
                    .toList();
        }

        @Override
        public long findLatestSeqByOwnerAndSession(String tenantId, String userId, String sessionId) {
            return events.stream()
                    .filter(event -> sessionId.equals(event.sessionId()))
                    .mapToLong(ChatEvent::sequence)
                    .max()
                    .orElse(0L);
        }
    }

    private ChatRun runningRun(String runId, String tenantId, String userId) {
        Instant now = Instant.now();
        return new ChatRun(runId, tenantId, userId, "session1", ChatRunStatus.RUNNING,
                "AGENT_RUNTIME", null, "relay", null, null, null, null,
                now, null, Map.of(), now, now);
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private ChatReadCursorApplicationService readCursorService() {
        return readCursorService(new TrackingReadCursorRepository());
    }

    private ChatReadCursorApplicationService readCursorService(ChatReadCursorRepository repository) {
        com.huawei.finance.front.one.application.config.ChatReadCursorProperties properties =
                new com.huawei.finance.front.one.application.config.ChatReadCursorProperties();
        properties.setDatabaseFlushInterval(Duration.ZERO);
        return new ChatReadCursorApplicationService(
                repository,
                new EmptyReadCursorCache(),
                new PermissionChecker(),
                new FixedSessionRepository(),
                properties
        );
    }

    private static class InMemoryRunRepository implements ChatRunRepository {
        private final Map<String, ChatRun> runs = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public ChatRun save(ChatRun run) {
            runs.put(run.id(), run);
            return run;
        }

        @Override
        public Optional<ChatRun> findById(String runId) {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId) {
            return findById(runId)
                    .filter(run -> tenantId.equals(run.tenantId()))
                    .filter(run -> userId.equals(run.userId()));
        }

        @Override
        public Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId) {
            return runs.values().stream()
                    .filter(run -> tenantId.equals(run.tenantId()))
                    .filter(run -> userId.equals(run.userId()))
                    .filter(run -> sessionId.equals(run.sessionId()))
                    .findFirst();
        }
    }

    private static class InMemoryLiveEventBus implements ChatLiveEventBus {
        private final Map<String, reactor.core.publisher.Sinks.Many<ChatEvent>> sinks = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void publish(String topicId, ChatEvent event) {
            sinks.computeIfAbsent(topicId, ignored -> reactor.core.publisher.Sinks.many().multicast().onBackpressureBuffer())
                    .tryEmitNext(event);
        }

        @Override
        public reactor.core.publisher.Flux<ChatEvent> subscribe(String topicId) {
            return sinks.computeIfAbsent(topicId, ignored -> reactor.core.publisher.Sinks.many().multicast().onBackpressureBuffer())
                    .asFlux();
        }
    }

    private static class TrackingReadCursorRepository implements ChatReadCursorRepository {
        private long seq;

        @Override
        public Optional<ChatReadCursor> find(String tenantId, String userId, String sessionId) {
            return seq <= 0 ? Optional.empty()
                    : Optional.of(new ChatReadCursor("cursor1", tenantId, userId, sessionId, seq, Instant.now()));
        }

        @Override
        public ChatReadCursor upsert(String tenantId, String userId, String sessionId, long lastConsumedSeq) {
            seq = Math.max(seq, lastConsumedSeq);
            return new ChatReadCursor("cursor1", tenantId, userId, sessionId, seq, Instant.now());
        }
    }

    private static class EmptyReadCursorCache implements ChatReadCursorCache {
        @Override
        public Optional<ChatReadCursor> find(String tenantId, String userId, String sessionId) {
            return Optional.empty();
        }

        @Override
        public void put(ChatReadCursor cursor) {
        }
    }

    private static class FixedSessionRepository implements SessionRepository {
        @Override
        public Optional<ChatSession> findById(String sessionId) {
            return Optional.empty();
        }

        @Override
        public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
            Instant now = Instant.now();
            return Optional.of(new ChatSession(sessionId, tenantId, userId, "title", "ACTIVE", "web", now, now));
        }

        @Override
        public List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId) {
            return List.of();
        }

        @Override
        public ChatSessionPage pageByTenantIdAndUserId(String tenantId, String userId, String cursor, int limit) {
            return new ChatSessionPage(List.of(), null);
        }

        @Override
        public ChatSession save(ChatSession session) {
            return session;
        }
    }
}
