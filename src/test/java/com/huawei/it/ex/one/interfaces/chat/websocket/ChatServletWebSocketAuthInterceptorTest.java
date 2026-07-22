package com.huawei.it.ex.one.interfaces.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.interfaces.chat.ChatEventTranslator;
import com.huawei.it.ex.one.interfaces.chat.ChatTurnStreamTranslator;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
class ChatServletWebSocketAuthInterceptorTest {
    private static final ThreadLocal<UserContext> CURRENT_USER = new ThreadLocal<>();

    @AfterEach
    void clearThreadLocal() {
        CURRENT_USER.remove();
    }

    @Test
    void beforeHandshakeStoresUserContextSnapshotFromRequestThread() {
        UserContext user = new UserContext("tenant1", "user1", "User One");
        CURRENT_USER.set(user);
        Map<String, Object> attributes = new HashMap<>();
        ChatServletWebSocketAuthInterceptor interceptor = new ChatServletWebSocketAuthInterceptor(
                threadLocalAuthProvider(),
                new PermissionChecker()
        );

        boolean accepted = interceptor.beforeHandshake(null, null, null, attributes);
        CURRENT_USER.remove();

        assertThat(accepted).isTrue();
        assertThat(ChatWebSocketUserContextAttributes.require(attributes)).isEqualTo(user);
    }

    @Test
    void beforeHandshakeRejectsWhenThreadLocalUserIsMissing() {
        Map<String, Object> attributes = new HashMap<>();
        ChatServletWebSocketAuthInterceptor interceptor = new ChatServletWebSocketAuthInterceptor(
                threadLocalAuthProvider(),
                new PermissionChecker()
        );

        boolean accepted = interceptor.beforeHandshake(null, null, null, attributes);

        assertThat(accepted).isFalse();
        assertThat(attributes).doesNotContainKey(ChatWebSocketUserContextAttributes.USER_CONTEXT_ATTRIBUTE);
    }

    @Test
    void connectionEstablishedUsesHandshakeSnapshotAfterThreadLocalIsGone() throws Exception {
        UserContext user = new UserContext("tenant1", "user1", "User One");
        Map<String, Object> attributes = new HashMap<>();
        ChatWebSocketUserContextAttributes.put(attributes, user);
        CURRENT_USER.remove();
        LocalWebSocketConnectionRegistry registry = new LocalWebSocketConnectionRegistry();
        ChatWebSocketProtocolService protocolService = new ChatWebSocketProtocolService(
                new PermissionChecker(),
                null,
                registry,
                new ChatEventTranslator(),
                new ChatTurnStreamTranslator(),
                new ChatStreamProperties(),
                new ObjectMapper()
        );
        ChatServletWebSocketHandler handler = new ChatServletWebSocketHandler(
                protocolService,
                new ObjectMapper(),
                new com.huawei.it.ex.one.application.config.ChatWebSocketProperties(),
                Runnable::run
        );

        handler.afterConnectionEstablished(new TestWebSocketSession("conn1", attributes));

        LocalWebSocketConnectionRegistry.ConnectionState connection = registry.get("conn1").orElseThrow();
        assertThat(connection.tenantId()).isEqualTo("tenant1");
        assertThat(connection.userId()).isEqualTo("user1");
        assertThat(connection.username()).isEqualTo("User One");
    }

    @Test
    void transportErrorReleasesConnectionRegistryImmediately() throws Exception {
        UserContext user = new UserContext("tenant1", "user1", "User One");
        Map<String, Object> attributes = new HashMap<>();
        ChatWebSocketUserContextAttributes.put(attributes, user);
        LocalWebSocketConnectionRegistry registry = new LocalWebSocketConnectionRegistry();
        ChatWebSocketProtocolService protocolService = new ChatWebSocketProtocolService(
                new PermissionChecker(),
                null,
                registry,
                new ChatEventTranslator(),
                new ChatTurnStreamTranslator(),
                new ChatStreamProperties(),
                new ObjectMapper()
        );
        ChatServletWebSocketHandler handler = new ChatServletWebSocketHandler(
                protocolService,
                new ObjectMapper(),
                new com.huawei.it.ex.one.application.config.ChatWebSocketProperties(),
                Runnable::run
        );
        TestWebSocketSession session = new TestWebSocketSession("conn1", attributes);
        handler.afterConnectionEstablished(session);

        handler.handleTransportError(session, new IOException("broken socket"));

        assertThat(registry.get("conn1")).isEmpty();
    }

    private AuthContextProvider threadLocalAuthProvider() {
        return () -> {
            UserContext user = CURRENT_USER.get();
            if (user == null) {
                throw new SecurityException("missing user");
            }
            return user;
        };
    }

    private static class TestWebSocketSession implements WebSocketSession {
        private final String id;
        private final Map<String, Object> attributes;

        private TestWebSocketSession(String id, Map<String, Object> attributes) {
            this.id = id;
            this.attributes = attributes;
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
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() throws IOException {
        }

        @Override
        public void close(CloseStatus status) throws IOException {
        }
    }
}
