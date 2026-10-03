/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.domain.notification.UserNotification;
import com.huawei.it.ex.one.domain.notification.UserNotificationRecipient;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.Disposable;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

class RedisUserNotificationBusTest {
    private final UserNotificationRecipient owner = new UserNotificationRecipient("tenant", "user");
    private final Map<MessageListener, String> broker = new ConcurrentHashMap<>();
    private final List<Fixture> fixtures = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void stop() {
        fixtures.forEach(fixture -> fixture.bus.stop());
    }

    @Test
    void sharedListenerFansOutAcrossSimulatedInstancesAndIsolatesOwners() throws Exception {
        Fixture first = fixture("sit");
        Fixture second = fixture("sit");
        Fixture otherEnvironment = fixture("prod");
        List<UserNotification> received = new CopyOnWriteArrayList<>();
        List<UserNotification> isolated = new CopyOnWriteArrayList<>();
        Disposable one = first.bus.subscribe(owner, received::add).block(Duration.ofSeconds(3));
        Disposable two = first.bus.subscribe(owner, received::add).block(Duration.ofSeconds(3));
        Disposable remote = second.bus.subscribe(owner, received::add).block(Duration.ofSeconds(3));
        Disposable tenant = first.bus.subscribe(new UserNotificationRecipient("other", "user"), isolated::add)
                .block(Duration.ofSeconds(3));
        Disposable user = first.bus.subscribe(new UserNotificationRecipient("tenant", "other"), isolated::add)
                .block(Duration.ofSeconds(3));
        Disposable env = otherEnvironment.bus.subscribe(owner, isolated::add).block(Duration.ofSeconds(3));
        verify(first.container, times(3)).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        first.bus.publish(owner, mapper.writeValueAsString(
                new UserNotification("session.title.updated", Map.of("sessionId", "s"))));
        assertThat(received).hasSize(3);
        assertThat(isolated).isEmpty();
        one.dispose();
        assertThat(broker.values().stream().filter(first.channel(owner)::equals).count()).isEqualTo(2);
        two.dispose();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(broker.values().stream().filter(first.channel(owner)::equals).count()).isEqualTo(1));
        remote.dispose();
        tenant.dispose();
        user.dispose();
        env.dispose();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(broker).isEmpty());
    }

    @Test
    void subscriptionWaitsForRegistrationAndLateRegistrationAfterCancelIsRemoved() throws Exception {
        Fixture fixture = fixture("sit");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            release.await(4, TimeUnit.SECONDS);
            broker.put(call.getArgument(0), call.getArgument(1, ChannelTopic.class).getTopic());
            return null;
        }).when(fixture.container).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        AtomicReference<Disposable> acquired = new AtomicReference<>();
        Disposable pending = fixture.bus.subscribe(owner, ignored -> { }).subscribe(acquired::set);
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(acquired).hasValue(null);
            pending.dispose();
        } finally {
            release.countDown();
        }
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                verify(fixture.container, org.mockito.Mockito.atLeastOnce())
                        .removeMessageListener(any(MessageListener.class), any(ChannelTopic.class)));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(broker).isEmpty());
    }

    @Test
    void registrationTimeoutAllowsRetryAndBoundsConcurrentRegistration() throws Exception {
        Fixture fixture = fixture("sit");
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            release.await(4, TimeUnit.SECONDS);
            return null;
        }).when(fixture.container).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        List<Disposable> pending = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                pending.add(fixture.bus.subscribe(new UserNotificationRecipient("tenant", "user" + i),
                        ignored -> { }).subscribe(ignored -> { }, ignored -> { }));
            }
            StepVerifier.create(fixture.bus.subscribe(owner, ignored -> { }))
                    .expectError(IllegalStateException.class).verify(Duration.ofSeconds(1));
            StepVerifier.create(fixture.bus.subscribe(new UserNotificationRecipient("tenant", "user0"),
                            ignored -> { }))
                    .expectError(java.util.concurrent.TimeoutException.class).verify(Duration.ofSeconds(3));
        } finally {
            release.countDown();
            pending.forEach(Disposable::dispose);
        }
    }

    @Test
    void wrongChannelAndLateAckCannotConfirmFailedOrReplacementRegistration() throws Exception {
        Fixture fixture = fixture("sit");
        List<MessageListener> added = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            MessageListener listener = call.getArgument(0);
            broker.put(listener, call.getArgument(1, ChannelTopic.class).getTopic());
            added.add(listener);
            return null;
        }).when(fixture.container).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        CompletableFuture<Disposable> expired = fixture.bus.subscribe(owner, ignored -> { }).toFuture();
        await().atMost(Duration.ofSeconds(1)).until(() -> added.size() == 1);
        ((SubscriptionListener) added.getFirst()).onChannelSubscribed("wrong".getBytes(StandardCharsets.UTF_8), 1);
        assertThat(expired).isNotDone();
        assertThatThrownBy(() -> expired.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(TimeoutException.class);
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(broker).isEmpty());

        CompletableFuture<Disposable> retry = fixture.bus.subscribe(owner, ignored -> { }).toFuture();
        await().atMost(Duration.ofSeconds(1)).until(() -> added.size() == 2);
        byte[] channel = fixture.channel(owner).getBytes(StandardCharsets.UTF_8);
        ((SubscriptionListener) added.getFirst()).onChannelSubscribed(channel, 1);
        assertThat(retry).isNotDone();
        ((SubscriptionListener) added.getLast()).onChannelSubscribed(channel, 1);
        retry.get(1, TimeUnit.SECONDS).dispose();
    }

    @Test
    void channelAckBeforeAddReturnsDoesNotMaskRegistrationFailure() {
        Fixture fixture = fixture("sit");
        doAnswer(call -> {
            ((SubscriptionListener) call.getArgument(0)).onChannelSubscribed(
                    fixture.channel(owner).getBytes(StandardCharsets.UTF_8), 1);
            throw new IllegalStateException("registration failed after ack");
        }).when(fixture.container).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        StepVerifier.create(fixture.bus.subscribe(owner, ignored -> { }))
                .expectErrorMessage("registration failed after ack").verify(Duration.ofSeconds(1));
    }

    @Test
    void expiredWaiterNeverRegistersAfterTheRegistrationLockIsReleased() throws Exception {
        Fixture fixture = fixture("sit");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(fixture.container).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        CompletableFuture<Disposable> first = fixture.bus.subscribe(owner, ignored -> { }).toFuture();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Disposable> waiting = fixture.bus.subscribe(
                new UserNotificationRecipient("tenant", "waiting"), ignored -> { }).toFuture();
        try {
            assertThatThrownBy(() -> first.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> waiting.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(TimeoutException.class);
        } finally {
            release.countDown();
        }
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                verify(fixture.container, org.mockito.Mockito.atLeastOnce())
                        .removeMessageListener(any(MessageListener.class), any(ChannelTopic.class)));
        verify(fixture.container, times(1)).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
    }

    @Test
    void confirmedSharedListenerCanBeReusedAfterItsInitialRegistrationDeadline() throws Exception {
        Fixture fixture = fixture("sit");
        Disposable first = fixture.bus.subscribe(owner, ignored -> { }).block(Duration.ofSeconds(1));
        CountDownLatch elapsed = new CountDownLatch(1);
        reactor.core.scheduler.Schedulers.parallel().schedule(elapsed::countDown, 2100, TimeUnit.MILLISECONDS);
        assertThat(elapsed.await(3, TimeUnit.SECONDS)).isTrue();
        Disposable second = fixture.bus.subscribe(owner, ignored -> { }).block(Duration.ofSeconds(1));
        verify(fixture.container, times(1)).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        first.dispose();
        second.dispose();
    }

    @Test
    void malformedOversizedAndWrongChannelMessagesAreDroppedAndConsumerFailureIsIsolated() throws Exception {
        Fixture fixture = fixture("sit");
        List<UserNotification> received = new ArrayList<>();
        Disposable broken = fixture.bus.subscribe(owner, ignored -> { throw new IllegalStateException(); })
                .block(Duration.ofSeconds(3));
        Disposable healthy = fixture.bus.subscribe(owner, received::add).block(Duration.ofSeconds(3));
        MessageListener listener = broker.keySet().iterator().next();
        listener.onMessage(message(fixture.channel(owner), "{bad"), null);
        listener.onMessage(message(fixture.channel(owner), "x".repeat(16385)), null);
        listener.onMessage(message("wrong-channel", "{\"type\":\"test\",\"data\":{}}"), null);
        fixture.bus.publish(owner, "{\"type\":\"test\",\"data\":{}}");
        assertThat(received).hasSize(1);
        broken.dispose();
        healthy.dispose();
    }

    @Test
    void channelIdentityEncodingDoesNotCollideOnSeparators() {
        FinanceExRedisKeyBuilder keys = FinanceExRedisKeyBuilder.ofEnv("sit");
        assertThat(keys.userNotificationChannel("a:b", "c"))
                .isNotEqualTo(keys.userNotificationChannel("a", "b:c"));
        assertThat(keys.userNotificationChannel("a", "b"))
                .isNotEqualTo(FinanceExRedisKeyBuilder.ofEnv("prod").userNotificationChannel("a", "b"));
    }

    private Fixture fixture(String environment) {
        Fixture fixture = new Fixture(environment);
        fixtures.add(fixture);
        return fixture;
    }

    private Message message(String channel, String body) {
        Message message = mock(Message.class);
        when(message.getChannel()).thenReturn(channel.getBytes(StandardCharsets.UTF_8));
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    private final class Fixture {
        private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        private final FinanceExRedisKeyBuilder keys;
        private final RedisUserNotificationBus bus;

        private Fixture(String environment) {
            keys = FinanceExRedisKeyBuilder.ofEnv(environment);
            bus = new RedisUserNotificationBus(redis, mapper, keys, container);
            doAnswer(call -> {
                broker.put(call.getArgument(0), call.getArgument(1, ChannelTopic.class).getTopic());
                ((SubscriptionListener) call.getArgument(0)).onChannelSubscribed(
                        call.getArgument(1, ChannelTopic.class).getTopic().getBytes(StandardCharsets.UTF_8), 1);
                return null;
            }).when(container).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
            doAnswer(call -> {
                broker.remove(call.getArgument(0));
                return null;
            }).when(container).removeMessageListener(any(MessageListener.class), any(ChannelTopic.class));
            when(redis.convertAndSend(anyString(), any())).thenAnswer(call -> {
                String channel = call.getArgument(0);
                broker.forEach((listener, topic) -> {
                    if (topic.equals(channel)) {
                        listener.onMessage(message(channel, call.getArgument(1)), null);
                    }
                });
                return 1L;
            });
        }

        private String channel(UserNotificationRecipient recipient) {
            return keys.userNotificationChannel(recipient.tenantId(), recipient.userId());
        }
    }
}
