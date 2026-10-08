/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.agentdatapersistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;
import com.huawei.it.ex.one.application.config.DomainAgentProperties;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
    void relaySkipsConfigurationButExtensionlessDomainAgentReadsIt() {
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
        AgentDataPersistenceGate.Decision noAttachments = gate.evaluate(
                user, RouteTarget.domainAgent("skill-1", "direct"), state,
                RuntimeForwardHeaders.empty(), List.of()).block();

        assertThat(relay.status()).isEqualTo(AgentDataPersistenceGate.Status.ALLOW);
        assertThat(extensionless.status()).isEqualTo(AgentDataPersistenceGate.Status.ALLOW);
        assertThat(noAttachments.status()).isEqualTo(AgentDataPersistenceGate.Status.ALLOW);
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void unconfiguredSkillRejectsEveryAttachment() {
        AtomicInteger providerCalls = new AtomicInteger();
        AgentDataPersistenceGate gate = gate(false, query -> {
            providerCalls.incrementAndGet();
            return Mono.just(DomainAgentSkillConfiguration.unconfigured(query.skillId()));
        });

        AgentDataPersistenceGate.Decision decision = gate.evaluate(
                user, RouteTarget.domainAgent("skill-1", "direct"),
                new AgentDataPersistenceState("回答已隐藏"), RuntimeForwardHeaders.empty(),
                List.of(document("doc-1", "report.pdf"), document("doc-2", "README"))).block();

        assertThat(providerCalls).hasValue(1);
        assertThat(decision.status()).isEqualTo(AgentDataPersistenceGate.Status.UNSUPPORTED_ATTACHMENT);
        assertThat(decision.payload())
                .containsEntry("supportedAttachmentTypes", List.of())
                .containsEntry("unsupportedAttachmentTypes", List.of(".pdf"));
        assertThat((List<?>) decision.payload().get("unsupportedAttachments")).hasSize(2);
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

    @ParameterizedTest
    @ValueSource(ints = {11, 20})
    void attachmentCountIsRejectedBeforeConfigurationLookup(int count) {
        AtomicInteger providerCalls = new AtomicInteger();
        AgentDataPersistenceGate gate = gate(false, query -> {
            providerCalls.incrementAndGet();
            return Mono.just(configuration(query.skillId(), Boolean.TRUE, ".pdf"));
        }, 10);
        List<UploadedDocument> documents = java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> document("doc-" + index, "report-" + index + ".pdf"))
                .toList();

        AgentDataPersistenceGate.Decision decision = gate.evaluate(
                user, RouteTarget.domainAgent("skill-1", "direct"),
                new AgentDataPersistenceState("回答已隐藏"), RuntimeForwardHeaders.empty(),
                documents).block();

        assertThat(decision.unsupportedAttachment()).isTrue();
        assertThat(decision.payload())
                .containsEntry("code", "DOMAIN_AGENT_ATTACHMENT_COUNT_EXCEEDED")
                .containsEntry("actualAttachmentCount", count)
                .containsEntry("maxAttachmentCount", 10)
                .containsEntry("limitSource", "SERVICE")
                .containsEntry("skillName", "skill-1");
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void customAttachmentCountLimitIsApplied() {
        AgentDataPersistenceGate gate = gate(false,
                query -> Mono.just(configuration(query.skillId(), Boolean.TRUE, ".pdf")), 1);

        AgentDataPersistenceGate.Decision decision = gate.evaluate(
                user, RouteTarget.domainAgent("skill-1", "direct"),
                new AgentDataPersistenceState("回答已隐藏"), RuntimeForwardHeaders.empty(),
                List.of(document("doc-1", "first.pdf"), document("doc-2", "second.pdf"))).block();
        assertThat(decision.unsupportedAttachment()).isTrue();
        assertThat(decision.payload()).containsEntry("maxAttachmentCount", 1)
                .containsEntry("limitSource", "SERVICE");
    }

    @ParameterizedTest
    @CsvSource({"2, 2, false", "2, 3, true", "0, 1, true", "0, 0, false",
            "10, 10, false", "20, 10, false", ", 10, false"})
    void skillCountBoundaryUsesTheSameRetentionSnapshot(Integer limit, int count, boolean rejected) {
        AtomicInteger providerCalls = new AtomicInteger();
        AgentDataPersistenceGate gate = gate(true, query -> {
            providerCalls.incrementAndGet();
            return Mono.just(new DomainAgentSkillConfiguration(
                    query.skillId(), "技能一", false, ".pdf", limit));
        });
        List<UploadedDocument> documents = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> document("doc-" + index, "report.pdf")).toList();

        AgentDataPersistenceGate.Decision decision = gate.evaluate(
                user, RouteTarget.domainAgent("skill-1", "direct"), AgentDataPersistenceState.full(),
                RuntimeForwardHeaders.empty(), documents).block();

        assertThat(providerCalls).hasValue(1);
        assertThat(decision.state().placeholderMode()).isTrue();
        assertThat(decision.unsupportedAttachment()).isEqualTo(rejected);
        if (rejected) {
            assertThat(decision.payload()).containsEntry("limitSource", "SKILL")
                    .containsEntry("maxAttachmentCount", limit)
                    .containsEntry("actualAttachmentCount", count)
                    .containsEntry("skillName", "技能一")
                    .containsEntry("message", "该技能最多支持上传" + limit + "个附件，本次上传"
                            + count + "个，请减少附件后重试。");
        }
    }

    @Test
    void countRejectionPrecedesTypeRejectionAndZeroDoesNotRejectEmptyRequests() {
        AtomicInteger calls = new AtomicInteger();
        AgentDataPersistenceGate gate = gate(false, query -> {
            calls.incrementAndGet();
            return Mono.just(new DomainAgentSkillConfiguration(query.skillId(), null, true, null, 0));
        });

        AgentDataPersistenceGate.Decision empty = gate.evaluate(
                user, RouteTarget.domainAgent("skill-1", "direct"), AgentDataPersistenceState.full(),
                RuntimeForwardHeaders.empty(), List.of()).block();
        assertThat(empty.unsupportedAttachment()).isFalse();
        assertThat(calls).hasValue(0);

        AgentDataPersistenceGate.Decision rejected = gate.evaluate(
                user, RouteTarget.domainAgent("skill-1", "direct"), AgentDataPersistenceState.full(),
                RuntimeForwardHeaders.empty(), List.of(document("doc-1", "README"))).block();
        assertThat(rejected.payload()).containsEntry("code", "DOMAIN_AGENT_ATTACHMENT_COUNT_EXCEEDED")
                .doesNotContainKeys("supportedAttachmentTypes", "unsupportedAttachments");
        assertThat(calls).hasValue(1);
    }

    @Test
    void relayBypassesDomainAgentCountLimit() {
        AgentDataPersistenceGate gate = gate(false, query -> {
            throw new AssertionError("Relay must not query skill configuration");
        }, 1);
        assertThat(gate.evaluate(user, RouteTarget.agentRuntime("relay"), AgentDataPersistenceState.full(),
                RuntimeForwardHeaders.empty(), List.of(document("doc-1", "1.pdf"), document("doc-2", "2.pdf")))
                .block().unsupportedAttachment()).isFalse();
    }

    private AgentDataPersistenceGate gate(
            boolean persistenceEnabled,
            DomainAgentSkillConfigurationProvider provider) {
        return gate(persistenceEnabled, provider, new DomainAgentProperties().normalizedMaxAttachments());
    }

    private AgentDataPersistenceGate gate(
            boolean persistenceEnabled,
            DomainAgentSkillConfigurationProvider provider,
            int maxAttachments) {
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
        DomainAgentProperties domainAgent = new DomainAgentProperties();
        domainAgent.setMaxAttachments(maxAttachments);
        return new AgentDataPersistenceGate(
                new AgentDataPersistencePolicyService(persistence), configurationService,
                domainAgent);
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
