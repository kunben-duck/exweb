package com.huawei.it.ex.one.infrastructure.intent;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * IntentDecision stream adapter blocking IO isolation.
 *
 * <p>Enterprise authentication providers may synchronously refresh a token. Keeping that work on a
 * bounded scheduler prevents a slow provider from occupying chat event IO or Reactor network threads.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IntentServiceHttpProperties.class)
public class IntentStreamSchedulerConfiguration {

    @Bean(name = "intentStreamAuthScheduler", destroyMethod = "dispose")
    public Scheduler intentStreamAuthScheduler(IntentServiceHttpProperties properties) {
        return Schedulers.newBoundedElastic(
                properties.normalizedStreamAuthIoMaxSize(),
                properties.normalizedStreamAuthIoQueueCapacity(),
                "finex-intent-auth-io"
        );
    }
}
