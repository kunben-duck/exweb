package com.huawei.it.ex.one.application.service.agentdatapersistence;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
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

    public Mono<AgentDataPersistencePolicy> resolve(
            UserContext user,
            String skillId,
            RuntimeForwardHeaders forwardHeaders) {
        if (!enabled()) {
            return Mono.just(AgentDataPersistencePolicy.FULL);
        }
        if (user == null || user.tenantId() == null || user.tenantId().isBlank()) {
            return Mono.error(new DomainAgentSkillConfigurationException(
                    DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID,
                    "Resolved DomainAgent route has no tenantId"));
        }
        if (skillId == null || skillId.isBlank()) {
            return Mono.error(new DomainAgentSkillConfigurationException(
                    DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID,
                    "Resolved DomainAgent route has no skillId"));
        }
        String tenantId = user.tenantId().trim();
        String normalizedSkillId = skillId.trim();
        RuntimeForwardHeaders safeForwardHeaders = forwardHeaders == null
                ? RuntimeForwardHeaders.empty()
                : forwardHeaders;
        if (!properties.isCacheEnabled()) {
            return resolveFromProvider(user, tenantId, normalizedSkillId, safeForwardHeaders);
        }
        return readCache(tenantId, normalizedSkillId)
                .flatMap(cached -> cached
                        .map(Mono::just)
                        .orElseGet(() -> resolveFromProvider(
                                user, tenantId, normalizedSkillId, safeForwardHeaders)));
    }

    /** 保留不需要出站请求头的内部调用兼容入口。 */
    public Mono<AgentDataPersistencePolicy> resolve(UserContext user, String skillId) {
        return resolve(user, skillId, RuntimeForwardHeaders.empty());
    }

    private Mono<Optional<AgentDataPersistencePolicy>> readCache(String tenantId, String skillId) {
        return Mono.fromCallable(() -> cache.get(tenantId, DOMAIN_AGENT_PROVIDER, skillId))
                .subscribeOn(ioScheduler)
                .onErrorResume(ex -> {
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_READ_FAILED,
                                    "Agent data persistence cache read failed; querying the configuration provider")
                            .operation("agent-data-persistence.cache.read")
                            .build(), ex);
                    return Mono.just(Optional.empty());
                });
    }

    private Mono<AgentDataPersistencePolicy> resolveFromProvider(
            UserContext user,
            String tenantId,
            String skillId,
            RuntimeForwardHeaders forwardHeaders) {
        DomainAgentSkillConfigurationQuery query = new DomainAgentSkillConfigurationQuery(
                tenantId,
                user.ownerUserId(),
                skillId,
                forwardHeaders);
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
                .flatMap(policy -> properties.isCacheEnabled()
                        ? writeCache(tenantId, skillId, policy).thenReturn(policy)
                        : Mono.just(policy));
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

    private Mono<Void> writeCache(
            String tenantId,
            String skillId,
            AgentDataPersistencePolicy policy) {
        return Mono.fromRunnable(() -> cache.put(
                        tenantId,
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
