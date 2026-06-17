package com.huawei.finance.front.one.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.StoredChatEvent;
import com.huawei.finance.front.one.infrastructure.redis.FinanceExRedisKeyBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * 基于 Redis Pub/Sub 的跨实例聊天实时事件总线。
 *
 * <p>该实现只传播已经写入数据库的事件。Redis 不可用时，本机 WebSocket 与 Event Resume 仍可工作；
 * 跨实例实时推送会退化为前端使用 {@code afterSeq} 重新补发。Redis Cluster 下不使用全局 pattern
 * 订阅，而是在本机出现 run topic 订阅者时动态订阅对应 channel，减少集群广播和模式订阅的不确定性。</p>
 */
@Component
public class RedisChatLiveEventBus implements ChatLiveEventBus, MessageListener {
    private static final Logger log = LoggerFactory.getLogger(RedisChatLiveEventBus.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FinanceExRedisKeyBuilder redisKeys;
    private final RedisMessageListenerContainer listenerContainer;
    private final Map<String, TopicSink> topicSinks = new ConcurrentHashMap<>();

    public RedisChatLiveEventBus(StringRedisTemplate redis, ObjectMapper objectMapper,
                                 RedisConnectionFactory connectionFactory,
                                 FinanceExRedisKeyBuilder redisKeys) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.redisKeys = redisKeys;
        this.listenerContainer = new RedisMessageListenerContainer();
        this.listenerContainer.setConnectionFactory(connectionFactory);
    }

    /**
     * 启动 Redis listener 容器。
     *
     * <p>具体 channel 在 {@link #subscribe(String)} 时按 topic 动态注册。</p>
     */
    @PostConstruct
    public void start() {
        try {
            listenerContainer.afterPropertiesSet();
            listenerContainer.start();
        } catch (RuntimeException ex) {
            log.warn("Redis ChatLiveEventBus 启动失败，跨实例 WebSocket 实时推送将降级。原因：{}", ex.getMessage());
        }
    }

    /**
     * 关闭 Redis listener。
     */
    @PreDestroy
    public void stop() {
        try {
            listenerContainer.stop();
            listenerContainer.destroy();
        } catch (Exception ex) {
            log.warn("Redis ChatLiveEventBus 关闭失败。原因：{}", ex.getMessage());
        }
    }

    @Override
    public void publish(String topicId, ChatEvent event) {
        if (topicId == null || topicId.isBlank() || event == null) {
            return;
        }
        try {
            redis.convertAndSend(channel(topicId), objectMapper.writeValueAsString(ChatLiveEventPayload.from(event)));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Redis ChatLiveEventBus 发布失败，topicId={}, seq={}, reason={}",
                    topicId, event.sequence(), ex.getMessage());
        }
    }

    @Override
    public Flux<ChatEvent> subscribe(String topicId) {
        if (topicId == null || topicId.isBlank()) {
            return Flux.empty();
        }
        TopicSink topic = topicSinks.computeIfAbsent(topicId, this::registerTopic);
        topic.subscribers().incrementAndGet();
        return topic.sink().asFlux()
                .doFinally(signalType -> {
                    if (topic.subscribers().decrementAndGet() <= 0) {
                        topicSinks.remove(topicId, topic);
                        unregisterTopic(topicId, topic);
                    }
                });
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String topicId = topicFromChannel(new String(message.getChannel(), StandardCharsets.UTF_8));
        TopicSink sink = topicSinks.get(topicId);
        if (sink == null) {
            return;
        }
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            ChatEvent event = objectMapper.readValue(body, ChatLiveEventPayload.class).toEvent();
            Sinks.EmitResult nextResult = sink.sink().tryEmitNext(event);
            if (nextResult.isFailure()) {
                log.warn("Redis ChatLiveEventBus 本机投递失败，topicId={}, seq={}, result={}",
                        topicId, event.sequence(), nextResult);
                /*
                 * Redis Pub/Sub 不是可靠队列，不能在本地堆积历史。投递失败时结束该 live 流，
                 * 上层 ChatStreamApplicationService 会把错误转换成 RECOVER_REQUIRED，引导前端
                 * 使用数据库事实源做 Event Resume。
                 */
                sink.sink().tryEmitError(new IllegalStateException(
                        "redis live sink emit failed, seq=" + event.sequence() + ", result=" + nextResult));
            }
            if (terminal(event)) {
                Sinks.EmitResult completeResult = sink.sink().tryEmitComplete();
                if (completeResult.isFailure()) {
                    log.warn("Redis ChatLiveEventBus 本机结束 topic 失败，topicId={}, result={}", topicId, completeResult);
                }
                unregisterTopic(topicId, sink);
                topicSinks.remove(topicId, sink);
            }
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Redis ChatLiveEventBus 消息解析失败，topicId={}, reason={}", topicId, ex.getMessage());
        }
    }

    private String channel(String topicId) {
        return redisKeys.chatStreamChannel(topicId);
    }

    private TopicSink registerTopic(String topicId) {
        ChannelTopic redisTopic = new ChannelTopic(channel(topicId));
        TopicSink topic = new TopicSink(redisTopic);
        try {
            listenerContainer.addMessageListener(this, redisTopic);
        } catch (RuntimeException ex) {
            log.warn("Redis ChatLiveEventBus 订阅失败，topicId={}，跨实例实时推送将依赖 Event Resume。原因：{}",
                    topicId, ex.getMessage());
            topic.markRegistrationFailed();
        }
        return topic;
    }

    private void unregisterTopic(String topicId, TopicSink topic) {
        if (!topic.registered()) {
            return;
        }
        try {
            listenerContainer.removeMessageListener(this, topic.redisTopic());
            topic.markUnregistered();
        } catch (RuntimeException ex) {
            log.warn("Redis ChatLiveEventBus 取消订阅失败，topicId={}，原因：{}", topicId, ex.getMessage());
        }
    }

    private String topicFromChannel(String channel) {
        return redisKeys.topicFromChatStreamChannel(channel);
    }

    private boolean terminal(ChatEvent event) {
        return "run.completed".equals(event.type()) || "run.failed".equals(event.type()) || "run.cancelled".equals(event.type());
    }

    /**
     * Redis Pub/Sub 中传输的最小事件快照。
     *
     * @param runId 本轮执行追踪标识。
     * @param sessionId 前端聊天会话标识。
     * @param sequence 持久化事件序号。
     * @param eventType 事件类型。
     * @param createdAt 事件创建时间。
     * @param payload 事件载荷。
     */
    private record ChatLiveEventPayload(
            String runId,
            String sessionId,
            long sequence,
            String eventType,
            Instant createdAt,
            Map<String, Object> payload
    ) {
        private static ChatLiveEventPayload from(ChatEvent event) {
            return new ChatLiveEventPayload(event.runId(), event.sessionId(), event.sequence(), event.type(),
                    event.createdAt(), event.payload());
        }

        private ChatEvent toEvent() {
            return new StoredChatEvent(runId, sessionId, sequence, eventType,
                    createdAt == null ? Instant.EPOCH : createdAt, payload == null ? Map.of() : payload);
        }
    }

    private static class TopicSink {
        private final Sinks.Many<ChatEvent> sink = Sinks.many().multicast()
                .onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);
        private final AtomicInteger subscribers = new AtomicInteger();
        private final ChannelTopic redisTopic;
        private volatile boolean registered = true;

        private TopicSink(ChannelTopic redisTopic) {
            this.redisTopic = redisTopic;
        }

        private Sinks.Many<ChatEvent> sink() {
            return sink;
        }

        private AtomicInteger subscribers() {
            return subscribers;
        }

        private ChannelTopic redisTopic() {
            return redisTopic;
        }

        private boolean registered() {
            return registered;
        }

        private void markRegistrationFailed() {
            this.registered = false;
        }

        private void markUnregistered() {
            this.registered = false;
        }
    }
}
