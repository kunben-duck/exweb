/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

import com.huawei.it.ex.one.application.integration.observation.FirstBusinessFrameClassifier;
import com.huawei.it.ex.one.application.integration.observation.ObservationRepository;
import com.huawei.it.ex.one.application.service.observation.AgentObservationRecorder;
import com.huawei.it.ex.one.application.service.observation.DefaultFirstBusinessFrameClassifier;
import com.huawei.it.ex.one.application.service.observation.ObservationDiagnostics;
import com.huawei.it.ex.one.application.service.observation.ObservationWriter;
import com.huawei.it.ex.one.application.service.observation.TurnTraceCoordinator;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityConfiguration {
    @Bean
    @ConditionalOnMissingBean(FirstBusinessFrameClassifier.class)
    FirstBusinessFrameClassifier firstBusinessFrameClassifier() { return new DefaultFirstBusinessFrameClassifier(); }

    @Bean
    ObservationDiagnostics observationDiagnostics(ObjectProvider<MeterRegistry> meters) {
        return new ObservationDiagnostics(meters.getIfAvailable());
    }

    @Bean(destroyMethod = "close")
    ObservationWriter observationWriter(ObservabilityProperties properties, ObservationRepository repository,
                                        PlatformTransactionManager manager, ObservationDiagnostics diagnostics) {
        return new ObservationWriter(properties, repository, manager, diagnostics);
    }

    @Bean
    TurnTraceCoordinator turnTraceCoordinator(ObservabilityProperties properties, ObservationWriter writer,
                                             FirstBusinessFrameClassifier classifier, ObservationDiagnostics diagnostics) {
        return new TurnTraceCoordinator(properties.isEnabled(), writer, classifier, diagnostics);
    }

    @Bean
    AgentObservationRecorder agentObservationRecorder(TurnTraceCoordinator turns, ObservationWriter writer,
                                                      FirstBusinessFrameClassifier classifier,
                                                      ObservationDiagnostics diagnostics) {
        return new AgentObservationRecorder(turns, writer, classifier, diagnostics);
    }
}
