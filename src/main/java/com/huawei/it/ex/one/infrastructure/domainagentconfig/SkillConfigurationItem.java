/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** 企业技能配置服务中ChatService需要的最小配置项。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillConfigurationItem(
        String skillId,
        String skillName,
        String isSaveSession,
        String attachmentType,
        JsonNode allowedUploadCount
) {
    public SkillConfigurationItem(
            String skillId, String skillName, String isSaveSession, String attachmentType) {
        this(skillId, skillName, isSaveSession, attachmentType, null);
    }
}
