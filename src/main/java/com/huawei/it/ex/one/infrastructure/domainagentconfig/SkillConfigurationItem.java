package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 企业技能配置服务中ChatService需要的最小配置项。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillConfigurationItem(
        String skillId,
        String skillName,
        String isSaveSession,
        String attachmentType
) {
}
