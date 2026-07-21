package com.huawei.it.ex.one.chat.interfaces.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.chat.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.chat.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.chat.interfaces.http.ChatEventTranslator;
import com.huawei.it.ex.one.chat.interfaces.http.ChatTurnStreamTranslator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

class ChatServletWebSocketHandlerAsyncSendTest {
    private ExecutorService executor;

    @AfterEach
    void shutdownExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void controlMessageHandlingDoesNotWaitForSlowSocketSend() throws Exception {
        LocalWebSocketConnectionRegistry registry = new LocalWebSocketConnectionRegistry();
        ChatServletWebSocketHandler handler = handler(registry, properties());
        BlockingWebSocketSession session = session("conn1");
        handler.afterConnectionEstablished(session);

        long startedAt = System.nanoTime();
        handler.handleTextMessage(session, new TextMessage("{\"id\":\"1\",\"type\":\"connect\"}"));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(elapsedMillis).isLessThan(200L);
        assertThat(session.sendStarted.await(1, TimeUnit.SECONDS)).isTrue();

        session.releaseSend();
    }

    @Test
    void outboundOverflowClosesConnectionAndReleasesRegistry() throws Exception {
        ChatWebSocketProperties properties = properties();
        properties.setServletSendQueueCapacity(1);
        LocalWebSocketConnectionRegistry registry = new LocalWebSocketConnectionRegistry(properties);
        ChatServletWebSocketHandler handler = handler(registry, properties);
        BlockingWebSocketSession session = session("conn1");
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("{\"id\":\"1\",\"type\":\"connect\"}"));
        assertThat(session.sendStarted.await(1, TimeUnit.SECONDS)).isTrue();
        handler.handleTextMessage(session, new TextMessage("{\"id\":\"2\",\"type\":\"presence\",\"state\":\"foreground\"}"));
        handler.handleTextMessage(session, new TextMessage("{\"id\":\"3\",\"type\":\"presence\",\"state\":\"background\"}"));

        assertThat(registry.get("conn1")).isEmpty();
        assertThat(session.closed).isTrue();

        session.releaseSend();
    }

    private ChatServletWebSocketHandler handler(LocalWebSocketConnectionRegistry registry,
                                                ChatWebSocketProperties properties) {
        executor = Executors.newSingleThreadExecutor();
        ChatWebSocketProtocolService protocolService = new ChatWebSocketProtocolService(
                new PermissionChecker(),
                null,
                registry,
                new ChatEventTranslator(),
                new ChatTurnStreamTranslator(),
                new ChatStreamProperties(),
                new ObjectMapper()
        );
        return new ChatServletWebSocketHandler(protocolService, new ObjectMapper(), properties, executor);
    }

    private ChatWebSocketProperties properties() {
        ChatWebSocketProperties properties = new ChatWebSocketProperties();
        properties.setSendTimeLimit(Duration.ofSeconds(30));
        properties.setServletSendQueueMaxBytes(org.springframework.util.unit.DataSize.ofKilobytes(64));
        return properties;
    }

    private BlockingWebSocketSession session(String id) {
        Map<String, Object> attributes = new HashMap<>();
        ChatWebSocketUserContextAttributes.put(attributes, new UserContext("tenant1", "user1", "User One"));
        return new BlockingWebSocketSession(id, attributes);
    }

    private static final class BlockingWebSocketSession implements WebSocketSession {
        private final String id;
        private final Map<String, Object> attributes;
        private final CountDownLatch releaseSend = new CountDownLatch(1);
        private final CountDownLatch sendStarted = new CountDownLatch(1);
        private volatile boolean closed;

        private BlockingWebSocketSession(String id, Map<String, Object> attributes) {
            this.id = id;
            this.attributes = attributes;
        }

        private void releaseSend() {
            releaseSend.countDown();
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public URI getUri() {
            return URI.create("ws://localhost/v1/chat/ws");
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return HttpHeaders.EMPTY;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 64 * 1024;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 64 * 1024;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            sendStarted.countDown();
            try {
                releaseSend.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", ex);
            }
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        @Override
        public void close() {
            closed = true;
            releaseSend.countDown();
        }

        @Override
        public void close(CloseStatus status) {
            close();
        }
    }
}
