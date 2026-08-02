package com.huawei.it.ex.one.application.integration.domainagentconfig;

/**
 * DomainAgent 技能配置查询条件。
 *
 * @param tenantId 可信租户标识。
 * @param userId 可信用户标识。
 * @param skillId 可信路由得到的 DomainAgent skillId。
 */
public record DomainAgentSkillConfigurationQuery(
        String tenantId,
        String userId,
        String skillId
) {
    public DomainAgentSkillConfigurationQuery {
        if (skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("DomainAgent skillId 不能为空");
        }
        skillId = skillId.trim();
    }
}
