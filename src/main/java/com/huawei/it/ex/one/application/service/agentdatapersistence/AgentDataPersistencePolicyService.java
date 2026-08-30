/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.agentdatapersistence;

import com.huawei.it.ex.one.application.config.AgentDataPersistenceProperties;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationException;

import org.springframework.stereotype.Service;

/** 根据统一技能配置快照解析DomainAgent assistant留存策略。 */
@Service
public class AgentDataPersistencePolicyService {
    private final AgentDataPersistenceProperties properties;

    public AgentDataPersistencePolicyService(AgentDataPersistenceProperties properties) {
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public String placeholderContent() {
        return properties.normalizedPlaceholderContent();
    }

    public AgentDataPersistencePolicy resolve(
            String skillId,
            DomainAgentSkillConfiguration configuration) {
        if (!enabled()) {
            return AgentDataPersistencePolicy.FULL;
        }
        if (skillId == null || skillId.isBlank()) {
            throw new DomainAgentSkillConfigurationException(
                    DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID,
                    "Resolved DomainAgent route has no skillId");
        }
        return toPolicy(skillId.trim(), configuration);
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

}
