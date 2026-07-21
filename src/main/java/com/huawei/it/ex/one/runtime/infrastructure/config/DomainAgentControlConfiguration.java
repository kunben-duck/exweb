package com.huawei.it.ex.one.runtime.infrastructure.config;

import com.huawei.it.ex.one.runtime.application.model.DomainAgentControlPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Owns DomainAgent control policy and its isolated blocking-IO scheduler. */
@Configuration
@EnableConfigurationProperties(DomainAgentProperties.class)
public class DomainAgentControlConfiguration {

    @Bean
    public DomainAgentControlPolicy domainAgentControlPolicy(DomainAgentProperties properties) {
        return new DomainAgentControlPolicy(properties.normalizedMaxReroutes());
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
