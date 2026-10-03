/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.websocket;

import com.huawei.it.ex.one.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.Scannable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebFlux 服务端栈下的前端 WebSocket 入口。
 *
 * <p>当应用以 Reactive WebFlux 启动时，该 handler 承载
 * {@code /v1/chat/ws}。当企业框架引入 Spring MVC 并以 Servlet 模式启动时，
 * 该 bean 不会生效，改由 {@link ChatServletWebSocketHandler} 注册同一路径。</p>
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ChatWebSocketHandler implements WebSocketHandler {
    private final ChatWebSocketProtocolService protocolService;
    private final AuthContextProvider auth;
    private final ObjectMapper objectMapper;
    private final ChatWebSocketProperties properties;

    public ChatWebSocketHandler(ChatWebSocketProtocolService protocolService,
                                AuthContextProvider auth,
                                ObjectMapper objectMapper,
                                ChatWebSocketProperties properties) {
        this.protocolService = protocolService;
        this.auth = auth;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 建立并处理一条 WebFlux WebSocket 连接。
     *
     * @param session WebFlux WebSocket 会话，承载当前物理连接的收发流。
     * @return 连接生命周期完成信号。
     */
    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String origin = session.getHandshakeInfo().getHeaders().getOrigin();
        if (!properties.originAllowed(origin)) {
            return session.send(Flux.just(toMessage(session,
                    ChatWebSocketEnvelopeDto.error(null, "WS_ORIGIN_FORBIDDEN", "WebSocket Origin 不在允许列表"))))
                    .then(session.close(CloseStatus.POLICY_VIOLATION));
        }
        UserContext user;
        try {
            user = auth.resolve();
            protocolService.open(session.getId(), user);
        } catch (RuntimeException ex) {
            return session.send(Flux.just(toMessage(session,
                    ChatWebSocketEnvelopeDto.error(null, "WS_AUTH_FAILED", ex.getMessage()))));
        }

        OutboundStreams outbound = new OutboundStreams(Sinks.many().unicast()
                .onBackpressureBuffer(Queues.<WebSocketMessage>get(properties.normalizedOutboundQueueSize()).get()),
                Sinks.many().multicast().directBestEffort(), Sinks.empty());
        ChatWebSocketOutbound sender = envelope -> emit(session, outbound, user, envelope);
        Mono<Void> inbound = session.receive()
                .filter(message -> message.getType() == WebSocketMessage.Type.TEXT)
                .handle((message, sink) -> {
                    if (message.getPayloadAsText().length() > properties.normalizedMaxInboundMessageBytes()) {
                        sender.emit(ChatWebSocketEnvelopeDto.error(null,
                                "WS_MESSAGE_TOO_LARGE", "WebSocket 控制消息超过最大允许大小"));
                        sink.error(new IllegalArgumentException("WebSocket 控制消息超过最大允许大小"));
                    } else {
                        sink.next(message);
                    }
                })
                // 当前只接受连接控制消息。聊天请求必须走 POST /chat/runs 创建后台 run。
                .concatMap(message -> protocolService.handleTextMessage(
                        session.getId(), user, sender, ((WebSocketMessage) message).getPayloadAsText()))
                .onErrorResume(ex -> {
                    sender.emit(ChatWebSocketEnvelopeDto.error(null, "WS_STREAM_ERROR", ex.getMessage()));
                    return Mono.empty();
                })
                .takeUntilOther(outbound.closed().asMono())
                .then()
                .doOnSuccess(ignored -> {
                    if (session.isOpen()) {
                        outbound.drain();
                    } else {
                        outbound.abort();
                    }
                    closeSubscriptions(session, outbound, user);
                })
                .doFinally(signalType -> {
                    if (signalType != SignalType.ON_COMPLETE) {
                        outbound.abort();
                        closeSubscriptions(session, outbound, user);
                    }
                });
        // 通知不能与 Run 争用同一个 Sink 的生产者保护；合流只预取一条，不等待发送锁。
        Flux<WebSocketMessage> messages = outbound.messages();
        // 期限从接收结束开始，覆盖 socket 的实际发送完成，不限制正常长连接。
        Mono<Void> send = session.send(messages)
                .timeout(outbound.drainDeadline(), Mono.defer(() -> {
                    closeOverloaded(session, outbound, user);
                    return Mono.empty();
                }))
                .takeUntilOther(outbound.closed().asMono())
                .doFinally(signal -> {
                    outbound.abort();
                    closeSubscriptions(session, outbound, user);
                });
        return Mono.when(inbound, send).doFinally(signal -> {
            outbound.abort();
            closeSubscriptions(session, outbound, user);
        });
    }

    private void emit(WebSocketSession session, OutboundStreams outbound,
                      UserContext user, ChatWebSocketEnvelopeDto dto) {
        if (!outbound.enter()) {
            return;
        }
        try {
            emitAccepted(session, outbound, user, dto);
        } finally {
            outbound.exit();
        }
    }

    private void emitAccepted(WebSocketSession session, OutboundStreams outbound,
                              UserContext user, ChatWebSocketEnvelopeDto dto) {
        boolean notification = "notification".equals(dto.type());
        Integer buffered = notification ? outbound.events().scan(Scannable.Attr.BUFFERED) : null;
        if (notification && buffered != null && buffered > 0) {
            return;
        }
        WebSocketMessage message;
        try {
            message = toMessage(session, dto);
        } catch (RuntimeException ex) {
            if (notification) {
                return;
            }
            throw ex;
        }
        if (outbound.state.get() == OutboundStreams.ABORTED) {
            message.release();
            return;
        }
        Sinks.EmitResult result = (notification ? outbound.notifications() : outbound.events()).tryEmitNext(message);
        if (result.isFailure() && notification) {
            message.release();
            return;
        }
        if (result.isFailure()) {
            message.release();
            closeOverloaded(session, outbound, user);
        }
    }

    private void closeSubscriptions(WebSocketSession session, OutboundStreams outbound, UserContext user) {
        if (outbound.subscriptionsClosed.compareAndSet(false, true)) {
            protocolService.close(session.getId(), user);
        }
    }

    private void closeOverloaded(WebSocketSession session, OutboundStreams outbound, UserContext user) {
        outbound.abort();
        closeSubscriptions(session, outbound, user);
        session.close(CloseStatus.SERVICE_OVERLOAD).subscribe();
    }

    private WebSocketMessage toMessage(WebSocketSession session, ChatWebSocketEnvelopeDto dto) {
        try {
            return session.textMessage(objectMapper.writeValueAsString(dto));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("WebSocket 响应序列化失败", ex);
        }
    }

    static final class OutboundStreams {
        private static final int OPEN = 0;
        private static final int DRAINING = 1;
        private static final int ABORTED = 2;
        private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(2);
        private final Sinks.Many<WebSocketMessage> events;
        private final Sinks.Many<WebSocketMessage> notifications;
        private final Sinks.Empty<Void> closed;
        private final Sinks.One<Long> draining = Sinks.one();
        private final AtomicInteger state = new AtomicInteger(OPEN);
        private final AtomicInteger emitting = new AtomicInteger();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean subscriptionsClosed = new AtomicBoolean();
        private final AtomicBoolean outputSubscribed = new AtomicBoolean();

        OutboundStreams(Sinks.Many<WebSocketMessage> events, Sinks.Many<WebSocketMessage> notifications,
                        Sinks.Empty<Void> closed) {
            this.events = events;
            this.notifications = notifications;
            this.closed = closed;
        }

        Sinks.Many<WebSocketMessage> events() {
            return events;
        }

        Sinks.Many<WebSocketMessage> notifications() {
            return notifications;
        }

        Sinks.Empty<Void> closed() {
            return closed;
        }

        private boolean enter() {
            if (state.get() != OPEN) {
                return false;
            }
            emitting.incrementAndGet();
            // 排空可能发生在第一次检查与计数之间；二次检查阻止迟到生产者。
            if (state.get() != OPEN) {
                exit();
                return false;
            }
            return true;
        }

        private void exit() {
            if (emitting.decrementAndGet() == 0) {
                completeWhenIdle();
            }
        }

        private void drain() {
            if (state.compareAndSet(OPEN, DRAINING)) {
                draining.tryEmitValue(System.nanoTime());
                completeWhenIdle();
            }
        }

        private void completeWhenIdle() {
            // 必须等所有已进入的 tryEmitNext 退出，避免完成信号因并发发送而失败。
            if (state.get() == DRAINING && emitting.get() == 0 && completed.compareAndSet(false, true)) {
                events.tryEmitComplete();
            }
        }

        private Mono<Long> drainDeadline() {
            return draining.asMono().flatMap(start -> Mono.delay(Duration.ofNanos(
                    Math.max(0L, DRAIN_TIMEOUT.toNanos() - (System.nanoTime() - start)))));
        }

        private Flux<WebSocketMessage> messages() {
            return Flux.defer(() -> {
                if (!outputSubscribed.compareAndSet(false, true)) {
                    return Flux.empty();
                }
                return Flux.merge(1, events.asFlux(), notifications.asFlux().takeUntilOther(draining.asMono()))
                        .takeUntilOther(closed.asMono())
                        .doOnDiscard(WebSocketMessage.class, WebSocketMessage::release);
            });
        }

        private void abort() {
            state.set(ABORTED);
            closed.tryEmitEmpty();
            // send 尚未订阅就关闭时，也要接管并释放已入队消息；与真实输出只允许一个消费者。
            if (outputSubscribed.compareAndSet(false, true)) {
                events.asFlux().doOnDiscard(WebSocketMessage.class, WebSocketMessage::release)
                        .subscribe(WebSocketMessage::release).dispose();
            }
        }
    }
}
