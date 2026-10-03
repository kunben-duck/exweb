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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.domain.notification.UserNotification;
import com.huawei.it.ex.one.domain.notification.UserNotificationRecipient;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.Disposable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.Subscription;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 使用项目实际容器执行建连、订阅和 ACK 分发，只控制底层连接。 */
class RedisUserNotificationRegistrationTest {
    private final List<Fixture> fixtures = new ArrayList<>();
    private final UserNotificationRecipient first = new UserNotificationRecipient("tenant", "first");
    private final UserNotificationRecipient second = new UserNotificationRecipient("tenant", "second");

    @AfterEach
    void close() {
        fixtures.forEach(fixture -> fixture.releaseConnection.countDown());
        fixtures.forEach(fixture -> fixture.bus.stop());
    }

    @Test
    void concurrentFirstSubscriptionsBothRegisterTheirActualChannels() throws Exception {
        Fixture fixture = fixture(Runnable::run, true);
        List<UserNotification> firstMessages = new CopyOnWriteArrayList<>();
        List<UserNotification> secondMessages = new CopyOnWriteArrayList<>();
        CompletableFuture<Disposable> one = fixture.bus.subscribe(first, firstMessages::add).toFuture();
        assertThat(fixture.connectionEntered.await(1, TimeUnit.SECONDS)).isTrue();
        // 第一个初始化已拍下频道集合，第二个注册此时到达：旧实现会漏掉它。
        CompletableFuture<Disposable> two = fixture.bus.subscribe(second, secondMessages::add).toFuture();
        fixture.secondAddEntered.await(300, TimeUnit.MILLISECONDS);
        assertThat(one).isNotDone();
        assertThat(two).isNotDone();
        fixture.releaseConnection.countDown();
        Disposable firstHandle = one.get(2, TimeUnit.SECONDS);
        Disposable secondHandle = two.get(2, TimeUnit.SECONDS);
        assertThat(fixture.channels).containsExactlyInAnyOrder(fixture.channel(first), fixture.channel(second));
        fixture.deliver(first);
        fixture.deliver(second);
        assertThat(firstMessages).hasSize(1);
        assertThat(secondMessages).hasSize(1);
        firstHandle.dispose();
        secondHandle.dispose();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(fixture.channels).isEmpty());
    }

    @Test
    void listeningContainerAndReturnedRegistrationAreNotEnoughWithoutChannelAckDelivery() throws Exception {
        ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        Fixture fixture = fixture(callbacks::add, false);
        CompletableFuture<Disposable> pending = fixture.bus.subscribe(first, ignored -> { }).toFuture();
        assertThat(fixture.firstAddReturned.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(fixture.container.isListening()).isTrue();
        assertThat(pending).isNotDone();
        assertThat(callbacks).isNotEmpty();
        callbacks.remove().run();
        pending.get(1, TimeUnit.SECONDS).dispose();
    }

    @Test
    void rejectedAckDispatchCannotBecomeSuccessfulSubscription() {
        Fixture fixture = fixture(task -> { throw new RejectedExecutionException("busy"); }, false);
        CompletableFuture<Disposable> pending = fixture.bus.subscribe(first, ignored -> { }).toFuture();
        assertThatThrownBy(() -> pending.get(3, TimeUnit.SECONDS))
                .hasCauseInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void cancelBeforeAckAndImmediateResubscribeDoesNotRemoveTheReplacementListener() throws Exception {
        ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        Fixture fixture = fixture(callbacks::add, false);
        CompletableFuture<Disposable> pending = fixture.bus.subscribe(first, ignored -> { }).toFuture();
        assertThat(fixture.firstAddReturned.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(pending.cancel(false)).isTrue();
        List<UserNotification> received = new CopyOnWriteArrayList<>();
        CompletableFuture<Disposable> replacement = fixture.bus.subscribe(first, received::add).toFuture();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
            Runnable callback;
            while ((callback = callbacks.poll()) != null) {
                callback.run();
            }
            assertThat(replacement).isDone();
        });
        Disposable active = replacement.get(1, TimeUnit.SECONDS);
        fixture.deliver(first);
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
            Runnable callback;
            while ((callback = callbacks.poll()) != null) {
                callback.run();
            }
            assertThat(received).hasSize(1);
        });
        active.dispose();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(fixture.channels).isEmpty());
    }

    private Fixture fixture(Executor callbackExecutor, boolean blockConnection) {
        Fixture fixture = new Fixture(callbackExecutor, blockConnection);
        fixtures.add(fixture);
        return fixture;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class Fixture {
        private final RedisMessageListenerContainer container = spy(new RedisMessageListenerContainer());
        private final FinanceExRedisKeyBuilder keys = FinanceExRedisKeyBuilder.ofEnv("sit");
        private final Set<String> channels = ConcurrentHashMap.newKeySet();
        private final AtomicReference<MessageListener> listener = new AtomicReference<>();
        private final CountDownLatch connectionEntered = new CountDownLatch(1);
        private final CountDownLatch releaseConnection;
        private final CountDownLatch secondAddEntered = new CountDownLatch(1);
        private final CountDownLatch firstAddReturned = new CountDownLatch(1);
        private final RedisUserNotificationBus bus;

        private Fixture(Executor callbackExecutor, boolean blockConnection) {
            releaseConnection = new CountDownLatch(blockConnection ? 1 : 0);
            LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
            RedisConnection connection = mock(RedisConnection.class);
            Subscription subscription = mock(Subscription.class);
            when(factory.getConnection()).thenAnswer(call -> {
                connectionEntered.countDown();
                if (!releaseConnection.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test connection not released");
                }
                return connection;
            });
            when(connection.getSubscription()).thenReturn(subscription);
            when(connection.isSubscribed()).thenAnswer(call -> !channels.isEmpty());
            doAnswer(call -> {
                listener.set(call.getArgument(0));
                subscribe((byte[][]) call.getRawArguments()[1]);
                return null;
            }).when(connection).subscribe(any(MessageListener.class), any(byte[][].class));
            doAnswer(call -> {
                subscribe((byte[][]) call.getRawArguments()[0]);
                return null;
            }).when(subscription).subscribe(any(byte[][].class));
            doAnswer(call -> {
                for (byte[] channel : (byte[][]) call.getRawArguments()[0]) {
                    channels.remove(new String(channel, StandardCharsets.UTF_8));
                    ((SubscriptionListener) listener.get()).onChannelUnsubscribed(channel, channels.size());
                }
                return null;
            }).when(subscription).unsubscribe(any(byte[][].class));
            doAnswer(call -> { channels.clear(); return null; }).when(subscription).close();
            AtomicInteger calls = new AtomicInteger();
            doAnswer(call -> {
                int index = calls.incrementAndGet();
                if (index == 2) {
                    secondAddEntered.countDown();
                }
                call.callRealMethod();
                if (index == 1) {
                    firstAddReturned.countDown();
                }
                return null;
            }).when(container).addMessageListener(any(MessageListener.class), any(Topic.class));
            container.setConnectionFactory(factory);
            container.setTaskExecutor(callbackExecutor);
            container.setSubscriptionExecutor(Runnable::run);
            bus = new RedisUserNotificationBus(mock(StringRedisTemplate.class), new ObjectMapper(), keys, container);
            bus.start();
        }

        private void subscribe(byte[][] requested) {
            for (byte[] channel : requested) {
                channels.add(new String(channel, StandardCharsets.UTF_8));
                ((SubscriptionListener) listener.get()).onChannelSubscribed(channel, channels.size());
            }
        }

        private String channel(UserNotificationRecipient recipient) {
            return keys.userNotificationChannel(recipient.tenantId(), recipient.userId());
        }

        private void deliver(UserNotificationRecipient recipient) {
            String channel = channel(recipient);
            if (channels.contains(channel)) {
                Message message = mock(Message.class);
                when(message.getChannel()).thenReturn(bytes(channel));
                when(message.getBody()).thenReturn(bytes("{\"type\":\"test\",\"data\":{}}"));
                listener.get().onMessage(message, null);
            }
        }
    }
}
