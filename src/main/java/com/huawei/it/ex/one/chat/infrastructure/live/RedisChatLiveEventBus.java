package com.huawei.it.ex.one.chat.infrastructure.live;

import com.huawei.it.ex.one.common.redis.config.ChatLiveEventBusProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.chat.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.common.instance.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.chat.application.publisher.ChatLiveEventBus;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.common.redis.FinanceExRedisKeyBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 基于 Redis Pub/Sub 的跨实例聊天实时事件总线。
 *
 * <p>该实现只传播已经写入数据库的事件。Redis 不可用时，本机 WebSocket 与 Event Resume 仍可工作；
 * 跨实例实时推送会退化为前端使用 {@code afterSeq} 重新补发。Redis Cluster 下不使用全局 pattern
 * 订阅，而是在本机出现 run topic 订阅者时动态订阅对应 channel，减少集群广播和模式订阅的不确定性。</p>
 */
@Component
public class RedisChatLiveEventBus implements ChatLiveEventBus, MessageListener {
    private static final AppLogger log = AppLoggerFactory.getLogger(RedisChatLiveEventBus.class);

    private final RedisMessageListenerContainer listenerContainer;
    private final RedisChatLiveEventPublisher publisher;
    private final RedisChatLiveEventSubscriber subscriber;

    @Autowired
    public RedisChatLiveEventBus(StringRedisTemplate redis, ObjectMapper objectMapper,
                                 RedisConnectionFactory connectionFactory,
                                 FinanceExRedisKeyBuilder redisKeys,
                                 ApplicationInstanceIdProvider instanceIdProvider,
                                 ChatLiveEventBusProperties properties,
                                 @Qualifier("redisChatLivePublishExecutor") Executor publishExecutor) {
        ChatLiveEventBusProperties resolvedProperties = properties == null
                ? new ChatLiveEventBusProperties() : properties;
        Executor resolvedExecutor = publishExecutor == null ? Runnable::run : publishExecutor;
        this.listenerContainer = new RedisMessageListenerContainer();
        this.listenerContainer.setConnectionFactory(connectionFactory);
        this.publisher = new RedisChatLiveEventPublisher(redis, objectMapper, redisKeys, instanceIdProvider,
                resolvedProperties, resolvedExecutor, log);
        this.subscriber = new RedisChatLiveEventSubscriber(objectMapper, redisKeys, instanceIdProvider,
                listenerContainer, this, log);
    }

    @Autowired
    void setChatStreamProperties(ChatStreamProperties chatStreamProperties) {
        subscriber.setChatStreamProperties(chatStreamProperties);
    }

    RedisChatLiveEventBus(StringRedisTemplate redis, ObjectMapper objectMapper,
                          RedisConnectionFactory connectionFactory,
                          FinanceExRedisKeyBuilder redisKeys,
                          ApplicationInstanceIdProvider instanceIdProvider) {
        this(redis, objectMapper, connectionFactory, redisKeys, instanceIdProvider,
                new ChatLiveEventBusProperties(), Runnable::run);
    }

    /** 启动 Redis listener 容器；具体 channel 在 {@link #subscribe(String)} 时按 topic 动态注册。 */
    @PostConstruct
    public void start() {
        try {
            listenerContainer.afterPropertiesSet();
            listenerContainer.start();
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_UNAVAILABLE,
                            "Redis live event listener failed to start; cross-instance delivery is degraded")
                    .operation("chat-live-bus.start")
                    .build(), ex);
        }
    }

    /** 关闭 Redis listener。 */
    @PreDestroy
    public void stop() {
        try {
            listenerContainer.stop();
            listenerContainer.destroy();
        } catch (Exception ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_SUBSCRIBE_FAILED,
                            "Redis live event listener failed to stop cleanly")
                    .operation("chat-live-bus.stop")
                    .retryable(false)
                    .build(), ex);
        } finally {
            publisher.clear();
        }
    }

    @Override
    public void publish(String topicId, ChatEvent event) {
        publisher.publish(topicId, event);
    }

    @Override
    public Flux<ChatEvent> subscribe(String topicId) {
        return subscriber.subscribe(topicId);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        subscriber.onMessage(message);
    }
}
