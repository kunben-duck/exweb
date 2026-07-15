package com.huawei.it.ex.one.interfaces.chat.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

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

        Sinks.Many<WebSocketMessage> outbound = Sinks.many().unicast()
                .onBackpressureBuffer(Queues.<WebSocketMessage>get(properties.normalizedOutboundQueueSize()).get());
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
                .doFinally(signalType -> {
                    protocolService.close(session.getId(), user);
                    outbound.tryEmitComplete();
                })
                .then();
        return Mono.when(inbound, session.send(outbound.asFlux()));
    }

    private void emit(WebSocketSession session, Sinks.Many<WebSocketMessage> outbound,
                      UserContext user, ChatWebSocketEnvelopeDto dto) {
        Sinks.EmitResult result = outbound.tryEmitNext(toMessage(session, dto));
        if (result.isFailure()) {
            protocolService.close(session.getId(), user);
            outbound.tryEmitComplete();
            session.close(CloseStatus.SERVICE_OVERLOAD).subscribe();
        }
    }

    private WebSocketMessage toMessage(WebSocketSession session, ChatWebSocketEnvelopeDto dto) {
        try {
            return session.textMessage(objectMapper.writeValueAsString(dto));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("WebSocket 响应序列化失败", ex);
        }
    }
}
