/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.huawei.it.ex.one.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventStore;
import com.huawei.it.ex.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.it.ex.one.application.integration.conversation.ChatLiveRecoveryRequiredException;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.service.chat.ChatStreamApplicationService;
import com.huawei.it.ex.one.application.service.chat.LocalChatEventStreamRegistry;
import com.huawei.it.ex.one.application.service.chat.StreamRecoveryRequiredException;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.util.AbstractSubscription;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** 实际 Spring 容器和 AbstractSubscription，仅替换网络连接并控制 ACK/退订时序。 */
class RedisChatLiveLifecycleTest {
    @Test
    void listenerCapacityBindsAndRejectsNonPositiveValues() {
        assertThat(new ChatLiveEventBusProperties().getRedisLiveMaxListeners()).isEqualTo(4096);
        ChatLiveEventBusProperties bound = new Binder(new MapConfigurationPropertySource(
                Map.of("financeex.websocket.redis-live-max-listeners", "7")))
                .bind("financeex.websocket", ChatLiveEventBusProperties.class).get();
        assertThat(bound.getRedisLiveMaxListeners()).isEqualTo(7);
        assertThatThrownBy(() -> bound.setRedisLiveMaxListeners(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource(
                Map.of("financeex.websocket.redis-live-max-listeners", "-1")))
                .bind("financeex.websocket", ChatLiveEventBusProperties.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capacityIncludesFailedCleanupAndOnlyRejectsNewListeners() throws Exception {
        try (Fixture f = new Fixture(3)) {
            Handle active = f.openReady("active");
            f.unsubscribeFailures.set(100);
            f.openReady("old-a").dispose();
            f.openReady("old-b").dispose();
            f.awaitIdle();
            assertThat(f.failedCleanup()).hasSize(2);
            assertThat(f.availableListeners()).isZero();
            for (int i = 0; i < 40; i++) {
                Handle excess = f.open("excess-" + i);
                assertThatThrownBy(() -> excess.ready.get(1, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(ChatLiveRecoveryRequiredException.class);
            }
            assertThat(f.retainedListeners()).hasSize(3);
            assertThat(f.subscribedChannels).hasSize(3);
            Handle shared = f.openReady("active");
            f.deliver("active", 1);
            assertThat(active.events).hasSize(1);
            assertThat(shared.events).hasSize(1);
            f.unsubscribeFailures.set(0);
            f.advance(Duration.ofSeconds(1));
            f.openReady("active").dispose();
            f.awaitIdle();
            assertThat(f.availableListeners()).isEqualTo(2);
            f.openReady("new");
            assertThat(f.availableListeners()).isEqualTo(1);
        }
    }

    @Test
    void exhaustedCapacityStillAllowsHttpAndWebSocketHistoryReplay() throws Exception {
        try (Fixture f = new Fixture(1)) {
            Handle active = f.openReady("active");
            UserContext user = new UserContext("tenant", "owner", "Owner");
            Instant now = Instant.now();
            ChatRun run = new ChatRun("blocked", "tenant", "owner", "session", ChatRunStatus.RUNNING,
                    "AGENT_RUNTIME", null, "relay", null, null, null, null,
                    now, null, Map.of(), now, now);
            ChatEvent event = new StoredChatEvent("blocked", "session", 1, "message.delta", now,
                    Map.of("delta", "saved"));
            ChatEventStore store = mock(ChatEventStore.class);
            when(store.findByOwnerAndRunAfterSeq("tenant", "owner", "session", "blocked", 0))
                    .thenReturn(List.of(event));
            ChatRunRepository runs = mock(ChatRunRepository.class);
            when(runs.findByTenantIdAndUserIdAndId("tenant", "owner", "blocked"))
                    .thenReturn(Optional.of(run));
            SessionRepository sessions = mock(SessionRepository.class);
            when(sessions.findByTenantIdAndUserIdAndId("tenant", "owner", "session"))
                    .thenReturn(Optional.of(mock(ChatSession.class)));
            ChatStreamApplicationService streams = new ChatStreamApplicationService(store,
                    new LocalChatEventStreamRegistry(), f.bus, runs, new PermissionChecker(), sessions,
                    new ChatWebSocketProperties());
            StepVerifier.create(streams.resumeRun(user, "blocked", 0)).expectNext(event)
                    .expectComplete().verify(Duration.ofSeconds(2));
            StepVerifier.create(streams.resumeRunTopic(user, "chat-run-blocked", 0)).expectNext(event)
                    .expectError(StreamRecoveryRequiredException.class).verify(Duration.ofSeconds(2));
            verify(store, times(2)).findByOwnerAndRunAfterSeq("tenant", "owner", "session", "blocked", 0);
            assertThat(f.retainedListeners()).hasSize(1);
            assertThat(f.subscribedChannels).containsExactly(f.channel("active"));
            f.deliver("active", 2);
            assertThat(active.events).hasSize(1);
        }
    }

    @Test
    void cancelledRegistrationKeepsItsSlotUntilContainerCleanup() throws Exception {
        try (Fixture f = new Fixture(1)) {
            f.blockConnection.set(true);
            Handle old = f.open("a");
            assertThat(f.connectionEntered.await(1, TimeUnit.SECONDS)).isTrue();
            old.dispose();
            assertThat(f.availableListeners()).isZero();
            assertThatThrownBy(() -> f.open("b").ready.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ChatLiveRecoveryRequiredException.class);
            f.releaseConnection.countDown();
            f.awaitIdle();
            assertThat(f.availableListeners()).isEqualTo(1);
            assertThat(f.channels).isEmpty();
            f.openReady("b").dispose();
            f.awaitIdle();
            assertThat(f.availableListeners()).isEqualTo(1);
        }
    }

    @Test
    void unregisteredAndRejectedTasksReleaseTheirListenerSlotsExactlyOnce() throws Exception {
        try (Fixture f = new Fixture(1)) {
            ChatLiveEventBus.Subscription unused = f.bus.openSubscription("chat-run-unused");
            assertThat(f.availableListeners()).isZero();
            unused.dispose();
            unused.dispose();
            assertThat(f.availableListeners()).isEqualTo(1);
            assertThat(f.connections).hasValue(0);
            f.executor().shutdown();
            Handle rejected = f.open("rejected");
            assertThatThrownBy(() -> rejected.ready.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ChatLiveRecoveryRequiredException.class);
            rejected.dispose();
            assertThat(f.availableListeners()).isEqualTo(1);
            assertThat(f.retainedListeners()).isEmpty();
        }
    }

    @Test
    void concurrentDuplicateAcknowledgementsAndLateTimeoutCannotRevokeReadiness() throws Exception {
        try (Fixture f = new Fixture()) {
            Handle a = f.openReady("a");
            f.advance(Duration.ofSeconds(3));
            List<CompletableFuture<Void>> acknowledgements = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                acknowledgements.add(CompletableFuture.runAsync(() ->
                        ((SubscriptionListener) f.current.get().getListener())
                                .onChannelSubscribed(bytes(f.channel("a")), 1)));
            }
            CompletableFuture.allOf(acknowledgements.toArray(CompletableFuture[]::new)).get(2, TimeUnit.SECONDS);
            Object topic = ((Map<?, ?>) ReflectionTestUtils.getField(f.bus, "topicSinks")).get("chat-run-a");
            Mono<?> lateTimeout = ReflectionTestUtils.invokeMethod(topic, "registrationError",
                    new java.util.concurrent.TimeoutException("late timer"));
            lateTimeout.toFuture().get(1, TimeUnit.SECONDS);
            assertThat(a.errors).isEmpty();
            f.deliver("a", 1);
            assertThat(a.events).hasSize(1);
        }
    }

    @Test
    void firstAckAfterDeadlineFailsAndCannotReviveListener() throws Exception {
        ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        try (Fixture f = new Fixture(callbacks::add)) {
            Handle pending = f.open("a");
            f.awaitIdle();
            f.advance(Duration.ofSeconds(3));
            Runnable task;
            while ((task = callbacks.poll()) != null) {
                task.run();
            }
            assertThatThrownBy(() -> pending.ready.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ChatLiveRecoveryRequiredException.class);
            f.awaitIdle();
            assertThat(f.retainedListeners()).isEmpty();
            assertThat(f.availableListeners()).isEqualTo(4096);
        }
    }

    @Test
    void failedCleanupBackoffIsPerListenerAndSaturatesAtThirtySeconds() throws Exception {
        try (Fixture f = new Fixture()) {
            f.openReady("active");
            f.unsubscribeFailures.set(100);
            f.openReady("failed").dispose();
            f.awaitIdle();
            int attempts = 1;
            for (int seconds : new int[] {1, 2, 4, 8, 16, 30, 30}) {
                f.advance(Duration.ofSeconds(seconds).minusNanos(1));
                f.openReady("active").dispose();
                f.awaitIdle();
                assertThat(f.unsubscribeCalls).hasValue(attempts);
                f.advance(Duration.ofNanos(1));
                f.openReady("active").dispose();
                f.awaitIdle();
                assertThat(f.unsubscribeCalls).hasValue(++attempts);
                assertThat(f.failedCleanup()).hasSize(1);
                assertThat(f.retainedListeners()).hasSize(2);
            }
        }
    }

    @Test
    void repeatedFailureWarningsAreRateLimitedAndIncludeSuppressedCount() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RedisChatLiveEventBus.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (Fixture f = new Fixture()) {
            f.openReady("active");
            f.unsubscribeFailures.set(100);
            for (int i = 0; i < 5; i++) {
                f.openReady("failed-" + i).dispose();
                f.awaitIdle();
            }
            assertThat(appender.list).hasSize(1);
            f.advance(Duration.ofSeconds(29));
            f.openReady("active").dispose();
            f.awaitIdle();
            assertThat(appender.list).hasSize(1);
            f.advance(Duration.ofSeconds(2));
            f.openReady("active").dispose();
            f.awaitIdle();
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.getLast().getFormattedMessage())
                    .contains("failureCount=10", "cleanupBacklog=5");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void acknowledgedChannelIsNotReadyUntilAddReturns() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch acknowledged = new CountDownLatch(1);
            f.afterSubscribe = () -> {
                acknowledged.countDown();
                waitFor(f.releaseAdd);
            };
            Handle pending = f.open("a");
            assertThat(acknowledged.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(pending.ready).isNotDone();
            f.releaseAdd.countDown();
            pending.ready.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void terminalDuringRegistrationCompletesOriginalSubscribeApi() throws Exception {
        try (Fixture f = new Fixture()) {
            f.afterSubscribe = () -> f.deliver("a", 1, "run.completed");
            List<ChatEvent> result = f.bus.subscribe("chat-run-a").collectList().toFuture()
                    .get(2, TimeUnit.SECONDS);
            assertThat(result).extracting(ChatEvent::type).containsExactly("run.completed");
            f.awaitIdle();
            assertThat(f.channels).isEmpty();
            f.assertReleased();
        }
    }

    @Test
    void concurrentFirstRegistrationsWaitForTheirOwnChannelAcknowledgement() throws Exception {
        try (Fixture f = new Fixture()) {
            f.blockConnection.set(true);
            Handle a = f.open("a");
            assertThat(f.connectionEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Handle b = f.open("b");
            assertThat(a.ready).isNotDone();
            assertThat(b.ready).isNotDone();
            f.releaseConnection.countDown();
            a.ready.get(2, TimeUnit.SECONDS);
            b.ready.get(2, TimeUnit.SECONDS);
            assertThat(f.channels).containsExactlyInAnyOrder(f.channel("a"), f.channel("b"));
            f.deliver("a", 1);
            f.deliver("b", 2);
            assertThat(a.events).extracting(ChatEvent::sequence).containsExactly(1L);
            assertThat(b.events).extracting(ChatEvent::sequence).containsExactly(2L);
            f.assertReleased();
        }
    }

    @Test
    void addReturnedWithoutDispatchedAckIsNotReadyAndLateAckCannotReviveCancelledHandle() throws Exception {
        ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        try (Fixture f = new Fixture(callbacks::add)) {
            Handle old = f.open("a");
            f.awaitIdle();
            assertThat(f.container.isListening()).isTrue();
            assertThat(old.ready).isNotDone();
            old.dispose();
            Handle replacement = f.open("a");
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
                Runnable task;
                while ((task = callbacks.poll()) != null) {
                    task.run();
                }
                assertThat(replacement.ready).isCompleted();
            });
            f.deliver("a", 3);
            Runnable task;
            while ((task = callbacks.poll()) != null) {
                task.run();
            }
            assertThat(old.events).isEmpty();
            assertThat(replacement.events).extracting(ChatEvent::sequence).containsExactly(3L);
        }
    }

    @Test
    void cancelWhileAddingCannotRemoveReplacementOrAnotherChannel() throws Exception {
        try (Fixture f = new Fixture()) {
            f.blockConnection.set(true);
            Handle old = f.open("a");
            assertThat(f.connectionEntered.await(1, TimeUnit.SECONDS)).isTrue();
            old.dispose();
            Handle replacement = f.open("a");
            Handle other = f.open("b");
            assertThat(f.listeners()).hasSize(1);
            f.releaseConnection.countDown();
            replacement.ready.get(2, TimeUnit.SECONDS);
            other.ready.get(2, TimeUnit.SECONDS);
            f.awaitIdle();
            assertThat(f.listeners()).hasSize(2);
            f.deliver("a", 1);
            f.deliver("b", 2);
            assertThat(old.events).isEmpty();
            assertThat(replacement.events).hasSize(1);
            assertThat(other.events).hasSize(1);
            f.assertReleased();
        }
    }

    @Test
    void lastUnsubscribeAndNewRegistrationCannotReuseAClosedSubscription() throws Exception {
        try (Fixture f = new Fixture()) {
            Handle a = f.openReady("a");
            f.blockClose.set(true);
            a.dispose();
            assertThat(f.closeEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Handle b = f.open("b");
            assertThat(b.ready).isNotDone();
            assertThat(f.listeners()).isEmpty();
            f.releaseClose.countDown();
            b.ready.get(2, TimeUnit.SECONDS);
            f.deliver("b", 7);
            assertThat(b.events).extracting(ChatEvent::sequence).containsExactly(7L);
            assertThat(f.connections).hasValue(2);
            f.assertReleased();
        }
    }

    @Test
    void rejectedAckReleasesRegistrationAndRecoversForNewAndFailedTopics() throws Exception {
        AtomicBoolean reject = new AtomicBoolean();
        Executor executor = task -> {
            if (reject.get()) {
                throw new IllegalStateException("executor busy");
            }
            task.run();
        };
        try (Fixture f = new Fixture(executor)) {
            Handle a = f.openReady("a");
            reject.set(true);
            Handle b = f.open("b");
            assertThatThrownBy(() -> b.ready.get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ChatLiveRecoveryRequiredException.class);
            f.assertReleased();
            reject.set(false);
            Handle c = f.openReady("c");
            Handle retried = f.openReady("b");
            f.deliver("a", 1);
            f.deliver("b", 2);
            f.deliver("c", 3);
            assertThat(a.events).hasSize(1);
            assertThat(retried.events).hasSize(1);
            assertThat(c.events).hasSize(1);
            assertThat(b.events).isEmpty();
        }
    }

    @Test
    void failedUnsubscribeRepairsMappingBeforeRetryAndPreservesOtherChannel() throws Exception {
        try (Fixture f = new Fixture()) {
            Handle a = f.openReady("a");
            Handle b = f.openReady("b");
            f.unsubscribeFailures.set(1);
            a.dispose();
            f.awaitIdle();
            assertThat(f.channels).hasSize(2);
            assertThat(f.listeners()).hasSize(1);
            assertThat(f.failedCleanup()).hasSize(1);
            f.advance(Duration.ofSeconds(1));
            f.openReady("b");
            f.awaitIdle();
            assertThat(f.unsubscribeCalls).hasValue(2);
            assertThat(f.failedCleanup()).isEmpty();
            assertThat(f.channels).containsExactly(f.channel("b"));
            f.deliver("b", 9);
            assertThat(b.events).hasSize(1);
        }
    }

    @Test
    void partialCleanupRetryNeverDeletesSameChannelReplacement() throws Exception {
        try (Fixture f = new Fixture()) {
            Handle a = f.openReady("a");
            f.openReady("b");
            f.unsubscribeFailures.set(2);
            a.dispose();
            f.awaitIdle();
            f.advance(Duration.ofSeconds(1));
            Handle replacement = f.openReady("a");
            f.awaitIdle();
            assertThat(f.failedCleanup()).hasSize(1);
            f.advance(Duration.ofSeconds(2));
            f.openReady("b");
            f.awaitIdle();
            assertThat(f.failedCleanup()).isEmpty();
            assertThat(f.channels).containsExactlyInAnyOrder(f.channel("a"), f.channel("b"));
            f.deliver("a", 11);
            assertThat(replacement.events).hasSize(1);
        }
    }

    @Test
    void repairAckMustNotExpireAnAlreadyReadyReplacement() throws Exception {
        try (Fixture f = new Fixture()) {
            Handle old = f.openReady("a");
            f.openReady("b");
            f.unsubscribeFailures.set(2);
            old.dispose();
            f.awaitIdle();
            f.advance(Duration.ofSeconds(1));
            Handle replacement = f.openReady("a");
            f.awaitIdle();
            f.advance(Duration.ofSeconds(3));
            f.openReady("b");
            f.awaitIdle();
            assertThat(replacement.errors).isEmpty();
            f.deliver("a", 11);
            f.deliver("a", 12, "run.completed");
            assertThat(replacement.events).extracting(ChatEvent::sequence).containsExactly(11L, 12L);
        }
    }

    @Test
    void lifecycleActivityMustNotRetryEveryFailedListenerImmediately() throws Exception {
        try (Fixture f = new Fixture()) {
            f.openReady("active");
            f.unsubscribeFailures.set(10000);
            for (int i = 0; i < 20; i++) {
                f.openReady("failed-" + i).dispose();
                f.awaitIdle();
            }
            assertThat(f.failedCleanup()).hasSize(20);
            int before = f.unsubscribeCalls.get();
            for (int i = 0; i < 40; i++) {
                f.openReady("active").dispose();
            }
            f.awaitIdle();
            assertThat(f.unsubscribeCalls).hasValue(before);
            f.advance(Duration.ofSeconds(1));
            f.openReady("active").dispose();
            f.awaitIdle();
            assertThat(f.unsubscribeCalls).hasValue(before + 16);
            for (int i = 0; i < 40; i++) {
                f.openReady("active").dispose();
            }
            f.awaitIdle();
            assertThat(f.unsubscribeCalls).hasValue(before + 16);
            f.advance(Duration.ofSeconds(1));
            f.openReady("active").dispose();
            f.awaitIdle();
            assertThat(f.unsubscribeCalls).hasValue(before + 20);
        }
    }

    @Test
    void bulkCleanupIsCoalescedAndDoesNotOccupySharedScheduler() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int i = 0; i < 32; i++) {
                f.openReady("run-" + i);
            }
            f.blockUnsubscribe.set(true);
            f.handles.forEach(Handle::dispose);
            assertThat(f.unsubscribeEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(f.executor().getQueue()).isEmpty();
            assertThat(f.executor().getActiveCount()).isEqualTo(1);
            assertThat(Mono.fromCallable(() -> "unrelated").subscribeOn(Schedulers.boundedElastic())
                    .toFuture().get(1, TimeUnit.SECONDS)).isEqualTo("unrelated");
            f.releaseUnsubscribe.countDown();
            f.awaitIdle();
            assertThat(f.channels).isEmpty();
            assertThat(f.lifecycleThreads).allMatch(name -> name.startsWith("finex-live-lifecycle-"));
            f.assertReleased();
        }
    }

    @Test
    void queuedRegistrationExpiresWithoutLateRedisAdd() throws Exception {
        try (Fixture f = new Fixture()) {
            f.openReady("a");
            f.openReady("b");
            f.blockUnsubscribe.set(true);
            f.handles.getFirst().dispose();
            assertThat(f.unsubscribeEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Handle expired = f.open("expired");
            assertThatThrownBy(() -> expired.ready.get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ChatLiveRecoveryRequiredException.class);
            f.releaseUnsubscribe.countDown();
            f.awaitIdle();
            assertThat(f.subscribedChannels).doesNotContain(f.channel("expired"));
            f.openReady("expired");
            f.assertReleased();
        }
    }

    @Test
    void registrationCapacityAndShutdownReleaseAllQueuedPermits() throws Exception {
        try (Fixture f = new Fixture()) {
            f.blockConnection.set(true);
            f.open("blocked");
            assertThat(f.connectionEntered.await(1, TimeUnit.SECONDS)).isTrue();
            for (int i = 1; i < 1024; i++) {
                f.open("queued-" + i);
            }
            Handle excess = f.open("excess");
            assertThatThrownBy(() -> excess.ready.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ChatLiveRecoveryRequiredException.class);
            assertThat(f.executor().getQueue()).hasSize(1023);
            CompletableFuture<Void> stopped = CompletableFuture.runAsync(f.bus::stop);
            await().atMost(Duration.ofSeconds(1)).until(() ->
                    Boolean.TRUE.equals(ReflectionTestUtils.getField(f.bus, "closed")));
            assertThatThrownBy(() -> f.bus.openSubscription("chat-run-new"))
                    .isInstanceOf(ChatLiveRecoveryRequiredException.class);
            f.releaseConnection.countDown();
            stopped.get(3, TimeUnit.SECONDS);
            f.awaitIdle();
            f.assertReleased();
            assertThat((Map<?, ?>) ReflectionTestUtils.getField(f.bus, "topicSinks")).isEmpty();
            assertThat(f.subscribedChannels).doesNotContain(f.channel("excess"));
            assertThat(f.availableListeners()).isEqualTo(4096);
            assertThat(f.retainedListeners()).isEmpty();
        }
    }

    @Test
    void repeatedSameTopicCancellationReturnsAllListenerResources() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int i = 0; i < 100; i++) {
                Handle a = f.openReady("shared");
                Handle b = f.openReady("shared");
                f.deliver("shared", i + 1);
                assertThat(a.events).hasSize(1);
                assertThat(b.events).hasSize(1);
                a.dispose();
                a.dispose();
                b.dispose();
            }
            f.awaitIdle();
            assertThat(f.channels).isEmpty();
            assertThat(f.listeners()).isEmpty();
            assertThat(f.failedCleanup()).isEmpty();
            assertThat((Map<?, ?>) ReflectionTestUtils.getField(f.bus, "topicSinks")).isEmpty();
            f.assertReleased();
        }
    }

    private static final class Handle implements Disposable {
        private final List<ChatEvent> events = new CopyOnWriteArrayList<>();
        private final List<Throwable> errors = new CopyOnWriteArrayList<>();
        private final ChatLiveEventBus.Subscription handle;
        private final Disposable subscriber;
        private final CompletableFuture<Void> ready;

        private Handle(ChatLiveEventBus.Subscription handle) {
            this.handle = handle;
            subscriber = handle.events().subscribe(events::add, errors::add);
            ready = handle.ready().toFuture();
        }

        @Override
        public void dispose() {
            ready.cancel(false);
            subscriber.dispose();
            handle.dispose();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final FinanceExRedisKeyBuilder keys = FinanceExRedisKeyBuilder.ofEnv("test");
        private final Set<String> channels = ConcurrentHashMap.newKeySet();
        private final Set<String> subscribedChannels = ConcurrentHashMap.newKeySet();
        private final Set<String> lifecycleThreads = ConcurrentHashMap.newKeySet();
        private final AtomicReference<ControlledSubscription> current = new AtomicReference<>();
        private final AtomicBoolean blockConnection = new AtomicBoolean();
        private final AtomicBoolean blockClose = new AtomicBoolean();
        private final AtomicBoolean blockUnsubscribe = new AtomicBoolean();
        private final CountDownLatch connectionEntered = new CountDownLatch(1);
        private final CountDownLatch releaseConnection = new CountDownLatch(1);
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);
        private final CountDownLatch unsubscribeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseUnsubscribe = new CountDownLatch(1);
        private final CountDownLatch releaseAdd = new CountDownLatch(1);
        private volatile Runnable afterSubscribe = () -> { };
        private final AtomicInteger unsubscribeFailures = new AtomicInteger();
        private final AtomicInteger unsubscribeCalls = new AtomicInteger();
        private final AtomicInteger connections = new AtomicInteger();
        private final List<Handle> handles = new ArrayList<>();
        private final AtomicLong now = new AtomicLong(System.nanoTime());
        private final RedisChatLiveEventBus bus;
        private final RedisMessageListenerContainer container;

        private Fixture() {
            this(Runnable::run, new ChatLiveEventBusProperties());
        }

        private Fixture(Executor dispatch) {
            this(dispatch, new ChatLiveEventBusProperties());
        }

        private Fixture(int capacity) {
            this(Runnable::run, liveProperties(capacity));
        }

        private Fixture(Executor dispatch, ChatLiveEventBusProperties properties) {
            LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
            when(factory.getConnection()).thenAnswer(call -> {
                connections.incrementAndGet();
                connectionEntered.countDown();
                if (blockConnection.get()) {
                    waitFor(releaseConnection);
                }
                RedisConnection connection = mock(RedisConnection.class);
                AtomicReference<ControlledSubscription> subscription = new AtomicReference<>();
                when(connection.getSubscription()).thenAnswer(ignored -> subscription.get());
                when(connection.isSubscribed()).thenAnswer(ignored ->
                        subscription.get() != null && subscription.get().isAlive());
                doAnswer(ignored -> {
                    ControlledSubscription next = new ControlledSubscription(ignored.getArgument(0));
                    subscription.set(next);
                    current.set(next);
                    next.subscribe((byte[][]) ignored.getRawArguments()[1]);
                    return null;
                }).when(connection).subscribe(any(MessageListener.class), any(byte[][].class));
                return connection;
            });
            bus = new RedisChatLiveEventBus(mock(StringRedisTemplate.class), new ObjectMapper().findAndRegisterModules(),
                    factory, keys, () -> "instance", properties, Runnable::run);
            ReflectionTestUtils.setField(bus, "nanoTime", (LongSupplier) now::get);
            container = (RedisMessageListenerContainer) ReflectionTestUtils.getField(bus, "listenerContainer");
            container.setTaskExecutor(task -> RedisChatLiveEventBus.dispatchListenerTask(dispatch, task));
            container.setSubscriptionExecutor(Runnable::run);
            bus.start();
        }

        private Handle open(String id) {
            Handle handle = new Handle(bus.openSubscription("chat-run-" + id));
            handles.add(handle);
            return handle;
        }

        private Handle openReady(String id) throws Exception {
            Handle handle = open(id);
            handle.ready.get(3, TimeUnit.SECONDS);
            return handle;
        }

        private String channel(String id) {
            return keys.chatStreamChannel("chat-run-" + id);
        }

        private void deliver(String id, long sequence) {
            deliver(id, sequence, "message.delta");
        }

        private void deliver(String id, long sequence, String type) {
            assertThat(channels).contains(channel(id));
            Message message = mock(Message.class);
            when(message.getChannel()).thenReturn(bytes(channel(id)));
            when(message.getBody()).thenReturn(bytes("{\"runId\":\"" + id
                    + "\",\"sessionId\":\"session\",\"sequence\":" + sequence
                    + ",\"eventType\":\"" + type + "\",\"payload\":{\"delta\":\"body\"}}"));
            current.get().getListener().onMessage(message, null);
        }

        private Map<?, ?> listeners() {
            return (Map<?, ?>) ReflectionTestUtils.getField(container, "listenerTopics");
        }

        private Set<?> failedCleanup() {
            synchronized (ReflectionTestUtils.getField(bus, "cleanupMonitor")) {
                return Set.copyOf((Collection<?>) ReflectionTestUtils.getField(bus, "failedCleanup"));
            }
        }

        private void advance(Duration duration) {
            now.addAndGet(duration.toNanos());
        }

        private int availableListeners() {
            return ((Semaphore) ReflectionTestUtils.getField(bus, "listenerSlots")).availablePermits();
        }

        private Set<?> retainedListeners() {
            return Set.copyOf((Set<?>) ReflectionTestUtils.getField(bus, "retainedTopics"));
        }

        private ThreadPoolExecutor executor() {
            return (ThreadPoolExecutor) ReflectionTestUtils.getField(bus, "lifecycleExecutor");
        }

        private void awaitIdle() {
            await().atMost(Duration.ofSeconds(2)).until(() ->
                    executor().getActiveCount() == 0 && executor().getQueue().isEmpty());
        }

        private void assertReleased() {
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(((Semaphore) ReflectionTestUtils.getField(bus, "registrations")).availablePermits())
                        .isEqualTo(1024);
                assertThat(((ReentrantLock) ReflectionTestUtils.getField(bus, "lifecycleLock")).isLocked()).isFalse();
            });
        }

        @Override
        public void close() {
            releaseConnection.countDown();
            releaseClose.countDown();
            releaseUnsubscribe.countDown();
            releaseAdd.countDown();
            handles.forEach(Handle::dispose);
            bus.stop();
        }

        private final class ControlledSubscription extends AbstractSubscription {
            private ControlledSubscription(MessageListener listener) {
                super(listener);
            }

            @Override
            protected void doSubscribe(byte[]... requested) {
                lifecycleThreads.add(Thread.currentThread().getName());
                for (byte[] channel : requested) {
                    channels.add(new String(channel, StandardCharsets.UTF_8));
                    subscribedChannels.add(new String(channel, StandardCharsets.UTF_8));
                    ((SubscriptionListener) getListener()).onChannelSubscribed(channel, channels.size());
                }
                afterSubscribe.run();
            }

            @Override
            protected void doUnsubscribe(boolean all, byte[]... requested) {
                lifecycleThreads.add(Thread.currentThread().getName());
                unsubscribeCalls.incrementAndGet();
                if (unsubscribeFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                    throw new IllegalStateException("partial unsubscribe failure");
                }
                unsubscribeEntered.countDown();
                if (blockUnsubscribe.get()) {
                    waitFor(releaseUnsubscribe);
                }
                for (byte[] channel : requested) {
                    channels.remove(new String(channel, StandardCharsets.UTF_8));
                    ((SubscriptionListener) getListener()).onChannelUnsubscribed(channel, channels.size());
                }
            }

            @Override
            protected void doClose() {
                closeEntered.countDown();
                if (blockClose.get()) {
                    waitFor(releaseClose);
                }
                channels.clear();
            }

            @Override
            protected void doPsubscribe(byte[]... patterns) { }

            @Override
            protected void doPUnsubscribe(boolean all, byte[]... patterns) { }
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static ChatLiveEventBusProperties liveProperties(int capacity) {
        ChatLiveEventBusProperties properties = new ChatLiveEventBusProperties();
        properties.setRedisLiveMaxListeners(capacity);
        return properties;
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
