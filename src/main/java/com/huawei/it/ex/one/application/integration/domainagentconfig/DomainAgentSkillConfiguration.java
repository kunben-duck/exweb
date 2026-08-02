package com.huawei.it.ex.one.application.integration.domainagentconfig;

/**
 * ChatService 可理解的 DomainAgent 技能配置。
 *
 * @param skillId 技能标识。
 * @param saveSession {@code false} 表示明确不保存，{@code true} 表示明确保存，{@code null} 表示未配置。
 */
public record DomainAgentSkillConfiguration(
        String skillId,
        Boolean saveSession
) {
    /**
     * 构造未配置结果，避免 Provider 使用 {@code null} 表示查询成功但没有配置。
     */
    public static DomainAgentSkillConfiguration unconfigured(String skillId) {
        return new DomainAgentSkillConfiguration(skillId, null);
    }
}
