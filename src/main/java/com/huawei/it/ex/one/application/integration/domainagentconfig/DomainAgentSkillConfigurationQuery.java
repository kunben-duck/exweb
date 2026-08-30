/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.domainagentconfig;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;

/**
 * DomainAgent 技能配置查询条件。
 *
 * @param tenantId 可信租户标识。
 * @param userId 可信用户标识。
 * @param skillId 可信路由得到的 DomainAgent skillId。
 * @param forwardHeaders 当前请求的内存态出站请求头快照。
 */
public record DomainAgentSkillConfigurationQuery(
        String tenantId,
        String userId,
        String skillId,
        RuntimeForwardHeaders forwardHeaders
) {
    public DomainAgentSkillConfigurationQuery {
        if (skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("DomainAgent skillId 不能为空");
        }
        skillId = skillId.trim();
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }

    /** 保留不需要出站请求头的内部调用兼容入口。 */
    public DomainAgentSkillConfigurationQuery(String tenantId, String userId, String skillId) {
        this(tenantId, userId, skillId, RuntimeForwardHeaders.empty());
    }
}
