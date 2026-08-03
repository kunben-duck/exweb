package com.huawei.it.ex.one.application.service.agentdatapersistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agentdatapersistence.AgentDataPersistencePolicyCache;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationProvider;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationQuery;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class AgentDataPersistenceGateTest {
    private final UserContext user = new UserContext("tenant-1", "user-1", "account-1");

    @Test
    void domainAgentUsesTrustedRouteSkillIdAndTightensState() {
        AtomicReference<DomainAgentSkillConfigurationQuery> captured = new AtomicReference<>();
        AgentDataPersistenceGate gate = gate(query -> {
            captured.set(query);
            return Mono.just(new DomainAgentSkillConfiguration(query.skillId(), Boolean.FALSE));
        });
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏");
        RuntimeForwardHeaders forwardHeaders = new RuntimeForwardHeaders(
                "SESSION=test", Instant.parse("2026-08-03T12:00:00Z"));

        AgentDataPersistenceState resolved = gate.resolve(
                user,
                RouteTarget.domainAgent("skill-trusted", "intent-agent", 0.9, "matched"),
                state,
                forwardHeaders).block();

        assertThat(resolved).isSameAs(state);
        assertThat(resolved.placeholderMode()).isTrue();
        assertThat(captured.get()).isEqualTo(new DomainAgentSkillConfigurationQuery(
                "tenant-1", "user-1", "skill-trusted", forwardHeaders));
    }

    @Test
    void relayDoesNotQueryProviderOrLoosenPlaceholderState() {
        AtomicInteger providerCalls = new AtomicInteger();
        AgentDataPersistenceGate gate = gate(query -> {
            providerCalls.incrementAndGet();
            return Mono.just(new DomainAgentSkillConfiguration(query.skillId(), Boolean.TRUE));
        });
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);

        AgentDataPersistenceState resolved = gate.resolve(
                user, RouteTarget.agentRuntime("relay"), state).block();

        assertThat(resolved).isSameAs(state);
        assertThat(resolved.placeholderMode()).isTrue();
        assertThat(providerCalls).hasValue(0);
    }

    private AgentDataPersistenceGate gate(DomainAgentSkillConfigurationProvider provider) {
        AgentDataPersistenceProperties properties = new AgentDataPersistenceProperties();
        properties.setEnabled(true);
        AgentDataPersistencePolicyCache cache = new AgentDataPersistencePolicyCache() {
            @Override
            public Optional<AgentDataPersistencePolicy> get(
                    String tenantId, String runtimeProvider, String skillId) {
                return Optional.empty();
            }

            @Override
            public void put(String tenantId, String runtimeProvider, String skillId,
                            AgentDataPersistencePolicy policy, Duration ttl) {
            }
        };
        return new AgentDataPersistenceGate(new AgentDataPersistencePolicyService(
                provider, cache, properties, Schedulers.immediate()));
    }
}
