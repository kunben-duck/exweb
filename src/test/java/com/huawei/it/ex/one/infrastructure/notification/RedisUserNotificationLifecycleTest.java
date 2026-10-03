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
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.domain.notification.UserNotification;
import com.huawei.it.ex.one.domain.notification.UserNotificationRecipient;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.Subscription;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** 使用生产构造器和实际 Spring 容器，仅在底层连接上控制生命周期交错。 */
class RedisUserNotificationLifecycleTest {
    private static final UserNotificationRecipient FIRST = new UserNotificationRecipient("tenant", "first");
    private static final UserNotificationRecipient SECOND = new UserNotificationRecipient("tenant", "second");

    @Test
    void massDisconnectDoesNotStarveTheSharedSchedulerInAnIsolatedJvm() throws Exception {
        Path output = Files.createTempFile("notification-cleanup-isolation-", ".log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dreactor.schedulers.defaultBoundedElasticSize=2", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                CleanupIsolationProbe.class.getName())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("isolated cleanup probe finished").isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(output)).isZero();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(output);
        }
    }

    public static final class CleanupIsolationProbe {
        public static void main(String[] args) throws Exception {
            assertThat(Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE).isEqualTo(2);
            try (Fixture fixture = new Fixture(false, false)) {
                for (int index = 0; index < 32; index++) {
                    fixture.subscribe(new UserNotificationRecipient("tenant", "user-" + index));
                }
                fixture.blockUnsubscribe.set(true);
                fixture.handles.forEach(Disposable::dispose);
                assertThat(fixture.unsubscribeEntered.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(Mono.fromCallable(() -> "unrelated-work")
                        .subscribeOn(Schedulers.boundedElastic()).toFuture().get(1, TimeUnit.SECONDS))
                        .isEqualTo("unrelated-work");
                assertThat(fixture.lifecycleThreads).allMatch(name -> name.startsWith("finex-notification-lifecycle-"));
                fixture.releaseUnsubscribe.countDown();
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(fixture.channels).isEmpty());
                fixture.assertReleased();
            } finally {
                Schedulers.shutdownNow();
            }
        }
    }

    @Test
    void newRegistrationCannotMutateContainerWhileLastListenerIsBeingRemoved() throws Exception {
        try (Fixture fixture = new Fixture(false, true)) {
            Disposable first = fixture.subscribe(FIRST);
            first.dispose();
            first.dispose();
            assertThat(fixture.closeEntered.await(1, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Disposable> second = fixture.bus.subscribe(SECOND, fixture.received::add).toFuture();
            assertThat(second).isNotDone();
            await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertThat(fixture.containerListeners()).isEmpty());
            fixture.releaseClose.countDown();
            fixture.handles.add(second.get(2, TimeUnit.SECONDS));
            fixture.subscribe(FIRST);
            fixture.deliver(FIRST);
            fixture.deliver(SECOND);
            assertThat(fixture.received).hasSize(2);
            assertThat(fixture.closeCalls.get()).isEqualTo(1);
            fixture.assertReleased();
        }
    }

    @Test
    void cancelledFirstRegistrationIsCleanedOnlyAfterAddReturnsAndCanBeRetried() throws Exception {
        try (Fixture fixture = new Fixture(true, false)) {
            CompletableFuture<Disposable> first = fixture.bus.subscribe(FIRST, fixture.received::add).toFuture();
            assertThat(fixture.connectionEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(first.cancel(false)).isTrue();
            CompletableFuture<Disposable> replacement = fixture.bus.subscribe(FIRST, fixture.received::add).toFuture();
            // 取消只能登记清理，不能在 add 仍持有初始化状态时清空容器映射。
            await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertThat(fixture.containerListeners()).hasSize(1));
            fixture.releaseConnection.countDown();
            fixture.handles.add(replacement.get(2, TimeUnit.SECONDS));
            fixture.subscribe(SECOND);
            fixture.deliver(FIRST);
            fixture.deliver(SECOND);
            assertThat(fixture.received).hasSize(2);
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertThat(fixture.containerListeners()).hasSize(2));
            fixture.assertReleased();
        }
    }

    @Test
    void stopRejectsNewSubscribersBeforeWaitingForExistingCleanup() throws Exception {
        try (Fixture fixture = new Fixture(false, true)) {
            fixture.subscribe(FIRST).dispose();
            assertThat(fixture.closeEntered.await(1, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> stop = CompletableFuture.runAsync(fixture.bus::stop);
            await().atMost(Duration.ofSeconds(1)).until(() ->
                    Boolean.TRUE.equals(ReflectionTestUtils.getField(fixture.bus, "closed")));
            assertThatThrownBy(() -> fixture.bus.subscribe(SECOND, ignored -> { }).block())
                    .isInstanceOf(IllegalStateException.class);
            assertThat(stop).isNotDone();
            // destroy 必须等前一次 remove 完成，不能并发改变容器运行状态。
            assertThat(fixture.container.isRunning()).isTrue();
            fixture.releaseClose.countDown();
            stop.get(2, TimeUnit.SECONDS);
            assertThat(fixture.container.isRunning()).isFalse();
            fixture.assertReleased();
        }
    }

    @Test
    void retryAfterPartialRemovalActuallyUnsubscribesTheOrphanedChannel() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            Disposable first = fixture.subscribe(FIRST);
            fixture.subscribe(SECOND);
            fixture.unsubscribeFailures.set(1);
            first.dispose();
            fixture.awaitIdle();
            assertThat(fixture.failedCleanup()).hasSize(1);
            Object old = fixture.failedCleanup().iterator().next();
            assertThat(fixture.containerListeners()).hasSize(1);
            assertThat(fixture.channels).hasSize(2);
            fixture.subscribe(SECOND);
            fixture.awaitIdle();
            assertThat(fixture.unsubscribeCalls).hasValue(2);
            assertThat(fixture.channels).containsExactly(fixture.channel(SECOND));
            assertThat(fixture.failedCleanup()).isEmpty();
            assertThat((AtomicBoolean) ReflectionTestUtils.getField(old, "closed")).isTrue();
            assertThat(ReflectionTestUtils.getField(old, "removed")).isEqualTo(true);
            fixture.deliver(SECOND);
            assertThat(fixture.received).hasSize(2);
            assertThat(fixture.closeCalls).hasValue(0);
        }
    }

    @Test
    void repairingAnOldListenerDoesNotUnsubscribeItsReplacementOrOtherUsers() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            Disposable first = fixture.subscribe(FIRST);
            fixture.subscribe(SECOND);
            fixture.unsubscribeFailures.set(2);
            first.dispose();
            fixture.awaitIdle();
            fixture.subscribe(FIRST);
            fixture.awaitIdle();
            assertThat(fixture.failedCleanup()).hasSize(1);
            assertThat(fixture.unsubscribeCalls).hasValue(2);
            fixture.subscribe(SECOND);
            fixture.awaitIdle();
            assertThat(fixture.failedCleanup()).isEmpty();
            assertThat(fixture.containerListeners()).hasSize(2);
            assertThat(fixture.channels).containsExactlyInAnyOrder(fixture.channel(FIRST), fixture.channel(SECOND));
            assertThat(fixture.unsubscribeCalls).hasValue(2);
            fixture.deliver(FIRST);
            fixture.deliver(SECOND);
            assertThat(fixture.received).hasSize(3);
            assertThat(fixture.closeCalls).hasValue(0);
        }
    }

    @Test
    void failedRepairRemainsPendingAndCanRecoverWithoutRestartingTheContainer() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            Disposable first = fixture.subscribe(FIRST);
            fixture.subscribe(SECOND);
            fixture.unsubscribeFailures.set(1);
            first.dispose();
            fixture.awaitIdle();
            Object old = fixture.failedCleanup().iterator().next();
            fixture.subscribeFailures.set(1);
            fixture.subscribe(SECOND);
            fixture.awaitIdle();
            assertThat(fixture.failedCleanup()).hasSize(1);
            assertThat(fixture.failedCleanup().contains(old)).isTrue();
            assertThat(ReflectionTestUtils.getField(old, "removed")).isEqualTo(false);
            fixture.deliver(FIRST);
            assertThat(fixture.received).isEmpty();
            fixture.deliver(SECOND);
            assertThat(fixture.received).hasSize(2);
            fixture.subscribe(SECOND);
            fixture.awaitIdle();
            assertThat(fixture.failedCleanup()).isEmpty();
            assertThat(fixture.channels).containsExactly(fixture.channel(SECOND));
            assertThat(fixture.unsubscribeCalls).hasValue(2);
        }
    }

    @Test
    void successfulLastChannelRemovalStillClosesTheSubscriptionOnce() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            fixture.subscribe(FIRST).dispose();
            fixture.awaitIdle();
            assertThat(fixture.closeCalls).hasValue(1);
            assertThat(fixture.channels).isEmpty();
            assertThat(fixture.failedCleanup()).isEmpty();
            fixture.subscribe(SECOND);
            fixture.deliver(SECOND);
            assertThat(fixture.received).hasSize(1);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final FinanceExRedisKeyBuilder keys = FinanceExRedisKeyBuilder.ofEnv("sit");
        private final Set<String> channels = ConcurrentHashMap.newKeySet();
        private final AtomicReference<MessageListener> listener = new AtomicReference<>();
        private final List<UserNotification> received = new CopyOnWriteArrayList<>();
        private final List<Disposable> handles = new CopyOnWriteArrayList<>();
        private final CountDownLatch connectionEntered = new CountDownLatch(1);
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseConnection;
        private final CountDownLatch releaseClose;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicInteger unsubscribeCalls = new AtomicInteger();
        private final AtomicInteger unsubscribeFailures = new AtomicInteger();
        private final AtomicInteger subscribeFailures = new AtomicInteger();
        private final AtomicBoolean blockUnsubscribe = new AtomicBoolean();
        private final CountDownLatch unsubscribeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseUnsubscribe = new CountDownLatch(1);
        private final Set<String> lifecycleThreads = ConcurrentHashMap.newKeySet();
        private final RedisUserNotificationBus bus;
        private final RedisMessageListenerContainer container;

        private Fixture(boolean blockConnection, boolean blockClose) {
            releaseConnection = new CountDownLatch(blockConnection ? 1 : 0);
            releaseClose = new CountDownLatch(blockClose ? 1 : 0);
            LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
            RedisConnection connection = mock(RedisConnection.class);
            Subscription subscription = mock(Subscription.class);
            when(factory.getConnection()).thenAnswer(call -> {
                connectionEntered.countDown();
                assertThat(releaseConnection.await(5, TimeUnit.SECONDS)).isTrue();
                return connection;
            });
            when(connection.getSubscription()).thenReturn(subscription);
            when(connection.isSubscribed()).thenAnswer(call -> !channels.isEmpty());
            doAnswer(call -> {
                lifecycleThreads.add(Thread.currentThread().getName());
                listener.set(call.getArgument(0));
                acknowledge((byte[][]) call.getRawArguments()[1]);
                return null;
            }).when(connection).subscribe(any(MessageListener.class), any(byte[][].class));
            doAnswer(call -> {
                lifecycleThreads.add(Thread.currentThread().getName());
                if (subscribeFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                    throw new IllegalStateException("injected repair subscribe failure");
                }
                acknowledge((byte[][]) call.getRawArguments()[0]);
                return null;
            }).when(subscription).subscribe(any(byte[][].class));
            doAnswer(call -> {
                lifecycleThreads.add(Thread.currentThread().getName());
                unsubscribeCalls.incrementAndGet();
                if (unsubscribeFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                    throw new IllegalStateException("injected partial unsubscribe failure");
                }
                if (blockUnsubscribe.get()) {
                    unsubscribeEntered.countDown();
                    assertThat(releaseUnsubscribe.await(10, TimeUnit.SECONDS)).isTrue();
                }
                for (byte[] channel : (byte[][]) call.getRawArguments()[0]) {
                    channels.remove(new String(channel, StandardCharsets.UTF_8));
                    ((SubscriptionListener) listener.get()).onChannelUnsubscribed(channel, channels.size());
                }
                return null;
            }).when(subscription).unsubscribe(any(byte[][].class));
            doAnswer(call -> {
                closeCalls.incrementAndGet();
                closeEntered.countDown();
                assertThat(releaseClose.await(5, TimeUnit.SECONDS)).isTrue();
                channels.clear();
                return null;
            }).when(subscription).close();
            bus = new RedisUserNotificationBus(mock(StringRedisTemplate.class), new ObjectMapper(), keys,
                    factory, Runnable::run);
            container = (RedisMessageListenerContainer) ReflectionTestUtils.getField(bus, "container");
            bus.start();
        }

        private void acknowledge(byte[][] requested) {
            for (byte[] channel : requested) {
                channels.add(new String(channel, StandardCharsets.UTF_8));
                ((SubscriptionListener) listener.get()).onChannelSubscribed(channel, channels.size());
            }
        }

        private Disposable subscribe(UserNotificationRecipient recipient) throws Exception {
            Disposable handle = bus.subscribe(recipient, received::add).toFuture().get(3, TimeUnit.SECONDS);
            handles.add(handle);
            return handle;
        }

        private Map<?, ?> containerListeners() {
            return (Map<?, ?>) ReflectionTestUtils.getField(container, "listenerTopics");
        }

        private String channel(UserNotificationRecipient recipient) {
            return keys.userNotificationChannel(recipient.tenantId(), recipient.userId());
        }

        private Set<?> failedCleanup() {
            synchronized (ReflectionTestUtils.getField(bus, "cleanupMonitor")) {
                return Set.copyOf((Set<?>) ReflectionTestUtils.getField(bus, "failedCleanup"));
            }
        }

        private void awaitIdle() {
            ThreadPoolExecutor executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(bus, "lifecycleExecutor");
            await().atMost(Duration.ofSeconds(2)).until(() ->
                    executor.getActiveCount() == 0 && executor.getQueue().isEmpty());
        }

        private void deliver(UserNotificationRecipient recipient) {
            String channel = keys.userNotificationChannel(recipient.tenantId(), recipient.userId());
            assertThat(channels).contains(channel);
            Message message = mock(Message.class);
            when(message.getChannel()).thenReturn(channel.getBytes(StandardCharsets.UTF_8));
            when(message.getBody()).thenReturn("{\"type\":\"test\",\"data\":{}}".getBytes(StandardCharsets.UTF_8));
            listener.get().onMessage(message, null);
        }

        private void assertReleased() {
            ReentrantLock lock = (ReentrantLock) ReflectionTestUtils.getField(bus, "registrationLock");
            Semaphore permits = (Semaphore) ReflectionTestUtils.getField(bus, "registrations");
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
                assertThat(lock.isLocked()).isFalse();
                assertThat(permits.availablePermits()).isEqualTo(4);
            });
        }

        @Override
        public void close() {
            releaseConnection.countDown();
            releaseClose.countDown();
            releaseUnsubscribe.countDown();
            handles.forEach(Disposable::dispose);
            bus.stop();
        }
    }
}
