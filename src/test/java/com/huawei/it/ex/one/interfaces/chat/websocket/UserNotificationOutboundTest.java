/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.notification.UserNotification;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.Disposable;
import reactor.core.Scannable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

class UserNotificationOutboundTest {
    private final UserContext user = new UserContext("tenant1", "user1", "User");
    private final ChatWebSocketEnvelopeDto notice = ChatWebSocketEnvelopeDto.notification(
            new UserNotification("session.title.updated", Map.of("sessionId", "s1")));

    @Test
    void webfluxNotificationPrefetchIsBoundedAndDoesNotConsumeRunQueueSlots() {
        try (WebFluxFixture fixture = new WebFluxFixture()) {
            List<WebSocketMessage> received = new ArrayList<>();
            BaseSubscriber<WebSocketMessage> subscriber = new BaseSubscriber<>() {
                @Override
                protected void hookOnSubscribe(Subscription subscription) {
                    // 模拟socket暂时没有发送需求，随后再显式请求。
                }

                @Override
                protected void hookOnNext(WebSocketMessage message) {
                    received.add(message);
                }
            };
            fixture.output.get().subscribe(subscriber);
            for (int i = 0; i < 1000; i++) {
                fixture.sender.get().emit(notice);
            }
            assertThat(fixture.allocated).hasSize(1000);
            fixture.allocated.subList(1, 1000).forEach(message -> verify(message).release());
            verify(fixture.allocated.getFirst(), org.mockito.Mockito.never()).release();
            fixture.sender.get().emit(ChatWebSocketEnvelopeDto.reply("run1", Map.of()));
            fixture.sender.get().emit(ChatWebSocketEnvelopeDto.reply("run2", Map.of()));
            subscriber.request(3);
            assertThat(received).hasSize(3).contains(fixture.allocated.getFirst())
                    .containsSubsequence(fixture.allocated.get(1000), fixture.allocated.get(1001));
            received.forEach(WebSocketMessage::release);
            fixture.close();
            assertThat(subscriber.isDisposed()).isTrue();
            verify(fixture.session, org.mockito.Mockito.never()).close(any());
        }
    }

    @Test
    void webfluxDisconnectDiscardsBothBranchesAndRejectsLateOutput() {
        try (WebFluxFixture fixture = new WebFluxFixture()) {
            // send 尚未消费输出流时通知直接丢弃，不暂存离线通知。
            fixture.sender.get().emit(notice);
            verify(fixture.allocated.getFirst()).release();
            StepVerifier.create(fixture.output.get(), 0)
                    .then(() -> {
                        fixture.sender.get().emit(notice);
                        fixture.sender.get().emit(ChatWebSocketEnvelopeDto.reply("run1", Map.of()));
                        fixture.sender.get().emit(ChatWebSocketEnvelopeDto.reply("run2", Map.of()));
                    })
                    .then(fixture::close)
                    .expectComplete().verify(Duration.ofSeconds(3));
            fixture.allocated.forEach(message -> verify(message).release());
            int before = fixture.allocated.size();
            fixture.sender.get().emit(notice);
            fixture.sender.get().emit(ChatWebSocketEnvelopeDto.reply("late", Map.of()));
            assertThat(fixture.allocated).hasSize(before);
        }
    }

    @Test
    void webfluxRealRunOverflowStillClosesAndReleasesPendingMessages() {
        try (WebFluxFixture fixture = new WebFluxFixture()) {
            StepVerifier.create(fixture.output.get(), 0)
                    .then(() -> {
                        // 一条合流预取 + 原16条Run队列；第18条仍必须触发真实溢出保护。
                        for (int i = 0; i < 18; i++) {
                            fixture.sender.get().emit(ChatWebSocketEnvelopeDto.reply("run" + i, Map.of()));
                        }
                    })
                    .expectComplete().verify(Duration.ofSeconds(3));
            verify(fixture.session).close(org.springframework.web.reactive.socket.CloseStatus.SERVICE_OVERLOAD);
            fixture.allocated.forEach(message -> verify(message).release());
        }
    }

    @Test
    void servletDropsBusyAndRejectedNotificationsWithoutClosingRunConnection() throws Exception {
        ChatWebSocketProtocolService protocol = mock(ChatWebSocketProtocolService.class);
        List<Runnable> scheduled = new ArrayList<>();
        ChatServletWebSocketHandler handler = new ChatServletWebSocketHandler(
                protocol, new ObjectMapper(), new ChatWebSocketProperties(), scheduled::add);
        org.springframework.web.socket.WebSocketSession session = servletSession();
        handler.afterConnectionEstablished(session);
        emitServlet(handler, notice);
        emitServlet(handler, notice);
        assertThat(scheduled).hasSize(1);
        assertThat(servletQueue(handler).snapshot().queueSize()).isEqualTo(1);
        assertThat(servletQueue(handler).poll().notification()).isTrue();

        ChatServletWebSocketHandler rejected = new ChatServletWebSocketHandler(
                protocol, new ObjectMapper(), new ChatWebSocketProperties(), task -> {
                    throw new RejectedExecutionException("busy");
                });
        rejected.afterConnectionEstablished(session);
        emitServlet(rejected, notice);
        assertThat(servletQueue(rejected).snapshot().queueSize()).isZero();
        assertThat(servletConnections(rejected)).hasSize(1);
        // 连接未被销毁，后续 Run 仍可使用原连接；仅没有得到调度的通知被丢弃。
        verify(protocol, org.mockito.Mockito.never()).close(any(), any());
        verify(session, org.mockito.Mockito.never()).close(any());
    }

    @Test
    void servletSerializationFailureDoesNotCloseConnectionOrScheduleSend() throws Exception {
        ChatWebSocketProtocolService protocol = mock(ChatWebSocketProtocolService.class);
        List<Runnable> scheduled = new ArrayList<>();
        ChatServletWebSocketHandler handler = new ChatServletWebSocketHandler(
                protocol, new ObjectMapper(), new ChatWebSocketProperties(), scheduled::add);
        handler.afterConnectionEstablished(servletSession());
        emitServlet(handler, invalidNotice());
        assertThat(scheduled).isEmpty();
        assertThat(servletConnections(handler)).hasSize(1);
        verify(protocol, org.mockito.Mockito.never()).close(any(), any());
    }

    @Test
    void webfluxSkipsBacklogAndReleasesRacingRejectedNotificationWithoutClosing() {
        ChatWebSocketProtocolService protocol = mock(ChatWebSocketProtocolService.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(
                protocol, null, new ObjectMapper(), new ChatWebSocketProperties());
        WebSocketSession session = mock(WebSocketSession.class);
        @SuppressWarnings("unchecked")
        Sinks.Many<WebSocketMessage> sink = mock(Sinks.Many.class);
        @SuppressWarnings("unchecked")
        Sinks.Many<WebSocketMessage> notifications = mock(Sinks.Many.class);
        Sinks.Empty<Void> closed = Sinks.empty();
        var outbound = new ChatWebSocketHandler.OutboundStreams(sink, notifications, closed);
        when(sink.scan(Scannable.Attr.BUFFERED)).thenReturn(1);
        ReflectionTestUtils.invokeMethod(handler, "emit", session, outbound, user, notice);
        verifyNoInteractions(session, protocol);

        when(sink.scan(Scannable.Attr.BUFFERED)).thenReturn(0);
        WebSocketMessage message = mock(WebSocketMessage.class);
        when(session.textMessage(any())).thenReturn(message);
        when(notifications.tryEmitNext(message)).thenReturn(Sinks.EmitResult.FAIL_OVERFLOW);
        ReflectionTestUtils.invokeMethod(handler, "emit", session, outbound, user, notice);
        verify(message).release();
        verify(sink, org.mockito.Mockito.never()).tryEmitNext(any());
        verify(session, org.mockito.Mockito.never()).close(any());
        verifyNoInteractions(protocol);
    }

    @Test
    void webfluxSerializationFailureDoesNotTouchExistingConnection() {
        ChatWebSocketProtocolService protocol = mock(ChatWebSocketProtocolService.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(
                protocol, null, new ObjectMapper(), new ChatWebSocketProperties());
        WebSocketSession session = mock(WebSocketSession.class);
        Sinks.Many<WebSocketMessage> sink = Sinks.many().unicast().onBackpressureBuffer();
        ReflectionTestUtils.invokeMethod(handler, "emit", session,
                new ChatWebSocketHandler.OutboundStreams(sink, Sinks.many().multicast().directBestEffort(), Sinks.empty()),
                user, invalidNotice());
        assertThat(sink.scan(Scannable.Attr.BUFFERED)).isZero();
        verifyNoInteractions(session, protocol);
    }

    private ChatWebSocketEnvelopeDto invalidNotice() {
        return ChatWebSocketEnvelopeDto.notification(new UserNotification("example", Map.of("bad", new Object())));
    }

    private org.springframework.web.socket.WebSocketSession servletSession() {
        org.springframework.web.socket.WebSocketSession session = mock(org.springframework.web.socket.WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        ChatWebSocketUserContextAttributes.put(attributes, user);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn("conn1");
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private Map<?, ?> servletConnections(ChatServletWebSocketHandler handler) {
        return (Map<?, ?>) ReflectionTestUtils.getField(handler, "connections");
    }

    private ServletWebSocketOutboundQueue servletQueue(ChatServletWebSocketHandler handler) {
        return (ServletWebSocketOutboundQueue) ReflectionTestUtils.invokeMethod(
                servletConnections(handler).get("conn1"), "outbound");
    }

    private void emitServlet(ChatServletWebSocketHandler handler, ChatWebSocketEnvelopeDto dto) {
        ReflectionTestUtils.invokeMethod(handler, "emit", "conn1", servletConnections(handler).get("conn1"), dto);
    }

    private final class WebFluxFixture implements AutoCloseable {
        private final WebSocketSession session = mock(WebSocketSession.class);
        private final AtomicReference<ChatWebSocketOutbound> sender = new AtomicReference<>();
        private final AtomicReference<Flux<WebSocketMessage>> output = new AtomicReference<>();
        private final List<WebSocketMessage> allocated = new ArrayList<>();
        private final Disposable running;

        private WebFluxFixture() {
            ChatWebSocketProtocolService protocol = mock(ChatWebSocketProtocolService.class);
            when(protocol.handleTextMessage(any(), any(), any(), any())).thenAnswer(call -> {
                sender.set(call.getArgument(2));
                return Mono.empty();
            });
            HandshakeInfo handshake = mock(HandshakeInfo.class);
            when(handshake.getHeaders()).thenReturn(HttpHeaders.EMPTY);
            when(session.getHandshakeInfo()).thenReturn(handshake);
            when(session.getId()).thenReturn("conn1");
            Sinks.Many<WebSocketMessage> input = Sinks.many().unicast().onBackpressureBuffer();
            when(session.receive()).thenReturn(input.asFlux());
            when(session.textMessage(any())).thenAnswer(call -> {
                WebSocketMessage message = mock(WebSocketMessage.class);
                allocated.add(message);
                return message;
            });
            when(session.close(any())).thenReturn(Mono.empty());
            when(session.send(any())).thenAnswer(call -> {
                output.set(Flux.from(call.getArgument(0)));
                return Mono.never();
            });
            ChatWebSocketProperties properties = new ChatWebSocketProperties();
            properties.setOutboundQueueSize(16);
            ChatWebSocketHandler handler = new ChatWebSocketHandler(protocol, () -> user, new ObjectMapper(), properties);
            running = handler.handle(session).subscribe();
            input.tryEmitNext(new WebSocketMessage(WebSocketMessage.Type.TEXT,
                    DefaultDataBufferFactory.sharedInstance.wrap(new byte[] {'{', '}'}))).orThrow();
        }

        @Override
        public void close() {
            running.dispose();
        }
    }
}
