package com.huawei.it.ex.one.interfaces.chat.websocket;

import com.huawei.it.ex.one.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.domain.auth.UserContext;

import reactor.core.Disposable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 当前服务实例内的 WebSocket 连接注册表。
 *
 * <p>该注册表只保存运行态连接状态，不作为业务事实源。它用于连接级身份隔离、订阅释放、
 * 去重和排障；跨实例事件仍通过 Redis Pub/Sub 扇出，事件事实源仍是数据库。</p>
 */
@Component
public class LocalWebSocketConnectionRegistry {
    private final Map<String, ConnectionState> connections = new ConcurrentHashMap<>();
    private final ChatWebSocketProperties properties;

    public LocalWebSocketConnectionRegistry() {
        this(new ChatWebSocketProperties());
    }

    @Autowired
    public LocalWebSocketConnectionRegistry(ChatWebSocketProperties properties) {
        this.properties = properties;
    }

    /**
     * 注册一条已完成握手鉴权的 WebSocket 连接。
     *
     * @param connectionId WebSocket 物理连接 ID。
     * @param user 当前连接的用户身份快照。
     * @return 连接状态。
     */
    public synchronized ConnectionState register(String connectionId, UserContext user) {
        long currentConnections = connections.values().stream()
                .filter(state -> state.tenantId().equals(user.tenantId()))
                .filter(state -> state.userId().equals(user.ownerUserId()))
                .count();
        if (currentConnections >= properties.normalizedMaxConnectionsPerUser()) {
            throw new IllegalStateException("WS_CONNECTION_LIMIT_EXCEEDED: 当前用户 WebSocket 连接数已达上限");
        }
        ConnectionState state = new ConnectionState(connectionId, user.tenantId(), user.ownerUserId(), user.username());
        ConnectionState previous = connections.put(connectionId, state);
        if (previous != null) {
            // 正常情况下 WebSocket sessionId 不会复用；这里做防御性释放，避免异常重连污染 topic 订阅。
            previous.close();
        }
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
     * <p>WebSocket 是用户级连接，同一物理连接允许同时订阅多个 session 的 run topic。
     * 串线防护依赖 topic 归属校验和事件输出前的 runId/sessionId 校验，而不是在服务端强制
     * 释放其他 session 订阅。</p>
     *
     * @param connectionId WebSocket 物理连接 ID。
     * @param topicId run 级 stream topic。
     * @param sessionId topic 所属前端会话 ID，用于同一连接内跨会话订阅隔离。
     * @param afterSeq 订阅起点，服务端不会向该连接重复投递小于等于该值的事件。
     * @param disposable 当前 topic 实时事件订阅句柄，取消订阅或断开连接时释放。
     */
    public synchronized void subscribe(String connectionId, String topicId, String sessionId, long afterSeq, Disposable disposable) {
        ConnectionState state = connections.get(connectionId);
        if (state == null) {
            /*
             * subscribe 与连接关闭可能并发发生。这里必须显式失败，避免调用方继续启动
             * 无法被 registry 持有和释放的 live 订阅。
             */
            throw new IllegalStateException("WS_CONNECTION_NOT_FOUND: WebSocket 连接已关闭");
        }
        if (!state.subscriptions.containsKey(topicId)
                && state.subscriptionCount() >= properties.normalizedMaxSubscriptionsPerConnection()) {
            throw new IllegalStateException("WS_SUBSCRIPTION_LIMIT_EXCEEDED: 当前连接订阅 topic 数已达上限");
        }
        if (!state.subscriptions.containsKey(topicId)
                && topicSubscriberCount(topicId) >= properties.normalizedMaxSubscribersPerTopic()) {
            throw new IllegalStateException("WS_TOPIC_SUBSCRIBER_LIMIT_EXCEEDED: 当前 topic 本机订阅数已达上限");
        }
        state.subscribe(topicId, sessionId, afterSeq, disposable, properties.normalizedDeliveredSeqWindow());
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
     * 判断事件是否应发送到该连接，并记录已发送序号。
     *
     * @param connectionId WebSocket 物理连接 ID。
     * @param topicId run 级 stream topic。
     * @param seq 准备投递的事件序号。
     * @return 投递决策；乱序事件会要求前端使用 Event Resume 恢复，而不是静默丢弃。
     */
    public DeliveryDecision markDelivered(String connectionId, String topicId, long seq) {
        return get(connectionId).map(state -> state.markDelivered(topicId, seq)).orElse(DeliveryDecision.notSubscribed());
    }

    /**
     * 注销连接并释放全部订阅。
     *
     * @param connectionId WebSocket 物理连接 ID。
     */
    public synchronized void unregister(String connectionId) {
        ConnectionState state = connections.remove(connectionId);
        if (state != null) {
            state.close();
        }
    }

    /**
     * 判断连接是否已超过空闲时间。
     *
     * @param connectionId WebSocket 物理连接 ID。
     * @param idleTimeout 空闲阈值；小于等于 0 表示不判定空闲。
     * @return true 表示连接存在且已空闲超时。
     */
    public boolean idleForLongerThan(String connectionId, Duration idleTimeout) {
        if (idleTimeout == null || idleTimeout.isZero() || idleTimeout.isNegative()) {
            return false;
        }
        return get(connectionId)
                .map(state -> state.lastActiveAt().plus(idleTimeout).isBefore(Instant.now()))
                .orElse(false);
    }

    private long topicSubscriberCount(String topicId) {
        return connections.values().stream()
                .filter(state -> state.subscriptions.containsKey(topicId))
                .count();
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

        private void subscribe(String topicId, String sessionId, long afterSeq, Disposable disposable, int deliveredSeqWindow) {
            unsubscribe(topicId);
            subscriptions.put(topicId, new SubscriptionState(topicId, sessionId, afterSeq, disposable, deliveredSeqWindow));
            touch();
        }

        private void unsubscribe(String topicId) {
            SubscriptionState previous = subscriptions.remove(topicId);
            if (previous != null) {
                previous.dispose();
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
        private final String topicId;
        private final String sessionId;
        private final Disposable disposable;
        private final Map<Long, Boolean> deliveredSeqs;
        private final long resumeAfterSeq;
        private volatile long highestDeliveredSeq;

        private SubscriptionState(String topicId, String sessionId, long afterSeq, Disposable disposable, int deliveredSeqWindow) {
            this.topicId = topicId;
            this.sessionId = sessionId;
            this.resumeAfterSeq = afterSeq;
            this.highestDeliveredSeq = afterSeq;
            this.disposable = disposable;
            this.deliveredSeqs = new DeliveredSeqMap(deliveredSeqWindow);
        }

        private synchronized DeliveryDecision markDelivered(long seq) {
            if (seq <= resumeAfterSeq || deliveredSeqs.containsKey(seq)) {
                return DeliveryDecision.duplicate(resumeAfterSeq, seq, highestDeliveredSeq);
            }
            if (seq < highestDeliveredSeq) {
                return DeliveryDecision.recoverRequired(resumeAfterSeq, seq, highestDeliveredSeq);
            }
            deliveredSeqs.put(seq, Boolean.TRUE);
            highestDeliveredSeq = Math.max(highestDeliveredSeq, seq);
            return DeliveryDecision.deliver(resumeAfterSeq, seq, highestDeliveredSeq);
        }

        private void dispose() {
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
            }
        }

        @Override
        public String toString() {
            return topicId + "@" + highestDeliveredSeq + "/" + resumeAfterSeq;
        }

        private static final class DeliveredSeqMap extends LinkedHashMap<Long, Boolean> {
            private final int maxSize;

            private DeliveredSeqMap(int maxSize) {
                this.maxSize = maxSize;
            }

            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                return size() > maxSize;
            }
        }
    }

    /**
     * 单条事件在连接侧的投递决策。
     *
     * @param action 投递动作。
     * @param resumeAfterSeq 当前订阅声明的恢复起点。
     * @param actualSeq 当前事件序号。
     * @param recoveryAfterSeq 建议 Event Resume 使用的恢复起点；仅 RECOVER_REQUIRED 有业务意义。
     * @param highestDeliveredSeq 当前 topic 已成功投递的最高事件序号。
     */
    public record DeliveryDecision(Action action, long resumeAfterSeq, long actualSeq,
                                   long recoveryAfterSeq, long highestDeliveredSeq) {
        public static DeliveryDecision deliver(long resumeAfterSeq, long actualSeq, long highestDeliveredSeq) {
            return new DeliveryDecision(Action.DELIVER, resumeAfterSeq, actualSeq, resumeAfterSeq, highestDeliveredSeq);
        }

        public static DeliveryDecision duplicate(long resumeAfterSeq, long actualSeq, long highestDeliveredSeq) {
            return new DeliveryDecision(Action.DUPLICATE, resumeAfterSeq, actualSeq, resumeAfterSeq, highestDeliveredSeq);
        }

        public static DeliveryDecision recoverRequired(long resumeAfterSeq, long actualSeq, long highestDeliveredSeq) {
            /*
             * seq 是数据库事件游标，不是 run/topic 内连续序号。这里不判断 expectedNext，只在已经
             * 投递更高 seq 后又收到更低但未见过的 seq 时要求恢复。恢复点从 actualSeq - 1 开始，
             * 可以覆盖迟到事件本身，同时避免新连接 afterSeq=0 时把整轮事件都要求重放。
             */
            long recoveryAfterSeq = Math.max(resumeAfterSeq, Math.max(0L, actualSeq - 1));
            return new DeliveryDecision(Action.RECOVER_REQUIRED, resumeAfterSeq, actualSeq,
                    recoveryAfterSeq, highestDeliveredSeq);
        }

        public static DeliveryDecision notSubscribed() {
            return new DeliveryDecision(Action.NOT_SUBSCRIBED, 0L, 0L, 0L, 0L);
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
