package com.huawei.finance.front.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.front.one.application.integration.conversation.ChatEventStore;
import com.huawei.finance.front.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.config.ChatStreamProperties;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunStatus;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import com.huawei.finance.front.one.domain.chat.ChatStreamTopics;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.domain.chat.RunCancelledEvent;
import com.huawei.finance.front.one.domain.chat.StoredChatEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
class ChatStreamApplicationServiceTest {
    @Test
    void publishPersistedStillAttemptsLiveBusWhenLocalPublishThrows() {
        AtomicInteger liveCalls = new AtomicInteger();
        LocalChatEventStreamRegistry failingRegistry = new LocalChatEventStreamRegistry() {
            @Override
            public void publish(ChatEvent event) {
                throw new IllegalStateException("local publish down");
            }
        };
        ChatLiveEventBus liveBus = new ChatLiveEventBus() {
            @Override
            public void publish(String topicId, ChatEvent event) {
                liveCalls.incrementAndGet();
            }

            @Override
            public Flux<ChatEvent> subscribe(String topicId) {
                return Flux.never();
            }
        };
        ChatStreamApplicationService service = service(failingRegistry, liveBus);

        assertThatThrownBy(() -> service.publishPersisted(stored(1L, "hello")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("本机聊天事件发布失败");
        assertThat(liveCalls).hasValue(1);
    }

    @Test
    void publishPersistedAttemptsLocalBeforePropagatingLiveBusFailure() {
        AtomicInteger localCalls = new AtomicInteger();
        LocalChatEventStreamRegistry registry = new LocalChatEventStreamRegistry() {
            @Override
            public void publish(ChatEvent event) {
                localCalls.incrementAndGet();
            }
        };
        ChatLiveEventBus failingLiveBus = new ChatLiveEventBus() {
            @Override
            public void publish(String topicId, ChatEvent event) {
                throw new IllegalStateException("live publish down");
            }

            @Override
            public Flux<ChatEvent> subscribe(String topicId) {
                return Flux.never();
            }
        };
        ChatStreamApplicationService service = service(registry, failingLiveBus);

        assertThatThrownBy(() -> service.publishPersisted(stored(1L, "hello")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("跨实例聊天事件发布失败");
        assertThat(localCalls).hasValue(1);
    }

    @Test
    void appendAssignsSeqAndResumeSessionReplaysOnlyPersistedEvents() {
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        LocalChatEventStreamRegistry registry = new LocalChatEventStreamRegistry();
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                store,
                registry,
                new InMemoryLiveEventBus(),
                new InMemoryRunRepository(),
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
    void defaultLiveSourceUsesRedisOnlyAndIgnoresLocalSink() {
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        InMemoryLiveEventBus liveEventBus = new InMemoryLiveEventBus();
        InMemoryRunRepository runRepository = new InMemoryRunRepository();
        LocalChatEventStreamRegistry registry = new LocalChatEventStreamRegistry();
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                store,
                registry,
                liveEventBus,
                runRepository,
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()
        );
        runRepository.save(runningRun("run1", "tenant1", "user1"));

        StepVerifier.create(service.resumeRunTopic(user(), ChatStreamTopics.runTopic("run1"), 0).take(1))
                .thenAwait(Duration.ofMillis(50))
                .then(() -> registry.publish(stored(1L, "local")))
                .expectNoEvent(Duration.ofMillis(100))
                .then(() -> liveEventBus.publish(ChatStreamTopics.runTopic("run1"), stored(2L, "redis")))
                .assertNext(event -> {
                    assertThat(event.sequence()).isEqualTo(2L);
                    assertThat(event.payload()).containsEntry("delta", "redis");
                })
                .verifyComplete();
    }

    @Test
    void localOnlyLiveSourceIgnoresRedisBus() {
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        InMemoryLiveEventBus liveEventBus = new InMemoryLiveEventBus();
        InMemoryRunRepository runRepository = new InMemoryRunRepository();
        LocalChatEventStreamRegistry registry = new LocalChatEventStreamRegistry();
        ChatStreamProperties streamProperties = new ChatStreamProperties();
        streamProperties.setLiveSourceMode(ChatStreamProperties.LiveSourceMode.LOCAL_ONLY);
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                store,
                registry,
                liveEventBus,
                runRepository,
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties(),
                streamProperties
        );
        runRepository.save(runningRun("run1", "tenant1", "user1"));

        StepVerifier.create(service.resumeRunTopic(user(), ChatStreamTopics.runTopic("run1"), 0).take(1))
                .thenAwait(Duration.ofMillis(50))
                .then(() -> liveEventBus.publish(ChatStreamTopics.runTopic("run1"), stored(1L, "redis")))
                .expectNoEvent(Duration.ofMillis(100))
                .then(() -> registry.publish(stored(2L, "local")))
                .assertNext(event -> {
                    assertThat(event.sequence()).isEqualTo(2L);
                    assertThat(event.payload()).containsEntry("delta", "local");
                })
                .verifyComplete();
    }

    @Test
    void mergeLiveSourceConsumesLocalAndRedisBus() {
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        InMemoryLiveEventBus liveEventBus = new InMemoryLiveEventBus();
        InMemoryRunRepository runRepository = new InMemoryRunRepository();
        LocalChatEventStreamRegistry registry = new LocalChatEventStreamRegistry();
        ChatStreamProperties streamProperties = new ChatStreamProperties();
        streamProperties.setLiveSourceMode(ChatStreamProperties.LiveSourceMode.MERGE);
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                store,
                registry,
                liveEventBus,
                runRepository,
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties(),
                streamProperties
        );
        runRepository.save(runningRun("run1", "tenant1", "user1"));

        StepVerifier.create(service.resumeRunTopic(user(), ChatStreamTopics.runTopic("run1"), 0)
                        .take(2)
                        .map(event -> String.valueOf(event.payload().get("delta")))
                        .collectList())
                .thenAwait(Duration.ofMillis(50))
                .then(() -> {
                    registry.publish(stored(1L, "local"));
                    liveEventBus.publish(ChatStreamTopics.runTopic("run1"), stored(2L, "redis"));
                })
                .assertNext(deltas -> assertThat(deltas).containsExactlyInAnyOrder("local", "redis"))
                .verifyComplete();
    }

    @Test
    void liveReorderSortsShortWindowEventsBySeq() {
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        InMemoryLiveEventBus liveEventBus = new InMemoryLiveEventBus();
        InMemoryRunRepository runRepository = new InMemoryRunRepository();
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                store,
                new LocalChatEventStreamRegistry(),
                liveEventBus,
                runRepository,
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties(),
                streamProperties(true, Duration.ofMillis(20), 128)
        );
        runRepository.save(runningRun("run1", "tenant1", "user1"));

        StepVerifier.create(service.resumeRunTopic(user(), ChatStreamTopics.runTopic("run1"), 0)
                        .take(3)
                        .map(ChatEvent::sequence)
                        .collectList())
                .thenAwait(Duration.ofMillis(50))
                .then(() -> {
                    liveEventBus.publish(ChatStreamTopics.runTopic("run1"), stored(414848L, "a"));
                    liveEventBus.publish(ChatStreamTopics.runTopic("run1"), stored(414850L, "c"));
                    liveEventBus.publish(ChatStreamTopics.runTopic("run1"), stored(414849L, "b"));
                })
                .assertNext(sequences -> assertThat(sequences).containsExactly(414848L, 414849L, 414850L))
                .verifyComplete();
    }

    @Test
    void liveReorderDoesNotWaitForNonexistentContinuousSeq() {
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        InMemoryLiveEventBus liveEventBus = new InMemoryLiveEventBus();
        InMemoryRunRepository runRepository = new InMemoryRunRepository();
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                store,
                new LocalChatEventStreamRegistry(),
                liveEventBus,
                runRepository,
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties(),
                streamProperties(true, Duration.ofMillis(20), 128)
        );
        runRepository.save(runningRun("run1", "tenant1", "user1"));

        StepVerifier.create(service.resumeRunTopic(user(), ChatStreamTopics.runTopic("run1"), 0)
                        .take(2)
                        .map(ChatEvent::sequence)
                        .collectList())
                .thenAwait(Duration.ofMillis(50))
                .then(() -> {
                    liveEventBus.publish(ChatStreamTopics.runTopic("run1"), stored(414848L, "a"));
                    liveEventBus.publish(ChatStreamTopics.runTopic("run1"), stored(414850L, "c"));
                })
                .assertNext(sequences -> assertThat(sequences).containsExactly(414848L, 414850L))
                .verifyComplete();
    }

    @Test
    void liveReorderFlushesWhenMaxEventsReached() {
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        InMemoryLiveEventBus liveEventBus = new InMemoryLiveEventBus();
        InMemoryRunRepository runRepository = new InMemoryRunRepository();
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                store,
                new LocalChatEventStreamRegistry(),
                liveEventBus,
                runRepository,
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties(),
                streamProperties(true, Duration.ofSeconds(5), 2)
        );
        runRepository.save(runningRun("run1", "tenant1", "user1"));

        StepVerifier.create(service.resumeRunTopic(user(), ChatStreamTopics.runTopic("run1"), 0)
                        .take(2)
                        .map(ChatEvent::sequence)
                        .collectList())
                .thenAwait(Duration.ofMillis(50))
                .then(() -> {
                    liveEventBus.publish(ChatStreamTopics.runTopic("run1"), stored(414850L, "c"));
                    liveEventBus.publish(ChatStreamTopics.runTopic("run1"), stored(414849L, "b"));
                })
                .assertNext(sequences -> assertThat(sequences).containsExactly(414849L, 414850L))
                .verifyComplete();
    }

    @Test
    void liveReorderCanBeDisabled() {
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        InMemoryLiveEventBus liveEventBus = new InMemoryLiveEventBus();
        InMemoryRunRepository runRepository = new InMemoryRunRepository();
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                store,
                new LocalChatEventStreamRegistry(),
                liveEventBus,
                runRepository,
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties(),
                streamProperties(false, Duration.ofMillis(20), 128)
        );
        runRepository.save(runningRun("run1", "tenant1", "user1"));

        StepVerifier.create(service.resumeRunTopic(user(), ChatStreamTopics.runTopic("run1"), 0)
                        .take(2)
                        .map(ChatEvent::sequence)
                        .collectList())
                .thenAwait(Duration.ofMillis(50))
                .then(() -> {
                    liveEventBus.publish(ChatStreamTopics.runTopic("run1"), stored(414850L, "c"));
                    liveEventBus.publish(ChatStreamTopics.runTopic("run1"), stored(414849L, "b"));
                })
                .assertNext(sequences -> assertThat(sequences).containsExactly(414850L, 414849L))
                .verifyComplete();
    }

    @Test
    void resumeRunTopicReceivesLocalRunCancelledTerminalEvent() {
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        InMemoryRunRepository runRepository = new InMemoryRunRepository();
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                store,
                new LocalChatEventStreamRegistry(),
                new InMemoryLiveEventBus(),
                runRepository,
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties()
        );
        runRepository.save(runningRun("run1", "tenant1", "user1"));

        StepVerifier.create(service.resumeRunTopic(user(), ChatStreamTopics.runTopic("run1"), 0).take(1))
                .then(() -> service.appendAndPublish(RunCancelledEvent.of("run1", "session1", "USER_STOP")))
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo("run.cancelled");
                    assertThat(event.payload()).containsEntry("status", "CANCELLED");
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
    void resumeRunStopsLiveTailWhenRecoveryRequired() {
        InMemoryChatEventStore store = new InMemoryChatEventStore();
        InMemoryRunRepository runRepository = new InMemoryRunRepository();
        ChatStreamApplicationService service = new ChatStreamApplicationService(
                store,
                new LocalChatEventStreamRegistry(),
                new FailingLiveEventBus(),
                runRepository,
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties(),
                new ChatStreamProperties()
        );
        runRepository.save(runningRun("run1", "tenant1", "user1"));
        ChatEvent first = service.appendAndPublish(MessageDeltaEvent.of("run1", "session1", "hello"));

        StepVerifier.create(service.resumeRun(user(), "run1", first.sequence()))
                .verifyComplete();
    }

    private static class InMemoryChatEventStore implements ChatEventStore {
        private final List<ChatEvent> events = new ArrayList<>();
        private long seq;

        @Override
        public synchronized ChatEvent append(ChatEvent event) {
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
        public synchronized ChatEvent appendWithExecutionGuard(ChatEvent event, com.huawei.finance.front.one.domain.chat.RunExecutionClaim claim) {
            return append(event);
        }

        @Override
        public synchronized List<ChatEvent> findByOwnerAndSessionAfterSeq(String tenantId, String userId, String sessionId, long afterSeq) {
            return events.stream()
                    .filter(event -> sessionId.equals(event.sessionId()))
                    .filter(event -> event.sequence() > afterSeq)
                    .toList();
        }

        @Override
        public synchronized List<ChatEvent> findByOwnerAndRunAfterSeq(String tenantId, String userId, String sessionId,
                                                                      String runId, long afterSeq) {
            return events.stream()
                    .filter(event -> runId.equals(event.runId()))
                    .filter(event -> sessionId.equals(event.sessionId()))
                    .filter(event -> event.sequence() > afterSeq)
                    .toList();
        }

        @Override
        public synchronized long findLatestSeqByOwnerAndSession(String tenantId, String userId, String sessionId) {
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

    private ChatStreamApplicationService service(LocalChatEventStreamRegistry registry,
                                                 ChatLiveEventBus liveEventBus) {
        return new ChatStreamApplicationService(
                new InMemoryChatEventStore(),
                registry,
                liveEventBus,
                new InMemoryRunRepository(),
                new PermissionChecker(),
                new FixedSessionRepository(),
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties());
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private ChatEvent stored(long seq, String delta) {
        return new StoredChatEvent("run1", "session1", seq, "message.delta",
                Instant.now(), Map.of("delta", delta));
    }

    private ChatStreamProperties streamProperties(boolean reorderEnabled, Duration reorderWindow, int maxEvents) {
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setLiveReorderEnabled(reorderEnabled);
        properties.setLiveReorderWindow(reorderWindow);
        properties.setLiveReorderMaxEvents(maxEvents);
        return properties;
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

    private static class FailingLiveEventBus implements ChatLiveEventBus {
        @Override
        public void publish(String topicId, ChatEvent event) {
            // no-op: this fake only models a remote live source failure during run Event Resume.
        }

        @Override
        public reactor.core.publisher.Flux<ChatEvent> subscribe(String topicId) {
            return reactor.core.publisher.Flux.error(new IllegalStateException("redis live sink emit failed"));
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
