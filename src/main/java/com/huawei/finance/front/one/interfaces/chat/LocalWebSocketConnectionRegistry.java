package com.huawei.finance.front.one.interfaces.chat;

import com.huawei.finance.front.one.domain.auth.UserContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

/**
 * 当前服务实例内的 WebSocket 连接注册表。
 *
 * <p>该注册表只保存运行态连接状态，不作为业务事实源。它用于连接级身份隔离、订阅释放、
 * 去重和排障；跨实例事件仍通过 Redis Pub/Sub 扇出，事件事实源仍是 openGauss。</p>
 */
@Component
public class LocalWebSocketConnectionRegistry {
    private final Map<String, ConnectionState> connections = new ConcurrentHashMap<>();

    /**
     * 注册一条已完成握手鉴权的 WebSocket 连接。
     *
     * @param connectionId WebSocket 物理连接 ID。
     * @param user 当前连接的用户身份快照。
     * @return 连接状态。
     */
    public ConnectionState register(String connectionId, UserContext user) {
        ConnectionState state = new ConnectionState(connectionId, user.tenantId(), user.userId(), user.username());
        connections.put(connectionId, state);
        return state;
    }

    /**
     * 查询连接状态。
     *
     * @param connectionId WebSocket 物理连接 ID。
     * @return 当前服务实例内记录的连接状态；连接不存在时为空。
     */
    public Optional<ConnectionState> get(String connectionId) {
        return Optional.ofNullable(connections.get(connectionId));
    }

    /**
     * 更新连接前后台状态。
     *
     * @param connectionId WebSocket 物理连接 ID。
     * @param presence 前端上报的前后台状态，例如 foreground、background。
     */
    public void updatePresence(String connectionId, String presence) {
        get(connectionId).ifPresent(state -> state.updatePresence(presence));
    }

    /**
     * 注册或替换某个 topic 的订阅。
     *
     * @param connectionId WebSocket 物理连接 ID。
     * @param topicId run 级 stream topic。
     * @param afterSeq 订阅起点，服务端不会向该连接重复投递小于等于该值的事件。
     * @param disposable 当前 topic 实时事件订阅句柄，取消订阅或断开连接时释放。
     */
    public void subscribe(String connectionId, String topicId, long afterSeq, Disposable disposable) {
        get(connectionId).ifPresent(state -> state.subscribe(topicId, afterSeq, disposable));
    }

    /**
     * 取消某个 topic 的订阅。
     *
     * @param connectionId WebSocket 物理连接 ID。
     * @param topicId 需要取消订阅的 run 级 stream topic。
     */
    public void unsubscribe(String connectionId, String topicId) {
        get(connectionId).ifPresent(state -> state.unsubscribe(topicId));
    }

    /**
     * 更新客户端确认消费到的序号。
     *
     * @param connectionId WebSocket 物理连接 ID。
     * @param topicId run 级 stream topic。
     * @param seq 客户端已经处理完成的最大事件序号。
     */
    public void ack(String connectionId, String topicId, long seq) {
        get(connectionId).ifPresent(state -> state.ack(topicId, seq));
    }

    /**
     * 判断事件是否应发送到该连接，并记录已发送序号。
     *
     * @param connectionId WebSocket 物理连接 ID。
     * @param topicId run 级 stream topic。
     * @param seq 准备投递的事件序号。
     * @return 投递决策；乱序事件会要求前端使用 SSE resume 恢复，而不是静默丢弃。
     */
    public DeliveryDecision markDelivered(String connectionId, String topicId, long seq) {
        return get(connectionId).map(state -> state.markDelivered(topicId, seq)).orElse(DeliveryDecision.notSubscribed());
    }

    /**
     * 注销连接并释放全部订阅。
     *
     * @param connectionId WebSocket 物理连接 ID。
     */
    public void unregister(String connectionId) {
        ConnectionState state = connections.remove(connectionId);
        if (state != null) {
            state.close();
        }
    }

    /**
     * 单条 WebSocket 连接的运行态状态。
     */
    public static final class ConnectionState {
        private final String connectionId;
        private final String tenantId;
        private final String userId;
        private final String username;
        private final Instant connectedAt;
        private final Map<String, SubscriptionState> subscriptions = new ConcurrentHashMap<>();
        private volatile String presence = "foreground";
        private volatile Instant lastActiveAt;

        private ConnectionState(String connectionId, String tenantId, String userId, String username) {
            this.connectionId = connectionId;
            this.tenantId = tenantId;
            this.userId = userId;
            this.username = username;
            this.connectedAt = Instant.now();
            this.lastActiveAt = connectedAt;
        }

        public String connectionId() {
            return connectionId;
        }

        public String tenantId() {
            return tenantId;
        }

        public String userId() {
            return userId;
        }

        public String username() {
            return username;
        }

        public String presence() {
            return presence;
        }

        public Instant connectedAt() {
            return connectedAt;
        }

        public Instant lastActiveAt() {
            return lastActiveAt;
        }

        public int subscriptionCount() {
            return subscriptions.size();
        }

        private void updatePresence(String presence) {
            if (presence != null && !presence.isBlank()) {
                this.presence = presence;
            }
            touch();
        }

        private void subscribe(String topicId, long afterSeq, Disposable disposable) {
            unsubscribe(topicId);
            subscriptions.put(topicId, new SubscriptionState(topicId, afterSeq, disposable));
            touch();
        }

        private void unsubscribe(String topicId) {
            SubscriptionState previous = subscriptions.remove(topicId);
            if (previous != null) {
                previous.dispose();
            }
            touch();
        }

        private void ack(String topicId, long seq) {
            SubscriptionState subscription = subscriptions.get(topicId);
            if (subscription != null) {
                subscription.ack(seq);
            }
            touch();
        }

        private DeliveryDecision markDelivered(String topicId, long seq) {
            SubscriptionState subscription = subscriptions.get(topicId);
            if (subscription == null) {
                return DeliveryDecision.notSubscribed();
            }
            touch();
            return subscription.markDelivered(seq);
        }

        private void close() {
            subscriptions.values().forEach(SubscriptionState::dispose);
            subscriptions.clear();
            touch();
        }

        private void touch() {
            lastActiveAt = Instant.now();
        }
    }

    /**
     * 单个 topic 的订阅状态。
     */
    private static final class SubscriptionState {
        private static final int MAX_REMEMBERED_DELIVERIES = 2048;

        private final String topicId;
        private final Disposable disposable;
        private final Map<Long, Boolean> deliveredSeqs = new DeliveredSeqMap();
        private volatile long lastAckSeq;
        private volatile long highestDeliveredSeq;

        private SubscriptionState(String topicId, long afterSeq, Disposable disposable) {
            this.topicId = topicId;
            this.lastAckSeq = afterSeq;
            this.highestDeliveredSeq = afterSeq;
            this.disposable = disposable;
        }

        private void ack(long seq) {
            if (seq > lastAckSeq) {
                lastAckSeq = seq;
            }
        }

        private synchronized DeliveryDecision markDelivered(long seq) {
            if (seq <= lastAckSeq || deliveredSeqs.containsKey(seq)) {
                return DeliveryDecision.duplicate(lastAckSeq, seq);
            }
            if (seq < highestDeliveredSeq) {
                return DeliveryDecision.recoverRequired(lastAckSeq, seq);
            }
            deliveredSeqs.put(seq, Boolean.TRUE);
            highestDeliveredSeq = Math.max(highestDeliveredSeq, seq);
            return DeliveryDecision.deliver(lastAckSeq, seq);
        }

        private void dispose() {
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
            }
        }

        @Override
        public String toString() {
            return topicId + "@" + highestDeliveredSeq + "/" + lastAckSeq;
        }

        private static final class DeliveredSeqMap extends LinkedHashMap<Long, Boolean> {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                return size() > MAX_REMEMBERED_DELIVERIES;
            }
        }
    }

    /**
     * 单条事件在连接侧的投递决策。
     *
     * @param action 投递动作。
     * @param lastAckSeq 客户端最近确认消费到的序号。
     * @param actualSeq 当前事件序号。
     */
    public record DeliveryDecision(Action action, long lastAckSeq, long actualSeq) {
        public static DeliveryDecision deliver(long lastAckSeq, long actualSeq) {
            return new DeliveryDecision(Action.DELIVER, lastAckSeq, actualSeq);
        }

        public static DeliveryDecision duplicate(long lastAckSeq, long actualSeq) {
            return new DeliveryDecision(Action.DUPLICATE, lastAckSeq, actualSeq);
        }

        public static DeliveryDecision recoverRequired(long lastAckSeq, long actualSeq) {
            return new DeliveryDecision(Action.RECOVER_REQUIRED, lastAckSeq, actualSeq);
        }

        public static DeliveryDecision notSubscribed() {
            return new DeliveryDecision(Action.NOT_SUBSCRIBED, 0L, 0L);
        }
    }

    /**
     * 事件投递动作。
     */
    public enum Action {
        DELIVER,
        DUPLICATE,
        RECOVER_REQUIRED,
        NOT_SUBSCRIBED
    }
}
