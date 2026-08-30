/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.domainagentconfig;

/**
 * ChatService 可理解的 DomainAgent 技能配置。
 *
 * @param skillId 技能标识。
 * @param skillName 技能展示名称。
 * @param saveSession {@code false} 表示明确不保存，{@code true} 表示明确保存，{@code null} 表示未配置。
 * @param attachmentType 技能配置的附件扩展名范围原始值；空值表示不限制。
 */
public record DomainAgentSkillConfiguration(
        String skillId,
        String skillName,
        Boolean saveSession,
        String attachmentType
) {
    public DomainAgentSkillConfiguration(String skillId, Boolean saveSession) {
        this(skillId, null, saveSession, null);
    }

    /**
     * 构造未配置结果，避免 Provider 使用 {@code null} 表示查询成功但没有配置。
     */
    public static DomainAgentSkillConfiguration unconfigured(String skillId) {
        return new DomainAgentSkillConfiguration(skillId, null, null, null);
    }
}
