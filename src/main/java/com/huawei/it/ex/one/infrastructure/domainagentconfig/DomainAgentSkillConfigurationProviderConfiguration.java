package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationProvider;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;

/** DomainAgent 技能配置默认 Provider 及 Redis IO 隔离调度器装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DomainAgentSkillConfigurationProperties.class)
public class DomainAgentSkillConfigurationProviderConfiguration {
    @Bean(name = "agentDataPersistenceIoScheduler", destroyMethod = "dispose")
    public Scheduler agentDataPersistenceIoScheduler() {
        return Schedulers.newBoundedElastic(4, 128, "finex-agent-data-persistence-io");
    }

    @Bean
    @ConditionalOnMissingBean(DomainAgentSkillConfigurationProvider.class)
    public DomainAgentSkillConfigurationProvider domainAgentSkillConfigurationProvider(
            WebClient.Builder webClientBuilder,
            DomainAgentSkillConfigurationProperties properties,
            AgentDataPersistenceProperties persistenceProperties) {
        if (persistenceProperties.isEnabled()) {
            validateRequiredConfiguration(properties);
        }
        return new DefaultDomainAgentSkillConfigurationProvider(
                webClientBuilder,
                properties);
    }

    /** 默认 Provider 启用时在启动阶段校验外部接口地址及调用超时。 */
    private void validateRequiredConfiguration(DomainAgentSkillConfigurationProperties properties) {
        validateBaseUrl(properties.normalizedBaseUrl());
        String queryPath = properties.normalizedQueryPath();
        if (queryPath == null) {
            throw missingConfiguration("financeex.domain-agent-skill-config.query-path");
        }
        if (!queryPath.startsWith("/") || queryPath.startsWith("//")) {
            throw new IllegalStateException(
                    "financeex.domain-agent-skill-config.query-path must be an absolute HTTP path");
        }
        if (properties.normalizedTimeout() == null) {
            throw new IllegalStateException(
                    "financeex.domain-agent-skill-config.timeout must be a positive duration");
        }
    }

    private void validateBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            throw missingConfiguration("financeex.domain-agent-skill-config.base-url");
        }
        try {
            URI uri = URI.create(baseUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("unsupported URI");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "financeex.domain-agent-skill-config.base-url must be a valid HTTP URL",
                    ex);
        }
    }

    private IllegalStateException missingConfiguration(String propertyName) {
        return new IllegalStateException(
                propertyName + " must be explicitly configured when agent data persistence is enabled");
    }

}
