package com.huawei.it.ex.one.application.service.agentdatapersistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agentdatapersistence.AgentDataPersistencePolicyCache;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationException;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationProvider;
import com.huawei.it.ex.one.domain.auth.UserContext;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class AgentDataPersistencePolicyServiceTest {
    private final UserContext user = new UserContext("tenant1", "user1", "account1");

    @Test
    void mapsExplicitNoToPlaceholderAndCachesThePolicy() {
        AgentDataPersistenceProperties properties = enabledProperties();
        RecordingCache cache = new RecordingCache();
        AtomicInteger providerCalls = new AtomicInteger();
        DomainAgentSkillConfigurationProvider provider = query -> {
            providerCalls.incrementAndGet();
            return Mono.just(new DomainAgentSkillConfiguration(query.skillId(), Boolean.FALSE));
        };
        AgentDataPersistencePolicyService service = service(provider, cache, properties);

        assertThat(service.resolve(user, "skill-1").block())
                .isEqualTo(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        assertThat(service.resolve(user, "skill-1").block())
                .isEqualTo(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        assertThat(providerCalls).hasValue(1);
        assertThat(cache.values).containsEntry(
                "tenant1:domain-agent:skill-1", AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
    }

    @Test
    void forwardsCookieSnapshotOnlyOnProviderCacheMiss() {
        RecordingCache cache = new RecordingCache();
        AtomicReference<RuntimeForwardHeaders> captured = new AtomicReference<>();
        DomainAgentSkillConfigurationProvider provider = query -> {
            captured.set(query.forwardHeaders());
            return Mono.just(new DomainAgentSkillConfiguration(query.skillId(), Boolean.TRUE));
        };
        AgentDataPersistencePolicyService service = service(provider, cache, enabledProperties());
        RuntimeForwardHeaders headers = RuntimeForwardHeaders.fromCookieHeader("SESSION=test", 8192);

        service.resolve(user, "skill-1", headers).block();
        service.resolve(user, "skill-1", RuntimeForwardHeaders.empty()).block();

        assertThat(captured.get()).isSameAs(headers);
    }

    @Test
    void isolatesCachedPoliciesByTenant() {
        RecordingCache cache = new RecordingCache();
        AtomicInteger providerCalls = new AtomicInteger();
        AgentDataPersistencePolicyService service = service(query -> {
            providerCalls.incrementAndGet();
            boolean saveSession = "tenant2".equals(query.tenantId());
            return Mono.just(new DomainAgentSkillConfiguration(query.skillId(), saveSession));
        }, cache, enabledProperties());

        assertThat(service.resolve(user, "skill-1").block())
                .isEqualTo(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        assertThat(service.resolve(new UserContext("tenant2", "user1", "account1"), "skill-1").block())
                .isEqualTo(AgentDataPersistencePolicy.FULL);
        assertThat(service.resolve(user, "skill-1").block())
                .isEqualTo(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);

        assertThat(providerCalls).hasValue(2);
        assertThat(cache.values).containsKeys(
                "tenant1:domain-agent:skill-1",
                "tenant2:domain-agent:skill-1");
    }

    @Test
    void unconfiguredSkillUsesFullPolicy() {
        AgentDataPersistencePolicyService service = service(
                query -> Mono.just(DomainAgentSkillConfiguration.unconfigured(query.skillId())),
                new RecordingCache(),
                enabledProperties());

        assertThat(service.resolve(user, "skill-1").block())
                .isEqualTo(AgentDataPersistencePolicy.FULL);
    }

    @Test
    void providerFailureIsFailClosedAfterCacheMiss() {
        DomainAgentSkillConfigurationException failure = new DomainAgentSkillConfigurationException(
                DomainAgentSkillConfigurationException.Reason.UNAVAILABLE,
                "service unavailable");
        AgentDataPersistencePolicyService service = service(
                query -> Mono.error(failure),
                new RecordingCache(),
                enabledProperties());

        assertThatThrownBy(() -> service.resolve(user, "skill-1").block())
                .isSameAs(failure);
    }

    @Test
    void nullProviderPublisherIsRejectedAsProtocolError() {
        AgentDataPersistencePolicyService service = service(
                query -> null,
                new RecordingCache(),
                enabledProperties());

        assertThatThrownBy(() -> service.resolve(user, "skill-1").block())
                .isInstanceOfSatisfying(DomainAgentSkillConfigurationException.class,
                        error -> assertThat(error.reason())
                                .isEqualTo(DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID));
    }

    @Test
    void mismatchedProviderSkillIdIsRejectedAsProtocolError() {
        AgentDataPersistencePolicyService service = service(
                query -> Mono.just(new DomainAgentSkillConfiguration("another-skill", Boolean.FALSE)),
                new RecordingCache(),
                enabledProperties());

        assertThatThrownBy(() -> service.resolve(user, "skill-1").block())
                .isInstanceOfSatisfying(DomainAgentSkillConfigurationException.class,
                        error -> assertThat(error.reason())
                                .isEqualTo(DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID));
    }

    @Test
    void cacheReadFailureFallsBackToProvider() {
        AtomicInteger providerCalls = new AtomicInteger();
        AgentDataPersistencePolicyCache cache = new AgentDataPersistencePolicyCache() {
            @Override
            public Optional<AgentDataPersistencePolicy> get(
                    String tenantId, String runtimeProvider, String skillId) {
                throw new IllegalStateException("redis unavailable");
            }

            @Override
            public void put(String tenantId, String runtimeProvider, String skillId,
                            AgentDataPersistencePolicy policy, Duration ttl) {
            }
        };
        AgentDataPersistencePolicyService service = service(query -> {
            providerCalls.incrementAndGet();
            return Mono.just(new DomainAgentSkillConfiguration(query.skillId(), Boolean.FALSE));
        }, cache, enabledProperties());

        assertThat(service.resolve(user, "skill-1").block())
                .isEqualTo(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void cacheWriteFailureDoesNotDiscardResolvedPolicy() {
        AgentDataPersistencePolicyCache cache = new AgentDataPersistencePolicyCache() {
            @Override
            public Optional<AgentDataPersistencePolicy> get(
                    String tenantId, String runtimeProvider, String skillId) {
                return Optional.empty();
            }

            @Override
            public void put(String tenantId, String runtimeProvider, String skillId,
                            AgentDataPersistencePolicy policy, Duration ttl) {
                throw new IllegalStateException("redis unavailable");
            }
        };
        AgentDataPersistencePolicyService service = service(
                query -> Mono.just(new DomainAgentSkillConfiguration(query.skillId(), Boolean.FALSE)),
                cache,
                enabledProperties());

        assertThat(service.resolve(user, "skill-1").block())
                .isEqualTo(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
    }

    @Test
    void disabledFeatureDoesNotCallProviderOrCache() {
        AgentDataPersistenceProperties properties = new AgentDataPersistenceProperties();
        AtomicInteger providerCalls = new AtomicInteger();
        RecordingCache cache = new RecordingCache();
        AgentDataPersistencePolicyService service = service(query -> {
            providerCalls.incrementAndGet();
            return Mono.just(new DomainAgentSkillConfiguration(query.skillId(), Boolean.FALSE));
        }, cache, properties);

        assertThat(service.resolve(user, "skill-1").block())
                .isEqualTo(AgentDataPersistencePolicy.FULL);
        assertThat(providerCalls).hasValue(0);
        assertThat(cache.getCalls).isZero();
    }

    private AgentDataPersistencePolicyService service(
            DomainAgentSkillConfigurationProvider provider,
            AgentDataPersistencePolicyCache cache,
            AgentDataPersistenceProperties properties) {
        return new AgentDataPersistencePolicyService(
                provider, cache, properties, Schedulers.immediate());
    }

    private AgentDataPersistenceProperties enabledProperties() {
        AgentDataPersistenceProperties properties = new AgentDataPersistenceProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static final class RecordingCache implements AgentDataPersistencePolicyCache {
        private final Map<String, AgentDataPersistencePolicy> values = new HashMap<>();
        private int getCalls;

        @Override
        public Optional<AgentDataPersistencePolicy> get(
                String tenantId, String runtimeProvider, String skillId) {
            getCalls++;
            return Optional.ofNullable(values.get(tenantId + ":" + runtimeProvider + ":" + skillId));
        }

        @Override
        public void put(String tenantId, String runtimeProvider, String skillId,
                        AgentDataPersistencePolicy policy, Duration ttl) {
            values.put(tenantId + ":" + runtimeProvider + ":" + skillId, policy);
        }
    }
}
