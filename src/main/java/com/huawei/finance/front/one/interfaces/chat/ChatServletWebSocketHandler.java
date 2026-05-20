package com.huawei.finance.front.one.interfaces.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Servlet/MVC 服务端栈下的前端 WebSocket 入口。
 *
 * <p>企业框架通常会引入 {@code spring-boot-starter-web} 并让应用以 Servlet 模式启动。
 * 在这种模式下 WebFlux 的 {@code WebSocketHandler} 不会进入请求映射链，因此需要使用
 * Spring Servlet WebSocket 注册同一个 {@code /api/v1/ex/chat/ws} 路径。</p>
 */
@Component
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ChatServletWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatServletWebSocketHandler.class);
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final ChatWebSocketProtocolService protocolService;
    private final ObjectMapper objectMapper;
    private final Map<String, ServletConnection> connections = new ConcurrentHashMap<>();

    public ChatServletWebSocketHandler(ChatWebSocketProtocolService protocolService, ObjectMapper objectMapper) {
        this.protocolService = protocolService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UserContext user;
        try {
            user = ChatWebSocketUserContextAttributes.require(session.getAttributes());
            protocolService.open(session.getId(), user);
        } catch (RuntimeException ex) {
            session.sendMessage(toMessage(ChatWebSocketEnvelopeDto.error(null, "WS_AUTH_FAILED", ex.getMessage())));
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        // ConcurrentWebSocketSessionDecorator 给 Servlet WebSocket 出站发送提供并发保护和有界缓冲。
        // 这对应 WebFlux 版本的 unicast sink，避免多个 runtime 事件线程同时写底层 socket。
        ConcurrentWebSocketSessionDecorator decorated = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES);
        connections.put(session.getId(), new ServletConnection(user, decorated));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ServletConnection connection = connections.get(session.getId());
        if (connection == null) {
            closeSilently(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        ChatWebSocketOutbound outbound = envelope -> emit(session.getId(), connection, envelope);
        protocolService.handleTextMessage(session.getId(), connection.user(), outbound, message.getPayload())
                .subscribe(
                        ignored -> { },
                        ex -> emit(session.getId(), connection,
                                ChatWebSocketEnvelopeDto.error(null, "WS_STREAM_ERROR", ex.getMessage()))
                );
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ServletConnection connection = connections.remove(session.getId());
        if (connection != null) {
            protocolService.close(session.getId(), connection.user());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Servlet WebSocket transport error, connectionId={}, reason={}", session.getId(), exception.getMessage());
        closeSilently(session, CloseStatus.SERVER_ERROR);
    }

    private void emit(String connectionId, ServletConnection connection, ChatWebSocketEnvelopeDto envelope) {
        try {
            connection.session().sendMessage(toMessage(envelope));
        } catch (Exception ex) {
            log.warn("Servlet WebSocket send failed, connectionId={}, reason={}", connectionId, ex.getMessage());
            protocolService.close(connectionId, connection.user());
            connections.remove(connectionId);
            closeSilently(connection.session(), CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    private TextMessage toMessage(ChatWebSocketEnvelopeDto dto) {
        try {
            return new TextMessage(objectMapper.writeValueAsString(dto));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("WebSocket 响应序列化失败", ex);
        }
    }

    private void closeSilently(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ex) {
            log.debug("Ignore WebSocket close failure, connectionId={}, reason={}", session.getId(), ex.getMessage());
        }
    }

    /**
     * Servlet WebSocket 连接运行态。
     *
     * @param user 握手入口解析出的不可变用户身份。
     * @param session 具备并发发送保护的 Servlet WebSocket session。
     */
    private record ServletConnection(UserContext user, ConcurrentWebSocketSessionDecorator session) {
    }
}
