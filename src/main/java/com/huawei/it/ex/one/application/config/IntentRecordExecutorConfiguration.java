/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Servlet/MVC 生产模式下的意图记录异步线程池配置。
 *
 * <p>该线程池只处理统计排障写入，不参与聊天主链路。拒绝策略使用 AbortPolicy，由调用方捕获后
 * 丢弃记录，避免队列满时反向阻塞 Servlet 请求线程。</p>
 */
@Configuration
public class IntentRecordExecutorConfiguration {
    @Bean(name = "intentRecognitionRecordExecutor")
    public ThreadPoolTaskExecutor intentRecognitionRecordExecutor(IntentRecordProperties properties) {
        IntentRecordProperties.Executor executorProperties = properties.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("finex-intent-record-");
        executor.setCorePoolSize(executorProperties.normalizedCoreSize());
        executor.setMaxPoolSize(executorProperties.normalizedMaxSize());
        executor.setQueueCapacity(executorProperties.normalizedQueueCapacity());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
