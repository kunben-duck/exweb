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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.Subscription;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** 走生产构造器与实际 Spring 容器，模拟 Lettuce 捕获回调异常而非向注册线程抛错。 */
class RedisUserNotificationDispatchIsolationTest {
    private static final UserNotificationRecipient FIRST = new UserNotificationRecipient("tenant", "first");
    private static final UserNotificationRecipient SECOND = new UserNotificationRecipient("tenant", "second");
    private static final UserNotificationRecipient THIRD = new UserNotificationRecipient("tenant", "third");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedAdditionalAckReleasesRegistrationAndAllowsNewUsersAndRetry(boolean standardRejection) throws Exception {
        AtomicReference<RuntimeException> rejection = new AtomicReference<>();
        try (Fixture fixture = new Fixture(task -> {
            if (rejection.get() != null) {
                throw rejection.get();
            }
            task.run();
        })) {
            fixture.subscribe(FIRST);
            rejection.set(standardRejection ? new RejectedExecutionException("busy")
                    : new IllegalStateException("Redis live publish executor queue is full"));
            fixture.expectRegistrationTimeout(SECOND);
            fixture.assertRegistrationReleased();
            assertThat(fixture.callbackErrors).isEmpty();

            rejection.set(null);
            fixture.subscribe(THIRD);
            fixture.subscribe(SECOND);
            fixture.deliver(FIRST);
            fixture.deliver(SECOND);
            fixture.deliver(THIRD);
            assertThat(fixture.received).hasSize(3);
        }
    }

    @Test
    void rejectedFirstAckNeverConfirmsSuccessAndCanBeRetried() throws Exception {
        AtomicReference<RuntimeException> rejection = new AtomicReference<>(new IllegalStateException("busy"));
        try (Fixture fixture = new Fixture(task -> {
            if (rejection.get() != null) {
                throw rejection.get();
            }
            task.run();
        })) {
            fixture.expectRegistrationTimeout(FIRST);
            fixture.assertRegistrationReleased();
            assertThat(fixture.callbackErrors).isEmpty();
            rejection.set(null);
            fixture.subscribe(FIRST);
            fixture.deliver(FIRST);
            assertThat(fixture.received).hasSize(1);
        }
    }

    @Test
    void saturatedRealExecutorRecoversWithoutRestartingTheContainer() throws Exception {
        ThreadPoolExecutor executor = executor();
        CountDownLatch release = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(executor)) {
            fixture.subscribe(FIRST);
            CountDownLatch occupied = new CountDownLatch(1);
            executor.execute(() -> {
                occupied.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(occupied.await(1, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> { });
            fixture.expectRegistrationTimeout(SECOND);
            fixture.assertRegistrationReleased();
            assertThat(fixture.callbackErrors).isEmpty();
            release.countDown();
            await().atMost(Duration.ofSeconds(1)).until(() -> executor.getActiveCount() == 0
                    && executor.getQueue().isEmpty());
            fixture.subscribe(THIRD);
            fixture.subscribe(SECOND);
            fixture.deliver(SECOND);
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(fixture.received).hasSize(1));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void shutdownExecutorDoesNotStrandRegistrationOrForgeSuccess() throws Exception {
        ThreadPoolExecutor executor = executor();
        try (Fixture fixture = new Fixture(executor)) {
            fixture.subscribe(FIRST);
            executor.shutdown();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            fixture.expectRegistrationTimeout(SECOND);
            fixture.assertRegistrationReleased();
            assertThat(fixture.callbackErrors).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectedBusinessDispatchIsDroppedWithoutInlineDeliveryOrLosingTheListener() throws Exception {
        AtomicReference<RuntimeException> rejection = new AtomicReference<>();
        try (Fixture fixture = new Fixture(task -> {
            if (rejection.get() != null) {
                throw rejection.get();
            }
            task.run();
        })) {
            fixture.subscribe(FIRST);
            rejection.set(new IllegalStateException("busy"));
            fixture.deliver(FIRST);
            assertThat(fixture.received).isEmpty();
            assertThat(fixture.callbackErrors).isEmpty();
            rejection.set(null);
            fixture.deliver(FIRST);
            assertThat(fixture.received).hasSize(1);
        }
    }

    private static ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class Fixture implements AutoCloseable {
        private final FinanceExRedisKeyBuilder keys = FinanceExRedisKeyBuilder.ofEnv("sit");
        private final Set<String> channels = ConcurrentHashMap.newKeySet();
        private final Set<String> requestedChannels = ConcurrentHashMap.newKeySet();
        private final AtomicReference<MessageListener> listener = new AtomicReference<>();
        private final List<RuntimeException> callbackErrors = new CopyOnWriteArrayList<>();
        private final List<UserNotification> received = new CopyOnWriteArrayList<>();
        private final List<Disposable> handles = new CopyOnWriteArrayList<>();
        private final RedisUserNotificationBus bus;

        private Fixture(Executor executor) {
            LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
            RedisConnection connection = mock(RedisConnection.class);
            Subscription subscription = mock(Subscription.class);
            when(factory.getConnection()).thenReturn(connection);
            when(connection.getSubscription()).thenReturn(subscription);
            when(connection.isSubscribed()).thenAnswer(call -> !channels.isEmpty());
            doAnswer(call -> {
                listener.set(call.getArgument(0));
                subscribeChannels((byte[][]) call.getRawArguments()[1]);
                return null;
            }).when(connection).subscribe(any(MessageListener.class), any(byte[][].class));
            doAnswer(call -> {
                subscribeChannels((byte[][]) call.getRawArguments()[0]);
                return null;
            }).when(subscription).subscribe(any(byte[][].class));
            doAnswer(call -> {
                for (byte[] channel : (byte[][]) call.getRawArguments()[0]) {
                    channels.remove(new String(channel, StandardCharsets.UTF_8));
                    callback(() -> ((SubscriptionListener) listener.get()).onChannelUnsubscribed(channel, channels.size()));
                }
                return null;
            }).when(subscription).unsubscribe(any(byte[][].class));
            doAnswer(call -> { channels.clear(); return null; }).when(subscription).close();
            bus = new RedisUserNotificationBus(mock(StringRedisTemplate.class), new ObjectMapper(), keys, factory, executor);
            bus.start();
        }

        private void subscribeChannels(byte[][] requested) {
            for (byte[] channel : requested) {
                String name = new String(channel, StandardCharsets.UTF_8);
                channels.add(name);
                requestedChannels.add(name);
                acknowledge(channel);
            }
        }

        private void acknowledge(byte[] channel) {
            callback(() -> ((SubscriptionListener) listener.get()).onChannelSubscribed(channel, channels.size()));
        }

        private void callback(Runnable callback) {
            try {
                callback.run();
            } catch (RuntimeException ex) {
                callbackErrors.add(ex);
            }
        }

        private void subscribe(UserNotificationRecipient recipient) throws Exception {
            handles.add(bus.subscribe(recipient, received::add).toFuture().get(3, TimeUnit.SECONDS));
        }

        private void expectRegistrationTimeout(UserNotificationRecipient recipient) {
            CompletableFuture<Disposable> pending = bus.subscribe(recipient, received::add).toFuture();
            assertThatThrownBy(() -> pending.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(TimeoutException.class);
            await().atMost(Duration.ofSeconds(1)).until(() -> !channels.contains(channel(recipient)));
        }

        private void assertRegistrationReleased() {
            ReentrantLock lock = (ReentrantLock) ReflectionTestUtils.getField(bus, "registrationLock");
            Semaphore permits = (Semaphore) ReflectionTestUtils.getField(bus, "registrations");
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
                assertThat(lock.isLocked()).isFalse();
                assertThat(permits.availablePermits()).isEqualTo(4);
            });
        }

        private String channel(UserNotificationRecipient recipient) {
            return keys.userNotificationChannel(recipient.tenantId(), recipient.userId());
        }

        private void deliver(UserNotificationRecipient recipient) {
            String channel = channel(recipient);
            assertThat(channels).contains(channel);
            Message message = mock(Message.class);
            when(message.getChannel()).thenReturn(bytes(channel));
            when(message.getBody()).thenReturn(bytes("{\"type\":\"test\",\"data\":{}}"));
            callback(() -> listener.get().onMessage(message, null));
        }

        @Override
        public void close() {
            // 失败基线中的 Future 可能仍未完成；仅在测试清理时补 ACK，避免滞留验证线程。
            requestedChannels.forEach(channel -> acknowledge(bytes(channel)));
            handles.forEach(Disposable::dispose);
            bus.stop();
        }
    }
}
