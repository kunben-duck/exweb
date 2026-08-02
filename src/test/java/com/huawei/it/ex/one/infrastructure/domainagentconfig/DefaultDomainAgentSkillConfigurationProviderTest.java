package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationException;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationQuery;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

class DefaultDomainAgentSkillConfigurationProviderTest {
    @Test
    void sendsSingleSkillIdAndMapsExplicitNo() {
        AtomicReference<List<String>> requestedSkillIds = new AtomicReference<>();
        DomainAgentSkillConfigurationClient client = skillIds -> {
            requestedSkillIds.set(skillIds);
            return response(item("skill-1", "N"));
        };

        DomainAgentSkillConfiguration configuration = resolve(provider(client), "skill-1");

        assertThat(configuration)
                .isEqualTo(new DomainAgentSkillConfiguration("skill-1", Boolean.FALSE));
        assertThat(requestedSkillIds.get()).containsExactly("skill-1");
    }

    @Test
    void mapsYesBlankAndMissingConfigurationWithoutInventingDefaults() {
        assertThat(resolve(provider(skillIds -> response(item("skill-1", "y"))), "skill-1"))
                .extracting(DomainAgentSkillConfiguration::saveSession)
                .isEqualTo(Boolean.TRUE);
        assertThat(resolve(provider(skillIds -> response(item("skill-1", "  "))), "skill-1")
                .saveSession())
                .isNull();
        assertThat(resolve(provider(skillIds -> response(item("another-skill", "N"))), "skill-1"))
                .isEqualTo(DomainAgentSkillConfiguration.unconfigured("skill-1"));
    }

    @Test
    void rejectsUnknownOrConflictingSaveSessionValues() {
        assertProtocolInvalid(provider(skillIds -> response(item("skill-1", "MAYBE"))));
        assertProtocolInvalid(provider(skillIds -> response(
                item("skill-1", "Y"),
                item("skill-1", "N"))));
    }

    @Test
    void rejectsEmptyOrMalformedResponses() {
        assertProtocolInvalid(provider(skillIds -> null));
        assertProtocolInvalid(provider(skillIds -> new SkillConfigurationResponse(null, List.of())));
    }

    @Test
    void preservesClientProtocolErrorsAndMapsOtherFailuresToUnavailable() {
        DefaultDomainAgentSkillConfigurationProvider protocolFailure = provider(skillIds -> {
            throw new DomainAgentSkillConfigurationException(
                    DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID,
                    "Invalid enterprise response");
        });
        assertProtocolInvalid(protocolFailure);

        DefaultDomainAgentSkillConfigurationProvider unavailable = provider(skillIds -> {
            throw new IllegalStateException("Enterprise client unavailable");
        });
        assertReason(unavailable, DomainAgentSkillConfigurationException.Reason.UNAVAILABLE);
    }

    @Test
    void executesBlockingClientOnConfiguredScheduler() {
        AtomicReference<String> threadName = new AtomicReference<>();
        Scheduler scheduler = Schedulers.newSingle("skill-config-client-test");
        try {
            DefaultDomainAgentSkillConfigurationProvider provider = provider(skillIds -> {
                threadName.set(Thread.currentThread().getName());
                return response(item("skill-1", "Y"));
            }, scheduler, "2s");

            resolve(provider, "skill-1");

            assertThat(threadName.get()).startsWith("skill-config-client-test");
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    void mapsConfiguredTimeoutToTimeoutReason() {
        Scheduler scheduler = Schedulers.newBoundedElastic(1, 1, "skill-config-timeout-test");
        try {
            DefaultDomainAgentSkillConfigurationProvider provider = provider(skillIds -> {
                LockSupport.parkNanos(200_000_000L);
                return response(item("skill-1", "Y"));
            }, scheduler, "10ms");

            assertReason(provider, DomainAgentSkillConfigurationException.Reason.TIMEOUT);
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    void rejectsMissingTimeoutAndInvalidQueriesAsProtocolErrors() {
        DefaultDomainAgentSkillConfigurationProvider missingTimeout = provider(
                skillIds -> response(item("skill-1", "Y")),
                Schedulers.immediate(),
                "");
        assertProtocolInvalid(missingTimeout);

        DefaultDomainAgentSkillConfigurationProvider provider = provider(
                skillIds -> response(item("skill-1", "Y")));
        assertThatThrownBy(() -> provider.findBySkillId(null).block())
                .isInstanceOfSatisfying(DomainAgentSkillConfigurationException.class,
                        error -> assertThat(error.reason())
                                .isEqualTo(DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID));
    }

    @Test
    void exposesStableEnterpriseOperationNameAndCurrentCompilePlaceholder() {
        DefaultDomainAgentSkillConfigurationClient client =
                new DefaultDomainAgentSkillConfigurationClient();

        assertThat(DefaultDomainAgentSkillConfigurationClient.OPERATION_NAME)
                .isEqualTo("findSkillConfigBySkillIds");
        assertThatThrownBy(() -> client.findBySkillIds(List.of("skill-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DomainAgent skill configuration client is not configured");
    }

    private DomainAgentSkillConfiguration resolve(
            DefaultDomainAgentSkillConfigurationProvider provider,
            String skillId) {
        return provider.findBySkillId(
                        new DomainAgentSkillConfigurationQuery("tenant-1", "user-1", skillId))
                .block();
    }

    private void assertProtocolInvalid(DefaultDomainAgentSkillConfigurationProvider provider) {
        assertReason(provider, DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID);
    }

    private void assertReason(
            DefaultDomainAgentSkillConfigurationProvider provider,
            DomainAgentSkillConfigurationException.Reason expected) {
        assertThatThrownBy(() -> resolve(provider, "skill-1"))
                .isInstanceOfSatisfying(DomainAgentSkillConfigurationException.class,
                        error -> assertThat(error.reason()).isEqualTo(expected));
    }

    private DefaultDomainAgentSkillConfigurationProvider provider(
            DomainAgentSkillConfigurationClient client) {
        return provider(client, Schedulers.immediate(), "2s");
    }

    private DefaultDomainAgentSkillConfigurationProvider provider(
            DomainAgentSkillConfigurationClient client,
            Scheduler scheduler,
            String timeout) {
        DomainAgentSkillConfigurationProperties properties =
                new DomainAgentSkillConfigurationProperties();
        properties.setTimeout(timeout);
        return new DefaultDomainAgentSkillConfigurationProvider(client, properties, scheduler);
    }

    private SkillConfigurationResponse response(SkillConfigurationItem... items) {
        return new SkillConfigurationResponse("success", List.of(items));
    }

    private SkillConfigurationItem item(String skillId, String isSaveSession) {
        return new SkillConfigurationItem(skillId, isSaveSession);
    }
}
