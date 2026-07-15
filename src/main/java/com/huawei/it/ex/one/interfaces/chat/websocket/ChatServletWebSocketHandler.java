package com.huawei.it.ex.one.interfaces.chat.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
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
 * Spring Servlet WebSocket 注册同一个 {@code /v1/chat/ws} 路径。</p>
 */
@Component
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ChatServletWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatServletWebSocketHandler.class);

    private final ChatWebSocketProtocolService protocolService;
    private final ObjectMapper objectMapper;
    private final ChatWebSocketProperties properties;
    private final Executor sendExecutor;
    private final Map<String, ServletConnection> connections = new ConcurrentHashMap<>();

    public ChatServletWebSocketHandler(ChatWebSocketProtocolService protocolService, ObjectMapper objectMapper,
                                       ChatWebSocketProperties properties,
                                       @Qualifier("chatServletWebSocketSendExecutor") Executor sendExecutor) {
        this.protocolService = protocolService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.sendExecutor = sendExecutor;
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
                session, properties.normalizedSendTimeLimitMillis(), properties.normalizedSendBufferSizeBytes());
        ServletWebSocketOutboundQueue outboundQueue = new ServletWebSocketOutboundQueue(
                properties.normalizedServletSendQueueCapacity(),
                properties.normalizedServletSendQueueMaxBytes()
        );
        connections.put(session.getId(), new ServletConnection(user, decorated, outboundQueue));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ServletConnection connection = connections.get(session.getId());
        if (connection == null) {
            closeSilently(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        if (message.getPayloadLength() > properties.normalizedMaxInboundMessageBytes()) {
            emit(session.getId(), connection, ChatWebSocketEnvelopeDto.error(null,
                    "WS_MESSAGE_TOO_LARGE", "WebSocket 控制消息超过最大允许大小"));
            closeConnection(session.getId(), connection, "WS_MESSAGE_TOO_LARGE", CloseStatus.TOO_BIG_TO_PROCESS);
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
            connection.outbound().close();
            protocolService.close(session.getId(), connection.user());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Servlet WebSocket transport error, connectionId={}, reason={}", session.getId(), exception.getMessage());
        ServletConnection connection = connections.remove(session.getId());
        if (connection != null) {
            connection.outbound().close();
            protocolService.close(session.getId(), connection.user());
        }
        closeSilently(session, CloseStatus.SERVER_ERROR);
    }

    /**
     * 清理 MVC WebSocket 空闲连接。
     *
     * <p>Servlet 容器会为长连接保留资源。生产环境中如果前端页面进入后台、网络中断但 TCP 未立即关闭，
     * 该任务会主动释放本机连接状态和 topic 订阅，避免连接泄漏。</p>
     */
    @Scheduled(fixedDelayString = "#{@chatWebSocketProperties.normalizedIdleCheckIntervalMillis()}")
    public void closeIdleConnections() {
        connections.forEach((connectionId, connection) -> {
            if (protocolService.idleForLongerThan(connectionId, properties.normalizedIdleTimeout())) {
                closeConnection(connectionId, connection, "WS_IDLE_TIMEOUT", CloseStatus.GOING_AWAY);
            }
        });
    }

    private void emit(String connectionId, ServletConnection connection, ChatWebSocketEnvelopeDto envelope) {
        ServletWebSocketOutboundQueue.OutboundMessage message;
        try {
            message = toOutboundMessage(envelope);
        } catch (Exception ex) {
            log.warn("Servlet WebSocket envelope serialization failed, connectionId={}, reason={}",
                    connectionId, ex.getMessage());
            closeConnection(connectionId, connection, "WS_SERIALIZATION_FAILED", CloseStatus.SERVER_ERROR);
            return;
        }
        ServletWebSocketOutboundQueue.OfferResult result = connection.outbound().offer(message);
        if (result == ServletWebSocketOutboundQueue.OfferResult.ACCEPTED) {
            scheduleDrain(connectionId, connection);
        } else if (result == ServletWebSocketOutboundQueue.OfferResult.SKIPPED_HEARTBEAT) {
            log.debug("Skip WebSocket heartbeat because outbound queue is busy, connectionId={}, topicId={}, offset={}",
                    connectionId, message.topicId(), message.offset());
        } else if (result == ServletWebSocketOutboundQueue.OfferResult.OVERFLOW) {
            ServletWebSocketOutboundQueue.Snapshot snapshot = connection.outbound().snapshot();
            log.warn("Servlet WebSocket outbound overflow, connectionId={}, topicId={}, offset={}, envelopeType={}, messageBytes={}, queueSize={}, queuedBytes={}",
                    connectionId, message.topicId(), message.offset(), message.envelopeType(), message.bytes(),
                    snapshot.queueSize(), snapshot.queuedBytes());
            closeConnection(connectionId, connection, "WS_OUTBOUND_OVERFLOW", CloseStatus.SERVICE_OVERLOAD);
        } else {
            log.debug("Drop WebSocket envelope because connection is closing, connectionId={}, envelopeType={}, topicId={}",
                    connectionId, message.envelopeType(), message.topicId());
        }
    }

    private void scheduleDrain(String connectionId, ServletConnection connection) {
        if (!connection.outbound().tryStartDraining()) {
            return;
        }
        try {
            sendExecutor.execute(() -> drainOutbound(connectionId, connection));
        } catch (RejectedExecutionException ex) {
            log.warn("Servlet WebSocket send executor rejected drain task, connectionId={}, reason={}",
                    connectionId, ex.getMessage());
            closeConnection(connectionId, connection, "WS_SEND_EXECUTOR_REJECTED", CloseStatus.SERVICE_OVERLOAD);
        }
    }

    private void drainOutbound(String connectionId, ServletConnection connection) {
        ServletWebSocketOutboundQueue.OutboundMessage current = null;
        try {
            while ((current = connection.outbound().poll()) != null) {
                if (!connection.session().isOpen()) {
                    closeConnection(connectionId, connection, "WS_SESSION_CLOSED", CloseStatus.SESSION_NOT_RELIABLE);
                    return;
                }
                connection.session().sendMessage(new TextMessage(current.payload()));
            }
        } catch (Exception ex) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
            ServletWebSocketOutboundQueue.Snapshot snapshot = connection.outbound().snapshot();
            log.warn("Servlet WebSocket async send failed, connectionId={}, topicId={}, offset={}, envelopeType={}, messageBytes={}, queueSize={}, queuedBytes={}, reason={}",
                    connectionId, current == null ? null : current.topicId(), current == null ? null : current.offset(),
                    current == null ? null : current.envelopeType(), current == null ? 0 : current.bytes(),
                    snapshot.queueSize(), snapshot.queuedBytes(), ex.getMessage());
            closeConnection(connectionId, connection, "WS_SEND_FAILED", CloseStatus.SESSION_NOT_RELIABLE);
            return;
        } finally {
            if (connection.outbound().finishDrainingAndHasPending()) {
                scheduleDrain(connectionId, connection);
            }
        }
    }

    private void closeConnection(String connectionId, ServletConnection connection, String reason, CloseStatus closeStatus) {
        connection.outbound().close();
        if (connections.remove(connectionId, connection)) {
            protocolService.close(connectionId, connection.user());
        }
        closeSilently(connection.session(), closeStatus.withReason(reason));
    }

    private TextMessage toMessage(ChatWebSocketEnvelopeDto dto) {
        return new TextMessage(toPayload(dto));
    }

    private ServletWebSocketOutboundQueue.OutboundMessage toOutboundMessage(ChatWebSocketEnvelopeDto dto) {
        String payload = toPayload(dto);
        int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
        return new ServletWebSocketOutboundQueue.OutboundMessage(payload, bytes, dto.type(), dto.topicId(),
                dto.offset(), heartbeat(dto));
    }

    private String toPayload(ChatWebSocketEnvelopeDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("WebSocket 响应序列化失败", ex);
        }
    }

    private boolean heartbeat(ChatWebSocketEnvelopeDto dto) {
        return "message".equals(dto.type())
                && dto.payload() != null
                && dto.payload().payload() != null
                && "heartbeat".equals(dto.payload().payload().type());
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
     * @param outbound 当前连接的有界异步发送队列。
     */
    private record ServletConnection(UserContext user, ConcurrentWebSocketSessionDecorator session,
                                     ServletWebSocketOutboundQueue outbound) {
    }
}
