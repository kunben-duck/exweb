package com.huawei.it.ex.one.application.service.agentdatapersistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationCache;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationException;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationProvider;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationQuery;
import com.huawei.it.ex.one.application.service.domainagentconfig.DomainAgentSkillConfigurationService;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.infrastructure.domainagentconfig.DomainAgentSkillConfigurationProperties;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class AgentDataPersistenceGateTest {
    private final UserContext user = new UserContext("tenant-1", "user-1", "account-1");

    @Test
    void oneConfigurationSnapshotDrivesRetentionAndAttachmentValidation() {
        AtomicReference<DomainAgentSkillConfigurationQuery> captured = new AtomicReference<>();
        AtomicInteger providerCalls = new AtomicInteger();
        AgentDataPersistenceGate gate = gate(true, query -> {
            providerCalls.incrementAndGet();
            captured.set(query);
            return Mono.just(configuration(query.skillId(), Boolean.FALSE, ".xlsx.xls;.rar;.zip"));
        });
        RuntimeForwardHeaders headers = new RuntimeForwardHeaders(
                "SESSION=test", Instant.parse("2026-08-03T12:00:00Z"));

        AgentDataPersistenceGate.Decision decision = gate.evaluate(
                user,
                RouteTarget.domainAgent("skill-1", "intent-agent", 0.9, "matched"),
                new AgentDataPersistenceState("回答已隐藏"),
                headers,
                List.of(document("doc-1", "report.PDF")))
                .block();

        assertThat(providerCalls).hasValue(1);
        assertThat(captured.get()).isEqualTo(new DomainAgentSkillConfigurationQuery(
                "tenant-1", "user-1", "skill-1", headers));
        assertThat(decision.status()).isEqualTo(AgentDataPersistenceGate.Status.UNSUPPORTED_ATTACHMENT);
        assertThat(decision.state().placeholderMode()).isTrue();
        assertThat(decision.payload())
                .containsEntry("skillId", "skill-1")
                .containsEntry("skillName", "技能一")
                .containsEntry("supportedAttachmentTypes", List.of(".xlsx", ".xls", ".rar", ".zip"))
                .containsEntry("unsupportedAttachmentTypes", List.of(".pdf"));
    }

    @Test
    void relayAndExtensionlessAttachmentDoNotReadConfigurationWhenRetentionIsDisabled() {
        AtomicInteger providerCalls = new AtomicInteger();
        AgentDataPersistenceGate gate = gate(false, query -> {
            providerCalls.incrementAndGet();
            return Mono.just(configuration(query.skillId(), Boolean.TRUE, ".pdf"));
        });
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏");

        AgentDataPersistenceGate.Decision relay = gate.evaluate(
                user, RouteTarget.agentRuntime("relay"), state,
                RuntimeForwardHeaders.empty(), List.of(document("doc-1", "report.pdf"))).block();
        AgentDataPersistenceGate.Decision extensionless = gate.evaluate(
                user, RouteTarget.domainAgent("skill-1", "direct"), state,
                RuntimeForwardHeaders.empty(), List.of(document("doc-2", "README"))).block();

        assertThat(relay.status()).isEqualTo(AgentDataPersistenceGate.Status.ALLOW);
        assertThat(extensionless.status()).isEqualTo(AgentDataPersistenceGate.Status.ALLOW);
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void attachmentOnlyConfigurationFailureIsFailOpen() {
        DomainAgentSkillConfigurationException failure = new DomainAgentSkillConfigurationException(
                DomainAgentSkillConfigurationException.Reason.UNAVAILABLE, "unavailable");
        AgentDataPersistenceGate gate = gate(false, query -> Mono.error(failure));

        AgentDataPersistenceGate.Decision decision = gate.evaluate(
                user, RouteTarget.domainAgent("skill-1", "direct"),
                new AgentDataPersistenceState("回答已隐藏"), RuntimeForwardHeaders.empty(),
                List.of(document("doc-1", "report.pdf"))).block();

        assertThat(decision.status()).isEqualTo(AgentDataPersistenceGate.Status.ALLOW);
    }

    @Test
    void retentionConfigurationFailureKeepsExistingFailClosedBehavior() {
        DomainAgentSkillConfigurationException failure = new DomainAgentSkillConfigurationException(
                DomainAgentSkillConfigurationException.Reason.UNAVAILABLE, "unavailable");
        AgentDataPersistenceGate gate = gate(true, query -> Mono.error(failure));

        assertThatThrownBy(() -> gate.evaluate(
                user, RouteTarget.domainAgent("skill-1", "direct"),
                new AgentDataPersistenceState("回答已隐藏"), RuntimeForwardHeaders.empty(),
                List.of()).block())
                .isSameAs(failure);
    }

    private AgentDataPersistenceGate gate(
            boolean persistenceEnabled,
            DomainAgentSkillConfigurationProvider provider) {
        AgentDataPersistenceProperties persistence = new AgentDataPersistenceProperties();
        persistence.setEnabled(persistenceEnabled);
        DomainAgentSkillConfigurationProperties configuration = new DomainAgentSkillConfigurationProperties();
        configuration.setCacheEnabled(false);
        DomainAgentSkillConfigurationCache cache = new DomainAgentSkillConfigurationCache() {
            @Override
            public Optional<DomainAgentSkillConfiguration> get(String tenantId, String skillId) {
                return Optional.empty();
            }

            @Override
            public void put(String tenantId, String skillId,
                            DomainAgentSkillConfiguration value, Duration ttl) {
            }
        };
        DomainAgentSkillConfigurationService configurationService =
                new DomainAgentSkillConfigurationService(
                        provider, cache, configuration, Schedulers.immediate());
        return new AgentDataPersistenceGate(
                new AgentDataPersistencePolicyService(persistence), configurationService);
    }

    private DomainAgentSkillConfiguration configuration(
            String skillId, Boolean saveSession, String attachmentType) {
        return new DomainAgentSkillConfiguration(skillId, "技能一", saveSession, attachmentType);
    }

    private UploadedDocument document(String id, String name) {
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        return new UploadedDocument(
                id, "tenant-1", "user-1", "session-1", name,
                "bucket", "object-key", "application/octet-stream", 10,
                "AVAILABLE", "LOCAL_UPLOAD", null, null, now, now);
    }
}
