/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.integration.notification.UserNotificationBus;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.notification.UserNotification;
import com.huawei.it.ex.one.domain.notification.UserNotificationRecipient;
import com.huawei.it.ex.one.interfaces.chat.ChatEventTranslator;
import com.huawei.it.ex.one.interfaces.chat.ChatTurnStreamTranslator;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

class UserNotificationProtocolTest {
    private final UserContext user = new UserContext("tenant", "owner", "name");
    private final ObjectMapper mapper = new ObjectMapper();
    private final LocalWebSocketConnectionRegistry registry = new LocalWebSocketConnectionRegistry();
    private final UserNotificationBus bus = mock(UserNotificationBus.class);
    private final ChatWebSocketProtocolService protocol = new ChatWebSocketProtocolService(
            new PermissionChecker(), null, registry, new ChatEventTranslator(),
            new ChatTurnStreamTranslator(), new ChatStreamProperties(), mapper);
    private final List<ChatWebSocketEnvelopeDto> received = new ArrayList<>();

    @Test
    void idempotentSubscriptionUsesTrustedOwnerAndSurvivesRunUnsubscribe() throws Exception {
        AtomicReference<Consumer<UserNotification>> consumer = new AtomicReference<>();
        Disposable listener = Disposables.single();
        when(bus.subscribe(any(), any())).thenAnswer(call -> {
            assertThat(call.getArgument(0, UserNotificationRecipient.class))
                    .isEqualTo(new UserNotificationRecipient(user.tenantId(), user.ownerUserId()));
            consumer.set(call.getArgument(1));
            return Mono.just(listener);
        });
        open();
        for (int i = 0; i < 8; i++) {
            registry.subscribe("connection", "topic" + i, "session" + i, 0, Disposables.single());
        }
        command("{\"id\":\"one\",\"type\":\"subscribe-user-notifications\",\"userId\":\"attacker\"}").block();
        command("{\"id\":\"two\",\"type\":\"subscribe-user-notifications\"}").block();
        verify(bus, times(1)).subscribe(any(), any());
        assertThat(registry.get("connection").orElseThrow().subscriptionCount()).isEqualTo(8);
        registry.unsubscribe("connection", "topic0");
        consumer.get().accept(new UserNotification("session.title.updated", Map.of("sessionId", "s1")));
        assertThat(received).extracting(ChatWebSocketEnvelopeDto::type).containsExactly("reply", "reply", "notification");
        assertThat(received.get(0).id()).isEqualTo("one");
        assertThat(received.get(2).topicId()).isNull();
        assertThat(mapper.readTree(mapper.writeValueAsString(received.get(0))).has("notification")).isFalse();
        command("{\"id\":\"three\",\"type\":\"unsubscribe-user-notifications\"}").block();
        assertThat(listener.isDisposed()).isTrue();
        int count = received.size();
        consumer.get().accept(new UserNotification("session.title.updated", Map.of("sessionId", "s1")));
        assertThat(received).hasSize(count);
    }

    @Test
    void waitsForRegistrationAndCloseCancelsPendingListener() {
        Sinks.One<Disposable> pending = Sinks.one();
        when(bus.subscribe(any(), any())).thenReturn(pending.asMono());
        open();
        Disposable request = command("{\"id\":\"one\",\"type\":\"subscribe-user-notifications\"}").subscribe();
        assertThat(received).isEmpty();
        Disposable listener = Disposables.single();
        pending.tryEmitValue(listener);
        assertThat(received).hasSize(1);
        protocol.close("connection", user);
        assertThat(listener.isDisposed()).isTrue();
        request.dispose();
    }

    @Test
    void closeDuringRegistrationCancelsBusSubscriptionAndCannotLeakReply() {
        AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
        when(bus.subscribe(any(), any())).thenReturn(Mono.<Disposable>never()
                .doOnCancel(() -> cancelled.set(true)));
        open();
        command("{\"id\":\"one\",\"type\":\"subscribe-user-notifications\"}").subscribe();
        protocol.close("connection", user);
        assertThat(cancelled.get()).isTrue();
        assertThat(received).isEmpty();
    }

    @Test
    void failedSubscriptionDoesNotCloseRunTopicsAndCanRetry() {
        when(bus.subscribe(any(), any())).thenReturn(Mono.error(new IllegalStateException("redis down")));
        open();
        Disposable run = Disposables.single();
        registry.subscribe("connection", "run-topic", "session", 0, run);
        command("{\"id\":\"one\",\"type\":\"subscribe-user-notifications\"}").block();
        assertThat(received.getFirst().code()).isEqualTo("USER_NOTIFICATIONS_UNAVAILABLE");
        assertThat(run.isDisposed()).isFalse();
        when(bus.subscribe(any(), any())).thenReturn(Mono.just(Disposables.single()));
        command("{\"id\":\"two\",\"type\":\"subscribe-user-notifications\"}").block();
        assertThat(received.getLast().type()).isEqualTo("reply");
        protocol.close("connection", user);
        assertThat(run.isDisposed()).isTrue();
    }

    private void open() {
        protocol.setNotificationBus(bus);
        protocol.open("connection", user);
    }

    private Mono<Void> command(String json) {
        return protocol.handleTextMessage("connection", user, received::add, json);
    }
}
