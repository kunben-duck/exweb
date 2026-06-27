package com.huawei.finance.front.one.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.finance.front.one.application.integration.identity.ApplicationInstanceIdProvider;
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
    private final ApplicationInstanceIdProvider instanceIdProvider;
    private final RedisMessageListenerContainer listenerContainer;
    private final Map<String, TopicSink> topicSinks = new ConcurrentHashMap<>();

    public RedisChatLiveEventBus(StringRedisTemplate redis, ObjectMapper objectMapper,
                                 RedisConnectionFactory connectionFactory,
                                 FinanceExRedisKeyBuilder redisKeys,
                                 ApplicationInstanceIdProvider instanceIdProvider) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.redisKeys = redisKeys;
        this.instanceIdProvider = instanceIdProvider;
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
            redis.convertAndSend(channel(topicId), objectMapper.writeValueAsString(
                    ChatLiveEventPayload.from(event, instanceIdProvider.currentInstanceId())));
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
            ChatLiveEventPayload payload = objectMapper.readValue(body, ChatLiveEventPayload.class);
            if (isPublishedByCurrentInstance(payload)) {
                log.debug("Redis ChatLiveEventBus 丢弃本实例回声，topicId={}, seq={}, instanceId={}",
                        topicId, payload.sequence(), payload.publisherInstanceId());
                if (terminal(payload.eventType())) {
                    completeTopic(topicId, sink);
                }
                return;
            }
            ChatEvent event = payload.toEvent();
            Sinks.EmitResult nextResult = sink.emitNext(event);
            if (nextResult.isFailure()) {
                handleEmitFailure(topicId, sink, event, nextResult);
                return;
            }
            if (terminal(event)) {
                completeTopic(topicId, sink);
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
        return event != null && terminal(event.type());
    }

    private boolean terminal(String eventType) {
        return "run.completed".equals(eventType) || "run.failed".equals(eventType) || "run.cancelled".equals(eventType);
    }

    private void completeTopic(String topicId, TopicSink sink) {
        Sinks.EmitResult completeResult = sink.emitComplete();
        if (completeResult.isFailure()) {
            logEmitFailure("REDIS_EMIT_COMPLETE_FAILED", topicId, null, completeResult);
        }
        unregisterTopic(topicId, sink);
        topicSinks.remove(topicId, sink);
    }

    private void handleEmitFailure(String topicId, TopicSink sink, ChatEvent event, Sinks.EmitResult result) {
        logEmitFailure("REDIS_EMIT_NEXT_FAILED", topicId, event, result);
        if (result == Sinks.EmitResult.FAIL_TERMINATED || result == Sinks.EmitResult.FAIL_CANCELLED) {
            topicSinks.remove(topicId, sink);
            unregisterTopic(topicId, sink);
            return;
        }
        /*
         * Redis Pub/Sub 不是可靠队列，不能在本地堆积历史。只有无法确认业务事件是否已实时投递时，
         * 才结束该 live 流，上层会引导前端使用数据库事实源做 Event Resume。
         */
        Sinks.EmitResult errorResult = sink.emitError(new IllegalStateException(
                "redis live sink emit failed, seq=" + event.sequence() + ", result=" + result));
        if (errorResult.isFailure()) {
            logEmitFailure("REDIS_EMIT_ERROR_FAILED", topicId, event, errorResult);
        }
    }

    private void logEmitFailure(String reason, String topicId, ChatEvent event, Sinks.EmitResult result) {
        String threadName = Thread.currentThread().getName();
        if (result == Sinks.EmitResult.FAIL_TERMINATED || result == Sinks.EmitResult.FAIL_CANCELLED) {
            log.debug("Redis ChatLiveEventBus 投递已结束 topic，reason={}, topicId={}, seq={}, result={}, thread={}",
                    reason, topicId, event == null ? null : event.sequence(), result, threadName);
            return;
        }
        log.warn("Redis ChatLiveEventBus 投递失败，reason={}, topicId={}, runId={}, sessionId={}, seq={}, result={}, thread={}",
                reason, topicId, event == null ? null : event.runId(), event == null ? null : event.sessionId(),
                event == null ? null : event.sequence(), result, threadName);
    }

    private boolean isPublishedByCurrentInstance(ChatLiveEventPayload payload) {
        /*
         * 滚动发布期间旧版本 payload 可能没有 publisherInstanceId。缺失时按远端事件处理，
         * 避免升级过程丢跨实例实时消息；新版本同实例 self-echo 则必须丢弃，防止 local sink
         * 与 Redis Pub/Sub 合并后重复/迟到触发 RECOVER_REQUIRED。
         */
        return payload.publisherInstanceId() != null
                && payload.publisherInstanceId().equals(instanceIdProvider.currentInstanceId());
    }

    /**
     * Redis Pub/Sub 中传输的最小事件快照。
     *
     * @param publisherInstanceId 发布该事件的应用实例 ID；旧版本可能为空。
     * @param runId 本轮执行追踪标识。
     * @param sessionId 前端聊天会话标识。
     * @param sequence 持久化事件序号。
     * @param eventType 事件类型。
     * @param createdAt 事件创建时间。
     * @param payload 事件载荷。
     */
    private record ChatLiveEventPayload(
            String publisherInstanceId,
            String runId,
            String sessionId,
            long sequence,
            String eventType,
            Instant createdAt,
            Map<String, Object> payload
    ) {
        private static ChatLiveEventPayload from(ChatEvent event, String publisherInstanceId) {
            return new ChatLiveEventPayload(publisherInstanceId, event.runId(), event.sessionId(),
                    event.sequence(), event.type(),
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

        private synchronized Sinks.EmitResult emitNext(ChatEvent event) {
            return sink.tryEmitNext(event);
        }

        private synchronized Sinks.EmitResult emitComplete() {
            return sink.tryEmitComplete();
        }

        private synchronized Sinks.EmitResult emitError(Throwable error) {
            return sink.tryEmitError(error);
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
