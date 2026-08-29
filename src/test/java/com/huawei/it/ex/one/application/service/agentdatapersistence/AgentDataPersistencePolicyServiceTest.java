package com.huawei.it.ex.one.application.service.agentdatapersistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationException;

import org.junit.jupiter.api.Test;

class AgentDataPersistencePolicyServiceTest {

    @Test
    void disabledFeatureAlwaysUsesFullPolicy() {
        AgentDataPersistencePolicyService service = service(false);

        assertThat(service.resolve("skill-1", new DomainAgentSkillConfiguration(
                "skill-1", "Skill", Boolean.FALSE, ".pdf")))
                .isEqualTo(AgentDataPersistencePolicy.FULL);
    }

    @Test
    void explicitNoUsesPlaceholderPolicy() {
        AgentDataPersistencePolicyService service = service(true);

        assertThat(service.resolve("skill-1", new DomainAgentSkillConfiguration(
                "skill-1", "Skill", Boolean.FALSE, ".pdf")))
                .isEqualTo(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
    }

    @Test
    void explicitYesAndUnconfiguredUseFullPolicy() {
        AgentDataPersistencePolicyService service = service(true);

        assertThat(service.resolve("skill-1", new DomainAgentSkillConfiguration(
                "skill-1", "Skill", Boolean.TRUE, ".pdf")))
                .isEqualTo(AgentDataPersistencePolicy.FULL);
        assertThat(service.resolve("skill-1", DomainAgentSkillConfiguration.unconfigured("skill-1")))
                .isEqualTo(AgentDataPersistencePolicy.FULL);
    }

    @Test
    void mismatchedSkillIsRejected() {
        AgentDataPersistencePolicyService service = service(true);

        assertThatThrownBy(() -> service.resolve("skill-1", new DomainAgentSkillConfiguration(
                "skill-2", "Skill", Boolean.TRUE, ".pdf")))
                .isInstanceOfSatisfying(DomainAgentSkillConfigurationException.class,
                        error -> assertThat(error.reason()).isEqualTo(
                                DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID));
    }

    private AgentDataPersistencePolicyService service(boolean enabled) {
        AgentDataPersistenceProperties properties = new AgentDataPersistenceProperties();
        properties.setEnabled(enabled);
        return new AgentDataPersistencePolicyService(properties);
    }
}
