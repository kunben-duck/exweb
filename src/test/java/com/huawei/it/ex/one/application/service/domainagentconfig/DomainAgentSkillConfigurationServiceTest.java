/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.domainagentconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationCache;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationProvider;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.infrastructure.domainagentconfig.DomainAgentSkillConfigurationProperties;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

class DomainAgentSkillConfigurationServiceTest {
    private final UserContext user = new UserContext("tenant-1", "user-1", "account-1");

    @Test
    void cacheHitDoesNotCallProvider() {
        RecordingCache cache = new RecordingCache();
        DomainAgentSkillConfiguration cached = configuration("skill-1", Boolean.FALSE);
        cache.values.put("tenant-1:skill-1", cached);
        AtomicInteger providerCalls = new AtomicInteger();
        DomainAgentSkillConfigurationService service = service(cache, query -> {
            providerCalls.incrementAndGet();
            return Mono.just(configuration(query.skillId(), Boolean.TRUE));
        }, true);

        assertThat(service.resolve(user, "skill-1", RuntimeForwardHeaders.empty()).block())
                .isEqualTo(cached);
        assertThat(providerCalls).hasValue(0);
        assertThat(cache.putCalls).isZero();
    }

    @Test
    void cacheMissCallsProviderOnceAndCachesCompleteSnapshot() {
        RecordingCache cache = new RecordingCache();
        AtomicInteger providerCalls = new AtomicInteger();
        DomainAgentSkillConfiguration resolved = configuration("skill-1", Boolean.FALSE);
        DomainAgentSkillConfigurationService service = service(cache, query -> {
            providerCalls.incrementAndGet();
            return Mono.just(resolved);
        }, true);

        assertThat(service.resolve(user, "skill-1", RuntimeForwardHeaders.empty()).block())
                .isEqualTo(resolved);
        assertThat(service.resolve(user, "skill-1", RuntimeForwardHeaders.empty()).block())
                .isEqualTo(resolved);

        assertThat(providerCalls).hasValue(1);
        assertThat(cache.values).containsEntry("tenant-1:skill-1", resolved);
        assertThat(cache.lastTtl).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void rejectsMismatchedCachedSkillConfiguration() {
        RecordingCache cache = new RecordingCache();
        cache.values.put("tenant-1:skill-1", configuration("skill-other", Boolean.TRUE));
        DomainAgentSkillConfigurationService service = service(
                cache, query -> Mono.just(configuration(query.skillId(), Boolean.TRUE)), true);

        assertThatThrownBy(() -> service.resolve(
                        user, "skill-1", RuntimeForwardHeaders.empty()).block())
                .hasMessageContaining("mismatched skillId");
    }

    @Test
    void redisReadAndWriteFailuresDoNotDiscardProviderResult() {
        DomainAgentSkillConfiguration expected = configuration("skill-1", Boolean.TRUE);
        DomainAgentSkillConfigurationCache cache = new DomainAgentSkillConfigurationCache() {
            @Override
            public Optional<DomainAgentSkillConfiguration> get(String tenantId, String skillId) {
                throw new IllegalStateException("redis read unavailable");
            }

            @Override
            public void put(String tenantId, String skillId,
                            DomainAgentSkillConfiguration configuration, Duration ttl) {
                throw new IllegalStateException("redis write unavailable");
            }
        };

        assertThat(service(cache, query -> Mono.just(expected), true)
                .resolve(user, "skill-1", RuntimeForwardHeaders.empty()).block())
                .isEqualTo(expected);
    }

    @Test
    void disabledCacheNeverReadsOrWritesRedis() {
        RecordingCache cache = new RecordingCache();
        AtomicInteger providerCalls = new AtomicInteger();
        DomainAgentSkillConfigurationService service = service(cache, query -> {
            providerCalls.incrementAndGet();
            return Mono.just(configuration(query.skillId(), Boolean.TRUE));
        }, false);

        service.resolve(user, "skill-1", RuntimeForwardHeaders.empty()).block();
        service.resolve(user, "skill-1", RuntimeForwardHeaders.empty()).block();

        assertThat(providerCalls).hasValue(2);
        assertThat(cache.getCalls).isZero();
        assertThat(cache.putCalls).isZero();
    }

    private DomainAgentSkillConfigurationService service(
            DomainAgentSkillConfigurationCache cache,
            DomainAgentSkillConfigurationProvider provider,
            boolean cacheEnabled) {
        DomainAgentSkillConfigurationProperties properties = new DomainAgentSkillConfigurationProperties();
        properties.setCacheEnabled(cacheEnabled);
        return new DomainAgentSkillConfigurationService(
                provider, cache, properties, Schedulers.immediate());
    }

    private DomainAgentSkillConfiguration configuration(String skillId, Boolean saveSession) {
        return new DomainAgentSkillConfiguration(
                skillId, "技能一", saveSession, ".xlsx.xls;.rar;.zip");
    }

    private static final class RecordingCache implements DomainAgentSkillConfigurationCache {
        private final Map<String, DomainAgentSkillConfiguration> values = new HashMap<>();
        private int getCalls;
        private int putCalls;
        private Duration lastTtl;

        @Override
        public Optional<DomainAgentSkillConfiguration> get(String tenantId, String skillId) {
            getCalls++;
            return Optional.ofNullable(values.get(tenantId + ":" + skillId));
        }

        @Override
        public void put(String tenantId, String skillId,
                        DomainAgentSkillConfiguration configuration, Duration ttl) {
            putCalls++;
            lastTtl = ttl;
            values.put(tenantId + ":" + skillId, configuration);
        }
    }
}
