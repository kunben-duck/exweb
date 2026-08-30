/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.intent;

import com.huawei.it.ex.one.application.config.IntentCandidateProperties;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Candidate-query authentication isolation, independent from normal Intent routing. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IntentCandidateProperties.class)
public class IntentCandidateSchedulerConfiguration {

    @Bean(name = "intentCandidateAuthScheduler", destroyMethod = "dispose")
    public Scheduler intentCandidateAuthScheduler(IntentCandidateProperties properties) {
        return Schedulers.newBoundedElastic(
                properties.getAuthIoMaxSize(),
                properties.getAuthIoQueueCapacity(),
                "finex-intent-candidate-auth-io"
        );
    }
}
