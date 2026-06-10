package com.huawei.finance.front.one.interfaces.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.domain.auth.UserContext;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
class LocalWebSocketConnectionRegistryTest {
    @Test
    void connectionKeepsIndependentTopicSubscriptionsAndDeduplicatesSeq() {
        LocalWebSocketConnectionRegistry registry = new LocalWebSocketConnectionRegistry();
        registry.register("conn1", new UserContext("tenant1", "user1", "User One"));
        Disposable disposable = () -> {};

        registry.subscribe("conn1", "chat-run-run1", "session1", 10L, disposable);
        registry.subscribe("conn1", "chat-run-run2", "session1", 0L, disposable);

        assertThat(registry.get("conn1")).isPresent();
        assertThat(registry.get("conn1").orElseThrow().subscriptionCount()).isEqualTo(2);
        assertThat(registry.markDelivered("conn1", "chat-run-run1", 11L).action())
                .isEqualTo(LocalWebSocketConnectionRegistry.Action.DELIVER);
        assertThat(registry.markDelivered("conn1", "chat-run-run1", 11L).action())
                .isEqualTo(LocalWebSocketConnectionRegistry.Action.DUPLICATE);
        assertThat(registry.markDelivered("conn1", "chat-run-run2", 1L).action())
                .isEqualTo(LocalWebSocketConnectionRegistry.Action.DELIVER);
    }

    @Test
    void outOfOrderUnseenEventRequiresClientRecoveryInsteadOfSilentDrop() {
        LocalWebSocketConnectionRegistry registry = new LocalWebSocketConnectionRegistry();
        registry.register("conn1", new UserContext("tenant1", "user1", "User One"));
        registry.subscribe("conn1", "chat-run-run1", "session1", 10L, () -> {});

        assertThat(registry.markDelivered("conn1", "chat-run-run1", 20L).action())
                .isEqualTo(LocalWebSocketConnectionRegistry.Action.DELIVER);

        LocalWebSocketConnectionRegistry.DeliveryDecision decision = registry.markDelivered("conn1", "chat-run-run1", 19L);

        assertThat(decision.action()).isEqualTo(LocalWebSocketConnectionRegistry.Action.RECOVER_REQUIRED);
        assertThat(decision.lastAckSeq()).isEqualTo(10L);
        assertThat(decision.actualSeq()).isEqualTo(19L);
    }

    @Test
    void unregisterDisposesSubscriptions() {
        LocalWebSocketConnectionRegistry registry = new LocalWebSocketConnectionRegistry();
        TrackingDisposable disposable = new TrackingDisposable();
        registry.register("conn1", new UserContext("tenant1", "user1", "User One"));
        registry.subscribe("conn1", "chat-run-run1", "session1", 0L, disposable);

        registry.unregister("conn1");

        assertThat(disposable.disposed).isTrue();
        assertThat(registry.get("conn1")).isEmpty();
    }

    @Test
    void rejectsTooManyConnectionsForSameUserOnCurrentInstance() {
        com.huawei.finance.front.one.application.config.ChatWebSocketProperties properties =
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties();
        properties.setMaxConnectionsPerUser(1);
        LocalWebSocketConnectionRegistry registry = new LocalWebSocketConnectionRegistry(properties);

        registry.register("conn1", new UserContext("tenant1", "user1", "User One"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        registry.register("conn2", new UserContext("tenant1", "user1", "User One")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WS_CONNECTION_LIMIT_EXCEEDED");
    }

    @Test
    void rejectsTooManySubscriptionsOnSameConnection() {
        com.huawei.finance.front.one.application.config.ChatWebSocketProperties properties =
                new com.huawei.finance.front.one.application.config.ChatWebSocketProperties();
        properties.setMaxSubscriptionsPerConnection(1);
        LocalWebSocketConnectionRegistry registry = new LocalWebSocketConnectionRegistry(properties);
        registry.register("conn1", new UserContext("tenant1", "user1", "User One"));
        registry.subscribe("conn1", "chat-run-run1", "session1", 0L, () -> {});

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        registry.subscribe("conn1", "chat-run-run2", "session1", 0L, () -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WS_SUBSCRIPTION_LIMIT_EXCEEDED");
    }

    @Test
    void unsubscribeDisposesOnlySelectedTopic() {
        LocalWebSocketConnectionRegistry registry = new LocalWebSocketConnectionRegistry();
        TrackingDisposable first = new TrackingDisposable();
        TrackingDisposable second = new TrackingDisposable();
        registry.register("conn1", new UserContext("tenant1", "user1", "User One"));
        registry.subscribe("conn1", "chat-run-run1", "session1", 0L, first);
        registry.subscribe("conn1", "chat-run-run2", "session1", 0L, second);

        registry.unsubscribe("conn1", "chat-run-run1");

        assertThat(first.disposed).isTrue();
        assertThat(second.disposed).isFalse();
        assertThat(registry.get("conn1").orElseThrow().subscriptionCount()).isEqualTo(1);
    }

    @Test
    void duplicateConnectionIdReleasesPreviousSubscriptionsDefensively() {
        LocalWebSocketConnectionRegistry registry = new LocalWebSocketConnectionRegistry();
        TrackingDisposable disposable = new TrackingDisposable();
        registry.register("conn1", new UserContext("tenant1", "user1", "User One"));
        registry.subscribe("conn1", "chat-run-run1", "session1", 0L, disposable);

        registry.register("conn1", new UserContext("tenant1", "user1", "User One"));

        assertThat(disposable.disposed).isTrue();
        assertThat(registry.get("conn1").orElseThrow().subscriptionCount()).isZero();
    }

    @Test
    void sameConnectionCanSubscribeMultipleSessions() {
        LocalWebSocketConnectionRegistry registry = new LocalWebSocketConnectionRegistry();
        TrackingDisposable first = new TrackingDisposable();
        TrackingDisposable second = new TrackingDisposable();
        registry.register("conn1", new UserContext("tenant1", "user1", "User One"));
        registry.subscribe("conn1", "chat-run-run1", "session1", 0L, first);

        registry.subscribe("conn1", "chat-run-run2", "session2", 0L, second);

        assertThat(first.disposed).isFalse();
        assertThat(second.disposed).isFalse();
        assertThat(registry.get("conn1").orElseThrow().subscriptionCount()).isEqualTo(2);
    }

    private static class TrackingDisposable implements Disposable {
        private boolean disposed;

        @Override
        public void dispose() {
            disposed = true;
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }
    }
}
