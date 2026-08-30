/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.domainagentconfig;

import java.time.Duration;
import java.util.Optional;

/** 跨实例共享的DomainAgent完整技能配置缓存。 */
public interface DomainAgentSkillConfigurationCache {
    Optional<DomainAgentSkillConfiguration> get(String tenantId, String skillId);

    void put(String tenantId, String skillId, DomainAgentSkillConfiguration configuration, Duration ttl);
}
