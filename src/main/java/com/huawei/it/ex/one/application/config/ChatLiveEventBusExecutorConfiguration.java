package com.huawei.it.ex.one.application.config;

import com.huawei.it.ex.one.infrastructure.persistence.ChatLiveEventBusProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Redis 实时事件发布执行器。
 *
 * <p>Redis Pub/Sub 是跨实例实时 fanout 通道，发布失败不能拖慢 run 主链路；因此使用独立有界
 * 执行器承载阻塞式 {@code convertAndSend}，避免占用 Servlet 或 Reactor timer 线程。</p>
 */
@Configuration
public class ChatLiveEventBusExecutorConfiguration {

    @Bean(name = "redisChatLivePublishExecutor")
    public ThreadPoolTaskExecutor redisChatLivePublishExecutor(ChatLiveEventBusProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("finex-redis-live-publish-");
        executor.setCorePoolSize(properties.normalizedRedisPublishExecutorCoreSize());
        executor.setMaxPoolSize(properties.normalizedRedisPublishExecutorMaxSize());
        executor.setQueueCapacity(properties.normalizedRedisPublishQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler((task, ignored) -> {
            throw new IllegalStateException("Redis live publish executor queue is full");
        });
        executor.initialize();
        return executor;
    }
}
