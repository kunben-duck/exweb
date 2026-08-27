package com.huawei.it.ex.one.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** Dedicated bounded executors keep preference persistence isolated from RouteMemory and chat workers. */
@Configuration
public class IntentPreferenceExecutorConfiguration {

    @Bean(name = "intentPreferenceReadExecutor")
    public ThreadPoolTaskExecutor intentPreferenceReadExecutor(RouteMemoryProperties properties) {
        return createExecutor("finex-intent-preference-read-", properties.getReadExecutor());
    }

    @Bean(name = "intentPreferenceWriteExecutor")
    public ThreadPoolTaskExecutor intentPreferenceWriteExecutor(RouteMemoryProperties properties) {
        return createExecutor("finex-intent-preference-write-", properties.getWriteExecutor());
    }

    private ThreadPoolTaskExecutor createExecutor(
            String threadNamePrefix, RouteMemoryProperties.Executor executorProperties) {
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
