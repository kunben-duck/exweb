/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.notification.UserNotification;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class ChatWebSocketDrainTest {
    @Test
    void synchronousReceiveFailureStillSendsItsQueuedProtocolError() {
        Fixture fixture = new Fixture(Flux.error(new IllegalStateException("receive failed")));
        StepVerifier.create(fixture.handler.handle(fixture.session)).verifyComplete();
        assertThat(fixture.sent).hasSize(1);
        assertThat(fixture.sent.getFirst()).contains("WS_STREAM_ERROR");
        verify(fixture.session, never()).close(any());
        fixture.assertReleased();
    }

    @Test
    void oversizedInputDrainsExistingErrorResponsesInOrder() {
        Fixture fixture = new Fixture(Flux.just(text("x".repeat(17000))));
        StepVerifier.create(fixture.handler.handle(fixture.session)).verifyComplete();
        assertThat(fixture.sent).hasSize(2);
        assertThat(fixture.sent.get(0)).contains("WS_MESSAGE_TOO_LARGE");
        assertThat(fixture.sent.get(1)).contains("WS_STREAM_ERROR");
        verify(fixture.session, never()).close(any());
        fixture.assertReleased();
    }

    @Test
    void gracefulDrainRetainsRunAndReplyOrderAndRejectsLateOutput() throws Exception {
        Sinks.Many<WebSocketMessage> input = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Empty<Void> startSending = Sinks.empty();
        Fixture fixture = new Fixture(input.asFlux());
        doAnswer(call -> startSending.asMono().then(fixture.consume(Flux.from(call.getArgument(0)))))
                .when(fixture.session).send(any());
        CompletableFuture<Void> running = fixture.handler.handle(fixture.session).toFuture();
        input.tryEmitNext(text("{}")).orThrow();
        fixture.sender.get().emit(ChatWebSocketEnvelopeDto.reply("subscribed", Map.of()));
        fixture.sender.get().emit(ChatWebSocketEnvelopeDto.message("chat-run-run1", null, "1"));
        input.tryEmitError(new IllegalStateException("command failed")).orThrow();
        verify(fixture.protocol).close("drain-test", fixture.user);
        assertThat(running).isNotDone();
        int allocated = fixture.allocated.size();
        fixture.sender.get().emit(ChatWebSocketEnvelopeDto.reply("late", Map.of()));
        fixture.sender.get().emit(ChatWebSocketEnvelopeDto.notification(new UserNotification("test", Map.of())));
        assertThat(fixture.allocated).hasSize(allocated);
        startSending.tryEmitEmpty().orThrow();
        running.get(1, TimeUnit.SECONDS);
        assertThat(fixture.sent).hasSize(3);
        assertThat(fixture.sent.get(0)).contains("subscribed");
        assertThat(fixture.sent.get(1)).contains("chat-run-run1");
        assertThat(fixture.sent.get(2)).contains("WS_STREAM_ERROR");
        fixture.assertReleased();
        verify(fixture.session, never()).close(any());
    }

    @Test
    void drainingWaitsForAlreadyEnteredEmitterButNotForNewOutput() throws Exception {
        Sinks.Many<WebSocketMessage> input = Sinks.many().unicast().onBackpressureBuffer();
        Fixture fixture = new Fixture(input.asFlux());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return fixture.allocate(call.getArgument(0));
        }).when(fixture.session).textMessage(any());
        CompletableFuture<Void> running = fixture.handler.handle(fixture.session).toFuture();
        input.tryEmitNext(text("{}")).orThrow();
        CompletableFuture<Void> emitter = CompletableFuture.runAsync(() ->
                fixture.sender.get().emit(ChatWebSocketEnvelopeDto.reply("in-flight", Map.of())));
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            input.tryEmitComplete().orThrow();
            fixture.sender.get().emit(ChatWebSocketEnvelopeDto.reply("late", Map.of()));
            assertThat(running).isNotDone();
            release.countDown();
            emitter.get(1, TimeUnit.SECONDS);
            running.get(1, TimeUnit.SECONDS);
            assertThat(fixture.sent).hasSize(1);
            assertThat(fixture.sent.getFirst()).contains("in-flight");
            fixture.assertReleased();
            verify(fixture.session, never()).close(any());
        } finally {
            release.countDown();
            running.cancel(false);
            emitter.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void drainTimeoutCancelsASlowSocketAndReleasesTheRemainingQueue() {
        Fixture fixture = new Fixture(Flux.just(text("x".repeat(17000))));
        BaseSubscriber<WebSocketMessage> stalled = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                // socket 不请求任何消息，必须在收尾期限到达后取消并释放队列。
            }
        };
        doAnswer(call -> Mono.<Void>create(sink -> {
            Flux.<WebSocketMessage>from(call.getArgument(0)).subscribe(stalled);
            sink.onCancel(stalled::dispose);
        })).when(fixture.session).send(any());
        StepVerifier.withVirtualTime(() -> fixture.handler.handle(fixture.session))
                .expectSubscription().thenAwait(Duration.ofMillis(2100)).verifyComplete();
        assertThat(stalled.isDisposed()).isTrue();
        verify(fixture.session).close(CloseStatus.SERVICE_OVERLOAD);
        fixture.assertReleased();
    }

    @Test
    void drainDeadlineIncludesSocketCompletionAfterAllMessagesWereConsumed() {
        Fixture fixture = new Fixture(Flux.error(new IllegalStateException("bad input")));
        doAnswer(call -> fixture.consume(Flux.from(call.getArgument(0))).then(Mono.never()))
                .when(fixture.session).send(any());
        StepVerifier.withVirtualTime(() -> fixture.handler.handle(fixture.session))
                .expectSubscription().thenAwait(Duration.ofMillis(2100)).verifyComplete();
        assertThat(fixture.sent).hasSize(1);
        verify(fixture.session).close(CloseStatus.SERVICE_OVERLOAD);
        fixture.assertReleased();
    }

    @Test
    void normalLongConnectionHasNoDrainDeadline() throws Exception {
        Sinks.Many<WebSocketMessage> input = Sinks.many().unicast().onBackpressureBuffer();
        Fixture fixture = new Fixture(input.asFlux());
        VirtualTimeScheduler clock = VirtualTimeScheduler.getOrSet();
        CompletableFuture<Void> running = fixture.handler.handle(fixture.session).toFuture();
        try {
            clock.advanceTimeBy(Duration.ofMinutes(30));
            assertThat(running).isNotDone();
            verify(fixture.session, never()).close(any());
            input.tryEmitComplete().orThrow();
            running.get(1, TimeUnit.SECONDS);
            verify(fixture.session, never()).close(any());
        } finally {
            running.cancel(false);
            VirtualTimeScheduler.reset();
        }
    }

    @Test
    void idleConnectionCompletesImmediatelyWhenInputEnds() throws Exception {
        Sinks.Many<WebSocketMessage> input = Sinks.many().unicast().onBackpressureBuffer();
        Fixture fixture = new Fixture(input.asFlux());
        CompletableFuture<Void> running = fixture.handler.handle(fixture.session).toFuture();
        try {
            input.tryEmitComplete().orThrow();
            running.get(1, TimeUnit.SECONDS);
            verify(fixture.session, never()).close(any());
        } finally {
            running.cancel(false);
        }
    }

    @Test
    void unavailableSocketAbortsWithoutDrainingQueuedErrors() {
        Fixture fixture = new Fixture(Flux.error(new IllegalStateException("disconnected")));
        when(fixture.session.isOpen()).thenReturn(false);
        StepVerifier.create(fixture.handler.handle(fixture.session)).verifyComplete();
        assertThat(fixture.sent).isEmpty();
        fixture.assertReleased();
    }

    @Test
    void sendFailureAbortsAndReleasesUnsentRunMessages() {
        Fixture fixture = new Fixture(Flux.just(text("{}")));
        when(fixture.protocol.handleTextMessage(any(), any(), any(), any())).thenAnswer(call -> {
            ChatWebSocketOutbound sender = call.getArgument(2);
            sender.emit(ChatWebSocketEnvelopeDto.message("chat-run-run1", null, "1"));
            sender.emit(ChatWebSocketEnvelopeDto.message("chat-run-run1", null, "2"));
            return Mono.empty();
        });
        doAnswer(call -> fixture.consume(Flux.<WebSocketMessage>from(call.getArgument(0)).take(1))
                .then(Mono.error(new IllegalStateException("send failed"))))
                .when(fixture.session).send(any());
        StepVerifier.create(fixture.handler.handle(fixture.session))
                .expectErrorMessage("send failed").verify(Duration.ofSeconds(1));
        assertThat(fixture.sent).hasSize(1);
        fixture.assertReleased();
    }

    @Test
    void cancellationWhileAnEmitterIsInFlightReleasesItsLateBuffer() throws Exception {
        Sinks.Many<WebSocketMessage> input = Sinks.many().unicast().onBackpressureBuffer();
        Fixture fixture = new Fixture(input.asFlux());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return fixture.allocate(call.getArgument(0));
        }).when(fixture.session).textMessage(any());
        Disposable running = fixture.handler.handle(fixture.session).subscribe();
        input.tryEmitNext(text("{}")).orThrow();
        CompletableFuture<Void> emitter = CompletableFuture.runAsync(() ->
                fixture.sender.get().emit(ChatWebSocketEnvelopeDto.reply("in-flight", Map.of())));
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            running.dispose();
            release.countDown();
            emitter.get(1, TimeUnit.SECONDS);
            assertThat(fixture.sent).isEmpty();
            fixture.assertReleased();
            verify(fixture.protocol).close("drain-test", fixture.user);
        } finally {
            release.countDown();
            running.dispose();
            emitter.get(2, TimeUnit.SECONDS);
        }
    }

    private static WebSocketMessage text(String value) {
        return new WebSocketMessage(WebSocketMessage.Type.TEXT,
                DefaultDataBufferFactory.sharedInstance.wrap(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class Fixture {
        private final UserContext user = new UserContext("tenant", "user", "User");
        private final ChatWebSocketProtocolService protocol = mock(ChatWebSocketProtocolService.class);
        private final WebSocketSession session = mock(WebSocketSession.class);
        private final List<String> sent = new CopyOnWriteArrayList<>();
        private final List<WebSocketMessage> allocated = new CopyOnWriteArrayList<>();
        private final AtomicReference<ChatWebSocketOutbound> sender = new AtomicReference<>();
        private final ChatWebSocketHandler handler;

        private Fixture(Flux<WebSocketMessage> input) {
            HandshakeInfo handshake = mock(HandshakeInfo.class);
            when(handshake.getHeaders()).thenReturn(HttpHeaders.EMPTY);
            when(session.getHandshakeInfo()).thenReturn(handshake);
            when(session.getId()).thenReturn("drain-test");
            when(session.isOpen()).thenReturn(true);
            when(session.receive()).thenReturn(input);
            when(session.textMessage(any())).thenAnswer(call -> allocate(call.getArgument(0)));
            when(session.close(any())).thenReturn(Mono.empty());
            when(session.send(any())).thenAnswer(call -> consume(Flux.from(call.getArgument(0))));
            when(protocol.handleTextMessage(any(), any(), any(), any())).thenAnswer(call -> {
                sender.set(call.getArgument(2));
                return Mono.empty();
            });
            handler = new ChatWebSocketHandler(protocol, () -> user, new ObjectMapper(), new ChatWebSocketProperties());
        }

        private WebSocketMessage allocate(String value) {
            WebSocketMessage message = spy(text(value));
            allocated.add(message);
            return message;
        }

        private Mono<Void> consume(Flux<WebSocketMessage> messages) {
            return messages.doOnNext(message -> {
                sent.add(message.getPayloadAsText());
                message.release();
            }).then();
        }

        private void assertReleased() {
            allocated.forEach(message -> verify(message).release());
        }
    }
}
