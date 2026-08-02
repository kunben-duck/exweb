package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** 企业技能配置服务的最小响应模型。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillConfigurationResponse(
        String status,
        List<SkillConfigurationItem> data
) {
}
