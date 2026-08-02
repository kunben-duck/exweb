package com.huawei.it.ex.one.application.service.agentdatapersistence;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;
import com.huawei.it.ex.one.application.integration.agentdatapersistence.AgentDataPersistencePolicyCache;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationException;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationProvider;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationQuery;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 解析并缓存可信 DomainAgent 技能的 assistant 留存策略。 */
@Service
public class AgentDataPersistencePolicyService {
    private static final AppLogger log = AppLoggerFactory.getLogger(AgentDataPersistencePolicyService.class);
    private static final String DOMAIN_AGENT_PROVIDER = "domain-agent";

    private final DomainAgentSkillConfigurationProvider configurationProvider;
    private final AgentDataPersistencePolicyCache cache;
    private final AgentDataPersistenceProperties properties;
    private final Scheduler ioScheduler;

    public AgentDataPersistencePolicyService(
            DomainAgentSkillConfigurationProvider configurationProvider,
            AgentDataPersistencePolicyCache cache,
            AgentDataPersistenceProperties properties,
            @Qualifier("agentDataPersistenceIoScheduler") Scheduler ioScheduler) {
        this.configurationProvider = configurationProvider;
        this.cache = cache;
        this.properties = properties;
        this.ioScheduler = ioScheduler;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public String placeholderContent() {
        return properties.normalizedPlaceholderContent();
    }

    public Mono<AgentDataPersistencePolicy> resolve(UserContext user, String skillId) {
        if (!enabled()) {
            return Mono.just(AgentDataPersistencePolicy.FULL);
        }
        if (skillId == null || skillId.isBlank()) {
            return Mono.error(new DomainAgentSkillConfigurationException(
                    DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID,
                    "Resolved DomainAgent route has no skillId"));
        }
        String normalizedSkillId = skillId.trim();
        return readCache(normalizedSkillId)
                .flatMap(cached -> cached
                        .map(Mono::just)
                        .orElseGet(() -> resolveFromProvider(user, normalizedSkillId)));
    }

    private Mono<Optional<AgentDataPersistencePolicy>> readCache(String skillId) {
        return Mono.fromCallable(() -> cache.get(DOMAIN_AGENT_PROVIDER, skillId))
                .subscribeOn(ioScheduler)
                .onErrorResume(ex -> {
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_READ_FAILED,
                                    "Agent data persistence cache read failed; querying the configuration provider")
                            .operation("agent-data-persistence.cache.read")
                            .build(), ex);
                    return Mono.just(Optional.empty());
                });
    }

    private Mono<AgentDataPersistencePolicy> resolveFromProvider(UserContext user, String skillId) {
        DomainAgentSkillConfigurationQuery query = new DomainAgentSkillConfigurationQuery(
                user == null ? null : user.tenantId(),
                user == null ? null : user.ownerUserId(),
                skillId);
        return Mono.defer(() -> {
                    Mono<DomainAgentSkillConfiguration> source = configurationProvider.findBySkillId(query);
                    return source == null
                            ? Mono.error(new DomainAgentSkillConfigurationException(
                                    DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID,
                                    "DomainAgent skill configuration provider returned null"))
                            : source;
                })
                .switchIfEmpty(Mono.error(new DomainAgentSkillConfigurationException(
                        DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID,
                        "DomainAgent skill configuration provider returned an empty result")))
                .onErrorMap(error -> !(error instanceof DomainAgentSkillConfigurationException),
                        error -> new DomainAgentSkillConfigurationException(
                                DomainAgentSkillConfigurationException.Reason.UNAVAILABLE,
                                "DomainAgent skill configuration provider failed",
                                error))
                .map(configuration -> toPolicy(skillId, configuration))
                .flatMap(policy -> writeCache(skillId, policy).thenReturn(policy));
    }

    private AgentDataPersistencePolicy toPolicy(
            String requestedSkillId,
            DomainAgentSkillConfiguration configuration) {
        if (configuration == null) {
            throw new DomainAgentSkillConfigurationException(
                    DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID,
                    "DomainAgent skill configuration provider returned null");
        }
        if (configuration.skillId() == null
                || !requestedSkillId.equals(configuration.skillId().trim())) {
            throw new DomainAgentSkillConfigurationException(
                    DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID,
                    "DomainAgent skill configuration provider returned a mismatched skillId");
        }
        return Boolean.FALSE.equals(configuration.saveSession())
                ? AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER
                : AgentDataPersistencePolicy.FULL;
    }

    private Mono<Void> writeCache(String skillId, AgentDataPersistencePolicy policy) {
        return Mono.fromRunnable(() -> cache.put(
                        DOMAIN_AGENT_PROVIDER,
                        skillId,
                        policy,
                        properties.normalizedCacheTtl()))
                .subscribeOn(ioScheduler)
                .onErrorResume(ex -> {
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_WRITE_FAILED,
                                    "Agent data persistence cache write failed; the resolved policy remains effective")
                            .operation("agent-data-persistence.cache.write")
                            .build(), ex);
                    return Mono.empty();
                })
                .then();
    }
}
