/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.domainagentconfig;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationCache;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationException;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationProvider;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationQuery;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.infrastructure.domainagentconfig.DomainAgentSkillConfigurationProperties;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 统一解析并缓存ChatService可理解的DomainAgent技能配置。 */
@Service
public class DomainAgentSkillConfigurationService {
    private static final AppLogger log = AppLoggerFactory.getLogger(DomainAgentSkillConfigurationService.class);

    private final DomainAgentSkillConfigurationProvider provider;
    private final DomainAgentSkillConfigurationCache cache;
    private final DomainAgentSkillConfigurationProperties properties;
    private final Scheduler ioScheduler;

    public DomainAgentSkillConfigurationService(
            DomainAgentSkillConfigurationProvider provider,
            DomainAgentSkillConfigurationCache cache,
            DomainAgentSkillConfigurationProperties properties,
            @Qualifier("agentDataPersistenceIoScheduler") Scheduler ioScheduler) {
        this.provider = provider;
        this.cache = cache;
        this.properties = properties;
        this.ioScheduler = ioScheduler;
    }

    public Mono<DomainAgentSkillConfiguration> resolve(
            UserContext user,
            String skillId,
            RuntimeForwardHeaders forwardHeaders) {
        String tenantId = requireText(user == null ? null : user.tenantId(), "tenantId");
        String normalizedSkillId = requireText(skillId, "skillId");
        RuntimeForwardHeaders safeHeaders = forwardHeaders == null
                ? RuntimeForwardHeaders.empty()
                : forwardHeaders;
        if (!properties.isCacheEnabled()) {
            return resolveFromProvider(user, tenantId, normalizedSkillId, safeHeaders);
        }
        return readCache(tenantId, normalizedSkillId)
                .flatMap(cached -> cached
                        .map(configuration -> Mono.just(validate(normalizedSkillId, configuration)))
                        .orElseGet(() -> resolveFromProvider(
                                user, tenantId, normalizedSkillId, safeHeaders)));
    }

    private Mono<Optional<DomainAgentSkillConfiguration>> readCache(String tenantId, String skillId) {
        return Mono.fromCallable(() -> cache.get(tenantId, skillId))
                .subscribeOn(ioScheduler)
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(ex -> {
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_READ_FAILED,
                                    "DomainAgent skill configuration cache read failed; querying provider")
                            .operation("domain-agent-skill-config.cache.read")
                            .attribute("skillId", skillId)
                            .build(), ex);
                    return Mono.just(Optional.empty());
                });
    }

    private Mono<DomainAgentSkillConfiguration> resolveFromProvider(
            UserContext user,
            String tenantId,
            String skillId,
            RuntimeForwardHeaders forwardHeaders) {
        DomainAgentSkillConfigurationQuery query = new DomainAgentSkillConfigurationQuery(
                tenantId,
                user == null ? null : user.ownerUserId(),
                skillId,
                forwardHeaders);
        return Mono.defer(() -> {
                    Mono<DomainAgentSkillConfiguration> source = provider.findBySkillId(query);
                    return source == null
                            ? Mono.error(protocolError("DomainAgent skill configuration provider returned null"))
                            : source;
                })
                .switchIfEmpty(Mono.error(protocolError(
                        "DomainAgent skill configuration provider returned an empty result")))
                .onErrorMap(error -> !(error instanceof DomainAgentSkillConfigurationException),
                        error -> new DomainAgentSkillConfigurationException(
                                DomainAgentSkillConfigurationException.Reason.UNAVAILABLE,
                                "DomainAgent skill configuration provider failed",
                                error))
                .map(configuration -> validate(skillId, configuration))
                .flatMap(configuration -> properties.isCacheEnabled()
                        ? writeCache(tenantId, skillId, configuration).thenReturn(configuration)
                        : Mono.just(configuration));
    }

    private Mono<Void> writeCache(
            String tenantId,
            String skillId,
            DomainAgentSkillConfiguration configuration) {
        return Mono.fromRunnable(() -> cache.put(
                        tenantId, skillId, configuration, properties.normalizedCacheTtl()))
                .subscribeOn(ioScheduler)
                .onErrorResume(ex -> {
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_WRITE_FAILED,
                                    "DomainAgent skill configuration cache write failed; using resolved value")
                            .operation("domain-agent-skill-config.cache.write")
                            .attribute("skillId", skillId)
                            .build(), ex);
                    return Mono.empty();
                })
                .then();
    }

    private DomainAgentSkillConfiguration validate(
            String requestedSkillId,
            DomainAgentSkillConfiguration configuration) {
        if (configuration == null
                || configuration.skillId() == null
                || !requestedSkillId.equals(configuration.skillId().trim())) {
            throw protocolError("DomainAgent skill configuration provider returned a mismatched skillId");
        }
        return configuration;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw protocolError("DomainAgent skill configuration " + field + " is blank");
        }
        return value.trim();
    }

    private DomainAgentSkillConfigurationException protocolError(String message) {
        return new DomainAgentSkillConfigurationException(
                DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID,
                message);
    }
}
