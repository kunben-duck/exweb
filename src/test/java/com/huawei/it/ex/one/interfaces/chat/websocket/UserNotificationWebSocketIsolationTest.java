/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.application.integration.notification.UserNotificationBus;
import com.huawei.it.ex.one.application.service.chat.ChatStreamApplicationService;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;
import com.huawei.it.ex.one.domain.notification.UserNotification;
import com.huawei.it.ex.one.interfaces.chat.ChatEventTranslator;
import com.huawei.it.ex.one.interfaces.chat.ChatTurnStreamTranslator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.socket.TextMessage;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

class UserNotificationWebSocketIsolationTest {
    private static final String NOTIFICATIONS = "{\"id\":\"notices\",\"type\":\"subscribe-user-notifications\"}";
    private static final String RUN = "{\"id\":\"run\",\"type\":\"subscribe\",\"topicId\":\"chat-run-run1\"}";
    private static final String PRESENCE = "{\"id\":\"presence\",\"type\":\"presence\",\"state\":\"foreground\"}";

    @Test
    void webfluxNotificationInFlightCannotCloseConcurrentRunOutput() throws Exception {
        Fixture fixture = new Fixture();
        ChatWebSocketHandler handler = new ChatWebSocketHandler(
                fixture.protocol, () -> fixture.user, fixture.mapper, new ChatWebSocketProperties());
        org.springframework.web.reactive.socket.WebSocketSession session =
                mock(org.springframework.web.reactive.socket.WebSocketSession.class);
        HandshakeInfo handshake = mock(HandshakeInfo.class);
        when(handshake.getHeaders()).thenReturn(HttpHeaders.EMPTY);
        when(session.getHandshakeInfo()).thenReturn(handshake);
        when(session.getId()).thenReturn("connection");
        Sinks.Many<WebSocketMessage> input = Sinks.many().unicast().onBackpressureBuffer();
        when(session.receive()).thenReturn(input.asFlux());
        when(session.textMessage(any())).thenAnswer(call -> text(call.getArgument(0)));
        when(session.close(any())).thenReturn(Mono.empty());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(session.send(any())).thenAnswer(call -> Flux.from(call.<Publisher<WebSocketMessage>>getArgument(0))
                .doOnNext(message -> {
                    if (message.getPayloadAsText().contains("session.title.updated")) {
                        entered.countDown();
                        try {
                            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(ex);
                        }
                    }
                    fixture.record(message.getPayloadAsText());
                }).then());
        Disposable connection = handler.handle(session).subscribe();
        Thread notification = new Thread(() -> fixture.notificationConsumer.get().accept(
                new UserNotification("session.title.updated", Map.of("sessionId", "session1"))));
        try {
            input.tryEmitNext(text(RUN)).orThrow();
            fixture.awaitRunSubscription();
            input.tryEmitNext(text(NOTIFICATIONS)).orThrow();
            fixture.pending.tryEmitValue(() -> { }).orThrow();
            notification.start();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            fixture.send(1, "message.delta");
            // 不是只验证通知被丢弃：通知先占用发送过程时，Run 必须仍然可发送。
            verify(session, never()).close(any());
            assertThat(fixture.registry.get("connection")).isPresent();
            fixture.send(2, "run.completed");
            release.countDown();
            notification.join(2000);
            await().atMost(Duration.ofSeconds(1)).until(() -> fixture.hasEvent("run.completed"));
            assertThat(fixture.output.stream().filter(node -> "message".equals(node.path("type").asText())))
                    .extracting(node -> node.path("payload").path("payload").path("encodedItem").path("event").asText())
                    .containsSubsequence("message.delta", "run.completed");
            verify(session, never()).close(any());
        } finally {
            release.countDown();
            notification.join(2000);
            connection.dispose();
        }
    }

    @Test
    void servletContinuesControlAndRunOutputWhileNotificationRegistrationWaitsAndFails() throws Exception {
        Fixture fixture = new Fixture();
        ChatServletWebSocketHandler handler = new ChatServletWebSocketHandler(
                fixture.protocol, fixture.mapper, new ChatWebSocketProperties(), Runnable::run);
        org.springframework.web.socket.WebSocketSession session =
                mock(org.springframework.web.socket.WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        ChatWebSocketUserContextAttributes.put(attributes, fixture.user);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn("connection");
        when(session.isOpen()).thenReturn(true);
        doAnswer(call -> {
            fixture.record(call.getArgument(0, TextMessage.class).getPayload());
            return null;
        }).when(session).sendMessage(any());
        handler.afterConnectionEstablished(session);
        try {
            handler.handleTextMessage(session, new TextMessage(NOTIFICATIONS));
            handler.handleTextMessage(session, new TextMessage(PRESENCE));
            handler.handleTextMessage(session, new TextMessage(RUN));
            fixture.awaitRunSubscription();
            assertThat(fixture.hasReply("presence")).isTrue();
            assertThat(fixture.hasReply("notices")).isFalse();
            fixture.send(1, "message.delta");
            assertThat(fixture.hasEvent("message.delta")).isTrue();
            fixture.pending.tryEmitError(new TimeoutException("ack timeout"));
            fixture.send(2, "run.completed");
            fixture.assertCompletedWithoutConnectionLoss();
            verify(session, never()).close(any());
        } finally {
            handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);
        }
    }

    @Test
    void webfluxKeepsExistingRunOutputAndResumesQueuedCommandsAfterNotificationFailure() {
        Fixture fixture = new Fixture();
        ChatWebSocketHandler handler = new ChatWebSocketHandler(
                fixture.protocol, () -> fixture.user, fixture.mapper, new ChatWebSocketProperties());
        org.springframework.web.reactive.socket.WebSocketSession session =
                mock(org.springframework.web.reactive.socket.WebSocketSession.class);
        HandshakeInfo handshake = mock(HandshakeInfo.class);
        when(handshake.getHeaders()).thenReturn(HttpHeaders.EMPTY);
        when(session.getHandshakeInfo()).thenReturn(handshake);
        when(session.getId()).thenReturn("connection");
        Sinks.Many<WebSocketMessage> input = Sinks.many().unicast().onBackpressureBuffer();
        when(session.receive()).thenReturn(input.asFlux());
        when(session.textMessage(any())).thenAnswer(call -> text(call.getArgument(0)));
        when(session.send(any())).thenAnswer(call -> Flux.from(call.<Publisher<WebSocketMessage>>getArgument(0))
                .doOnNext(message -> fixture.record(message.getPayloadAsText())).then());
        Disposable connection = handler.handle(session).subscribe();
        try {
            input.tryEmitNext(text(RUN)).orThrow();
            fixture.awaitRunSubscription();
            input.tryEmitNext(text(NOTIFICATIONS)).orThrow();
            input.tryEmitNext(text(PRESENCE)).orThrow();
            assertThat(fixture.hasReply("presence")).isFalse();
            fixture.send(1, "message.delta");
            assertThat(fixture.hasEvent("message.delta")).isTrue();
            fixture.pending.tryEmitError(new TimeoutException("ack timeout"));
            assertThat(fixture.hasReply("presence")).isTrue();
            fixture.send(2, "run.completed");
            fixture.assertCompletedWithoutConnectionLoss();
            verify(session, never()).close(any());
        } finally {
            connection.dispose();
        }
    }

    private static WebSocketMessage text(String json) {
        return new WebSocketMessage(WebSocketMessage.Type.TEXT,
                DefaultDataBufferFactory.sharedInstance.wrap(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class Fixture {
        private final UserContext user = new UserContext("tenant", "user", "name");
        private final ObjectMapper mapper = new ObjectMapper();
        private final LocalWebSocketConnectionRegistry registry = new LocalWebSocketConnectionRegistry();
        private final Sinks.One<Disposable> pending = Sinks.one();
        private final Sinks.Many<ChatEvent> live = Sinks.many().unicast().onBackpressureBuffer();
        private final List<JsonNode> output = new CopyOnWriteArrayList<>();
        private final AtomicReference<Consumer<UserNotification>> notificationConsumer = new AtomicReference<>();
        private final ChatWebSocketProtocolService protocol;

        private Fixture() {
            ChatStreamApplicationService stream = mock(ChatStreamApplicationService.class);
            ChatRun run = mock(ChatRun.class);
            when(run.id()).thenReturn("run1");
            when(run.sessionId()).thenReturn("session1");
            when(stream.ensureRunTopicAccessible(user, "chat-run-run1")).thenReturn(run);
            when(stream.resumeRunTopic(user, "chat-run-run1", 0)).thenReturn(live.asFlux());
            protocol = new ChatWebSocketProtocolService(new PermissionChecker(), stream, registry,
                    new ChatEventTranslator(), new ChatTurnStreamTranslator(), new ChatStreamProperties(), mapper);
            UserNotificationBus bus = mock(UserNotificationBus.class);
            when(bus.subscribe(any(), any())).thenAnswer(call -> {
                notificationConsumer.set(call.getArgument(1));
                return pending.asMono();
            });
            protocol.setNotificationBus(bus);
        }

        private void awaitRunSubscription() {
            await().atMost(Duration.ofSeconds(1)).until(() -> live.currentSubscriberCount() == 1);
        }

        private void record(String json) {
            try {
                output.add(mapper.readTree(json));
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        }

        private void send(long sequence, String type) {
            live.tryEmitNext(new StoredChatEvent("run1", "session1", sequence, type, Instant.now(),
                    Map.of("delta", "answer"))).orThrow();
        }

        private boolean hasReply(String id) {
            return output.stream().anyMatch(node -> "reply".equals(node.path("type").asText())
                    && id.equals(node.path("id").asText()));
        }

        private boolean hasEvent(String type) {
            return output.stream().anyMatch(node -> type.equals(node.path("payload").path("payload")
                    .path("encodedItem").path("event").asText()));
        }

        private void assertCompletedWithoutConnectionLoss() {
            assertThat(hasEvent("run.completed")).isTrue();
            assertThat(output).anyMatch(node -> "done".equals(node.path("payload").path("payload").path("type").asText()));
            assertThat(output).anyMatch(node -> "USER_NOTIFICATIONS_UNAVAILABLE".equals(node.path("code").asText()));
            assertThat(hasReply("notices")).isFalse();
            assertThat(registry.get("connection")).isPresent();
        }
    }
}
