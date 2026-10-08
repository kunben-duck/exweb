/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.agentdatapersistence;

import com.huawei.it.ex.one.domain.document.UploadedDocument;

import java.util.List;
import java.util.Map;

/** 数量校验只读取已解析的本次附件列表，不累计历史附件或采信 metadata。 */
final class DomainAgentAttachmentCountValidator {
    boolean exceeds(List<UploadedDocument> documents, Integer limit) {
        return limit != null && limit >= 0 && documents != null && documents.size() > limit;
    }

    Map<String, Object> payload(
            String skillId, String skillName, int actualCount, int maxCount, String limitSource) {
        String subject = "SERVICE".equals(limitSource) ? "服务端" : "该技能";
        return Map.of(
                "source", "chatservice",
                "sourceType", DomainAgentAttachmentTypeValidator.SOURCE_TYPE,
                "code", "DOMAIN_AGENT_ATTACHMENT_COUNT_EXCEEDED",
                "skillId", skillId,
                "skillName", skillName == null || skillName.isBlank() ? skillId : skillName.trim(),
                "actualAttachmentCount", actualCount,
                "maxAttachmentCount", maxCount,
                "limitSource", limitSource,
                "message", subject + "最多支持上传" + maxCount + "个附件，本次上传" + actualCount
                        + "个，请减少附件后重试。");
    }
}
