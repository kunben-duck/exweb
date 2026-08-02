package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import java.util.List;

/** 企业内部技能配置同步调用边界。 */
@FunctionalInterface
public interface DomainAgentSkillConfigurationClient {
    /**
     * 批量查询技能配置。
     *
     * @param skillIds 待查询的技能标识。
    * @return 企业服务响应，不得返回 {@code null}。
     */
    SkillConfigurationResponse findBySkillIds(List<String> skillIds);
}
