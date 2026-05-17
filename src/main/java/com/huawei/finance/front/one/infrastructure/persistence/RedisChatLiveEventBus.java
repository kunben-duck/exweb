package com.huawei.finance.front.one.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.StoredChatEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 基于 Redis Pub/Sub 的跨实例聊天实时事件总线。
 *
 * <p>该实现只传播已经写入 openGauss 的事件。Redis 不可用时，本机 WebSocket 与 SSE 恢复仍可工作；
 * 跨实例实时推送会退化为前端使用 {@code afterSeq} 重新补发。</p>
 */
@Component
@EnableConfigurationProperties(ChatLiveEventBusProperties.class)
public class RedisChatLiveEventBus implements ChatLiveEventBus, MessageListener {
    private static final Logger log = LoggerFactory.getLogger(RedisChatLiveEventBus.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ChatLiveEventBusProperties properties;
    private final RedisMessageListenerContainer listenerContainer;
    private final Map<String, TopicSink> topicSinks = new ConcurrentHashMap<>();

    public RedisChatLiveEventBus(StringRedisTemplate redis, ObjectMapper objectMapper,
                                 ChatLiveEventBusProperties properties, RedisConnectionFactory connectionFactory) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.listenerContainer = new RedisMessageListenerContainer();
        this.listenerContainer.setConnectionFactory(connectionFactory);
    }

    /**
     * 启动 Redis pattern 订阅。
     */
    @PostConstruct
    public void start() {
        try {
            listenerContainer.addMessageListener(this, new PatternTopic(channel("*")));
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
        TopicSink topic = topicSinks.computeIfAbsent(topicId, ignored -> new TopicSink());
        topic.subscribers().incrementAndGet();
        return topic.sink().asFlux()
                .doFinally(signalType -> {
                    if (topic.subscribers().decrementAndGet() <= 0) {
                        topicSinks.remove(topicId, topic);
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
            }
            if (terminal(event)) {
                Sinks.EmitResult completeResult = sink.sink().tryEmitComplete();
                if (completeResult.isFailure()) {
                    log.warn("Redis ChatLiveEventBus 本机结束 topic 失败，topicId={}, result={}", topicId, completeResult);
                }
                topicSinks.remove(topicId, sink);
            }
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Redis ChatLiveEventBus 消息解析失败，topicId={}, reason={}", topicId, ex.getMessage());
        }
    }

    private String channel(String topicId) {
        return properties.getRedisChannelPrefix() + ":" + topicId;
    }

    private String topicFromChannel(String channel) {
        String prefix = properties.getRedisChannelPrefix() + ":";
        return channel != null && channel.startsWith(prefix) ? channel.substring(prefix.length()) : channel;
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

    private record TopicSink(Sinks.Many<ChatEvent> sink, AtomicInteger subscribers) {
        private TopicSink() {
            this(Sinks.many().replay().limit(512), new AtomicInteger());
        }
    }
}
