package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationProvider;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** DomainAgent 技能配置默认Provider及阻塞IO隔离调度器装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DomainAgentSkillConfigurationProperties.class)
public class DomainAgentSkillConfigurationProviderConfiguration {
    @Bean(name = "agentDataPersistenceIoScheduler", destroyMethod = "dispose")
    public Scheduler agentDataPersistenceIoScheduler() {
        return Schedulers.newBoundedElastic(4, 128, "finex-agent-data-persistence-io");
    }

    @Bean
    @ConditionalOnMissingBean(DomainAgentSkillConfigurationClient.class)
    public DomainAgentSkillConfigurationClient domainAgentSkillConfigurationClient() {
        return new DefaultDomainAgentSkillConfigurationClient();
    }

    @Bean
    @ConditionalOnMissingBean(DomainAgentSkillConfigurationProvider.class)
    public DomainAgentSkillConfigurationProvider domainAgentSkillConfigurationProvider(
            DomainAgentSkillConfigurationClient client,
            DomainAgentSkillConfigurationProperties properties,
            AgentDataPersistenceProperties persistenceProperties,
            @Qualifier("agentDataPersistenceIoScheduler") Scheduler ioScheduler) {
        if (persistenceProperties.isEnabled()) {
            validateRequiredConfiguration(properties);
        }
        return new DefaultDomainAgentSkillConfigurationProvider(
                client,
                properties,
                ioScheduler);
    }

    /** 默认Provider启用时必须在启动阶段确认调用超时已配置。 */
    private void validateRequiredConfiguration(DomainAgentSkillConfigurationProperties properties) {
        if (properties.normalizedTimeout() == null) {
            throw missingConfiguration("financeex.domain-agent-skill-config.timeout");
        }
    }

    private IllegalStateException missingConfiguration(String propertyName) {
        return new IllegalStateException(
                propertyName + " must be explicitly configured when agent data persistence is enabled");
    }

}
