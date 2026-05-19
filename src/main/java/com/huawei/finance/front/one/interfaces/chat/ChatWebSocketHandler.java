package com.huawei.finance.front.one.interfaces.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;
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
 * {@code /api/v1/ex/chat/ws}。当企业框架引入 Spring MVC 并以 Servlet 模式启动时，
 * 该 bean 不会生效，改由 {@link ChatServletWebSocketHandler} 注册同一路径。</p>
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ChatWebSocketHandler implements WebSocketHandler {
    private final ChatWebSocketProtocolService protocolService;
    private final ObjectMapper objectMapper;

    public ChatWebSocketHandler(ChatWebSocketProtocolService protocolService, ObjectMapper objectMapper) {
        this.protocolService = protocolService;
        this.objectMapper = objectMapper;
    }

    /**
     * 建立并处理一条 WebFlux WebSocket 连接。
     *
     * @param session WebFlux WebSocket 会话，承载当前物理连接的收发流。
     * @return 连接生命周期完成信号。
     */
    @Override
    public Mono<Void> handle(WebSocketSession session) {
        UserContext user;
        try {
            user = protocolService.open(session.getId());
        } catch (RuntimeException ex) {
            return session.send(Flux.just(toMessage(session,
                    ChatWebSocketEnvelopeDto.error(null, "WS_AUTH_FAILED", ex.getMessage()))));
        }

        Sinks.Many<WebSocketMessage> outbound = Sinks.many().unicast()
                .onBackpressureBuffer(Queues.<WebSocketMessage>get(256).get());
        ChatWebSocketOutbound sender = envelope -> emit(session, outbound, user, envelope);
        Mono<Void> inbound = session.receive()
                .filter(message -> message.getType() == WebSocketMessage.Type.TEXT)
                // 当前只接受连接控制消息。聊天请求必须走 POST /chat/runs 创建后台 run。
                .concatMap(message -> protocolService.handleTextMessage(
                        session.getId(), user, sender, message.getPayloadAsText()))
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
