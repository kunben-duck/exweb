/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

import jakarta.validation.constraints.NotBlank;

/** Intent候选技能查询请求。 */
public record IntentCandidateQueryRequest(
        @NotBlank(message = "messageId不能为空")
        String messageId
) {
}
