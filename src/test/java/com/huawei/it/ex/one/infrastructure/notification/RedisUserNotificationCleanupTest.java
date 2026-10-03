/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.huawei.it.ex.one.domain.notification.UserNotificationRecipient;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.Disposable;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

class RedisUserNotificationCleanupTest {
    @Test
    void cleanupIsCoalescedAndYieldsToFourRegistrationsAfterSixteenRemovals() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<Disposable> handles = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                handles.add(fixture.subscribe("old-" + index).get(1, TimeUnit.SECONDS));
            }
            CountDownLatch release = fixture.blockExecutor();
            fixture.operations.clear();
            handles.forEach(handle -> { handle.dispose(); handle.dispose(); });
            assertThat(fixture.executor.getQueue()).hasSize(1);
            List<CompletableFuture<Disposable>> registrations = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                registrations.add(fixture.subscribe("new-" + index));
            }
            assertThat(fixture.executor.getQueue()).hasSize(5);
            assertThat(fixture.executor.getQueue().remainingCapacity()).isZero();
            assertThat(fixture.executor.getMaximumPoolSize()).isEqualTo(1);
            assertThat(fixture.executor.getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            assertThatThrownBy(() -> fixture.subscribe("overflow").get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            release.countDown();
            for (CompletableFuture<Disposable> registration : registrations) {
                registration.get(1, TimeUnit.SECONDS);
            }
            fixture.awaitIdle();
            assertThat(fixture.operations.subList(0, 16)).containsOnly("remove");
            assertThat(fixture.operations.subList(16, 20)).containsOnly("add");
            assertThat(fixture.operations.subList(20, 68)).containsOnly("remove");
            assertThat(fixture.registered).hasSize(4);
            assertThat(fixture.pending()).isEmpty();
            assertThat(fixture.permits.availablePermits()).isEqualTo(4);
        }
    }

    @Test
    void failedRemovalDoesNotBlockOtherItemsOrRetryWithoutLaterLifecycleActivity() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Disposable first = fixture.subscribe("first").get(1, TimeUnit.SECONDS);
            Disposable second = fixture.subscribe("second").get(1, TimeUnit.SECONDS);
            AtomicInteger attempts = new AtomicInteger();
            fixture.onRemove = ignored -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("Redis temporarily unavailable");
                }
            };
            CountDownLatch release = fixture.blockExecutor();
            first.dispose();
            second.dispose();
            release.countDown();
            fixture.awaitIdle();
            assertThat(attempts).hasValue(2);
            assertThat(fixture.registered).hasSize(1);
            assertThat(fixture.failed()).hasSize(1);
            Object failed = fixture.failed().iterator().next();
            assertThat(ReflectionTestUtils.getField(failed, "removed")).isEqualTo(false);
            fixture.subscribe("next").get(1, TimeUnit.SECONDS);
            fixture.awaitIdle();
            assertThat(attempts).hasValue(3);
            assertThat(fixture.failed()).isEmpty();
            assertThat(fixture.registered).hasSize(1);
            assertThat(ReflectionTestUtils.getField(failed, "removed")).isEqualTo(true);
        }
    }

    @Test
    void rejectedCleanupRetainsItsReferenceUntilALaterSubscriptionCanRescheduleIt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Disposable handle = fixture.subscribe("first").get(1, TimeUnit.SECONDS);
            CountDownLatch release = fixture.blockExecutor();
            for (int index = 0; index < 5; index++) {
                fixture.executor.execute(() -> { });
            }
            handle.dispose();
            assertThat(fixture.pending()).hasSize(1);
            assertThat(fixture.registered).hasSize(1);
            assertThat(fixture.operations).containsExactly("add");
            assertThatThrownBy(() -> fixture.subscribe("rejected").get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(RejectedExecutionException.class);
            assertThat(fixture.permits.availablePermits()).isEqualTo(4);
            release.countDown();
            fixture.awaitIdle();
            fixture.subscribe("retry").get(1, TimeUnit.SECONDS);
            fixture.awaitIdle();
            assertThat(fixture.pending()).isEmpty();
            assertThat(fixture.operations).containsExactly("add", "remove", "add");
        }
    }

    @Test
    void registrationsExpireWhileCleanupIsBlockedAndNeverAddAfterRecovery() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Disposable first = fixture.subscribe("first").get(1, TimeUnit.SECONDS);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = fixture.barrier();
            fixture.onRemove = ignored -> { entered.countDown(); waitFor(release); };
            first.dispose();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            List<CompletableFuture<Disposable>> registrations = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                registrations.add(fixture.subscribe("waiting-" + index));
            }
            for (CompletableFuture<Disposable> registration : registrations) {
                assertThatThrownBy(() -> registration.get(3, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(TimeoutException.class);
            }
            release.countDown();
            fixture.awaitIdle();
            assertThat(fixture.operations).containsExactly("add", "remove");
            assertThat(fixture.permits.availablePermits()).isEqualTo(4);
            fixture.subscribe("waiting-0").get(1, TimeUnit.SECONDS);
            assertThat(fixture.registered).hasSize(1);
        }
    }

    @Test
    void cancellationDuringTheLastRemovalStillSchedulesTheNextBatch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Disposable first = fixture.subscribe("first").get(1, TimeUnit.SECONDS);
            Disposable second = fixture.subscribe("second").get(1, TimeUnit.SECONDS);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = fixture.barrier();
            fixture.onRemove = ignored -> { entered.countDown(); waitFor(release); };
            first.dispose();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            second.dispose();
            assertThat(fixture.executor.getQueue()).isEmpty();
            assertThat(fixture.pending()).hasSize(1);
            release.countDown();
            fixture.awaitIdle();
            assertThat(fixture.operations).containsExactly("add", "add", "remove", "remove");
            assertThat(fixture.pending()).isEmpty();
            assertThat(fixture.registered).isEmpty();
        }
    }

    @Test
    void shutdownClearsPendingCleanupAndQueuedRegistrationsReturnTheirPermits() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Disposable first = fixture.subscribe("first").get(1, TimeUnit.SECONDS);
            CountDownLatch release = fixture.blockExecutor();
            first.dispose();
            List<CompletableFuture<Disposable>> registrations = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                registrations.add(fixture.subscribe("waiting-" + index));
            }
            fixture.bus.stop();
            assertThat(fixture.executor.isShutdown()).isTrue();
            assertThat(fixture.pending()).isEmpty();
            assertThat(fixture.failed()).isEmpty();
            release.countDown();
            assertThat(fixture.executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            for (CompletableFuture<Disposable> registration : registrations) {
                assertThat(registration).isCompletedExceptionally();
            }
            assertThat(fixture.operations).containsExactly("add");
            assertThat(fixture.permits.availablePermits()).isEqualTo(4);
        }
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        private final RedisUserNotificationBus bus = new RedisUserNotificationBus(mock(StringRedisTemplate.class),
                new ObjectMapper(), FinanceExRedisKeyBuilder.ofEnv("test"), container);
        private final ThreadPoolExecutor executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(bus, "lifecycleExecutor");
        private final Semaphore permits = (Semaphore) ReflectionTestUtils.getField(bus, "registrations");
        private final List<String> operations = new CopyOnWriteArrayList<>();
        private final Set<MessageListener> registered = ConcurrentHashMap.newKeySet();
        private final List<CountDownLatch> barriers = new ArrayList<>();
        private volatile Consumer<MessageListener> onRemove = ignored -> { };

        private Fixture() {
            doAnswer(call -> {
                assertThat(Thread.currentThread().getName()).startsWith("finex-notification-lifecycle-");
                operations.add("add");
                MessageListener listener = call.getArgument(0);
                registered.add(listener);
                ((SubscriptionListener) listener).onChannelSubscribed(
                        call.getArgument(1, ChannelTopic.class).getTopic().getBytes(StandardCharsets.UTF_8), 1);
                return null;
            }).when(container).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
            doAnswer(call -> {
                assertThat(Thread.currentThread().getName()).startsWith("finex-notification-lifecycle-");
                operations.add("remove");
                MessageListener listener = call.getArgument(0);
                onRemove.accept(listener);
                registered.remove(listener);
                return null;
            }).when(container).removeMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        }

        private CompletableFuture<Disposable> subscribe(String user) {
            return bus.subscribe(new UserNotificationRecipient("tenant", user), ignored -> { }).toFuture();
        }

        private CountDownLatch barrier() {
            CountDownLatch latch = new CountDownLatch(1);
            barriers.add(latch);
            return latch;
        }

        private CountDownLatch blockExecutor() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = barrier();
            executor.execute(() -> { entered.countDown(); waitFor(release); });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            return release;
        }

        private Set<?> pending() {
            return cleanupSnapshot("pendingCleanup");
        }

        private Set<?> failed() {
            return cleanupSnapshot("failedCleanup");
        }

        private Set<?> cleanupSnapshot(String field) {
            synchronized (ReflectionTestUtils.getField(bus, "cleanupMonitor")) {
                return Set.copyOf((Set<?>) ReflectionTestUtils.getField(bus, field));
            }
        }

        private void awaitIdle() {
            await().atMost(Duration.ofSeconds(3)).until(() ->
                    executor.getActiveCount() == 0 && executor.getQueue().isEmpty());
        }

        @Override
        public void close() throws Exception {
            barriers.forEach(CountDownLatch::countDown);
            bus.stop();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }
}
