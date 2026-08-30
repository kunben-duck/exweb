/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Servlet WebSocket 阻塞发送线程池。
 *
 * <p>每条连接已经有独立有界队列，因此这里不再增加全局任务队列；当慢连接过多导致发送线程耗尽时，
 * 新的 drain 任务会被拒绝并关闭对应 WebSocket，业务 run 仍可通过 Event Resume 恢复。</p>
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ChatServletWebSocketSendExecutorConfiguration {
    @Bean(name = "chatServletWebSocketSendExecutor", destroyMethod = "shutdown")
    public ExecutorService chatServletWebSocketSendExecutor(ChatWebSocketProperties properties) {
        if (properties.isServletSendUseVirtualThreads()) {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
        int coreSize = properties.normalizedServletSendExecutorCoreSize();
        int maxSize = properties.normalizedServletSendExecutorMaxSize();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                coreSize,
                maxSize,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                namedThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private ThreadFactory namedThreadFactory() {
        CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("finex-ws-send-");
        threadFactory.setDaemon(true);
        return threadFactory;
    }
}
