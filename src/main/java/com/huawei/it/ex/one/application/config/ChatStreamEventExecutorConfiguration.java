package com.huawei.it.ex.one.application.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 流式事件阻塞 IO 调度器配置。
 *
 * <p>下游 Runtime 事件可能由 Reactor timer 或网络回调线程触发；事件落库、run 状态推进和 Redis
 * 实时发布都是阻塞式调用，必须切换到专用调度器，避免占用 {@code parallel-*} 或 Servlet 请求线程。</p>
 */
@Configuration
@EnableConfigurationProperties(DomainAgentProperties.class)
public class ChatStreamEventExecutorConfiguration {

    @Bean(name = "chatStreamEventScheduler", destroyMethod = "dispose")
    public Scheduler chatStreamEventScheduler(ChatStreamProperties properties) {
        return Schedulers.newBoundedElastic(
                properties.normalizedEventIoExecutorMaxSize(),
                properties.normalizedEventIoExecutorQueueCapacity(),
                "finex-chat-event-io"
        );
    }

    @Bean(name = "domainAgentControlIoScheduler", destroyMethod = "dispose")
    public Scheduler domainAgentControlIoScheduler(DomainAgentProperties properties) {
        return Schedulers.newBoundedElastic(
                properties.normalizedControlIoExecutorMaxSize(),
                properties.normalizedControlIoExecutorQueueCapacity(),
                "finex-domain-agent-control-io"
        );
    }
}
