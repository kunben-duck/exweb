/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.domainagentconfig;

import reactor.core.publisher.Mono;

/**
 * DomainAgent 技能配置查询防腐接口。
 *
 * <p>应用层只依赖该中立接口，不感知企业 HTTP、SGOV 鉴权或外部字段命名。</p>
 */
public interface DomainAgentSkillConfigurationProvider {
    /**
     * 查询指定技能的会话保存配置。
     *
     * @param query 可信租户、用户和技能查询条件。
     * @return 非空配置；未配置时返回 {@link DomainAgentSkillConfiguration#unconfigured(String)}。
     */
    Mono<DomainAgentSkillConfiguration> findBySkillId(DomainAgentSkillConfigurationQuery query);
}
