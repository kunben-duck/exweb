package com.huawei.it.ex.one.intent.infrastructure.config;

import com.huawei.it.ex.one.intent.application.config.RouteMemoryProperties;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * RouteMemory 是意图上下文增强能力，读写必须与聊天主链路隔离。
 */
@Configuration
public class RouteMemoryExecutorConfiguration {

    @Bean(name = "routeMemoryReadExecutor")
    public ThreadPoolTaskExecutor routeMemoryReadExecutor(RouteMemoryProperties properties) {
        return createExecutor("finex-route-memory-read-", properties.getReadExecutor());
    }

    @Bean(name = "routeMemoryWriteExecutor")
    public ThreadPoolTaskExecutor routeMemoryWriteExecutor(RouteMemoryProperties properties) {
        return createExecutor("finex-route-memory-write-", properties.getWriteExecutor());
    }

    private ThreadPoolTaskExecutor createExecutor(String threadNamePrefix,
                                                  RouteMemoryProperties.Executor executorProperties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setCorePoolSize(executorProperties.normalizedCoreSize());
        executor.setMaxPoolSize(executorProperties.normalizedMaxSize());
        executor.setQueueCapacity(executorProperties.normalizedQueueCapacity());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
