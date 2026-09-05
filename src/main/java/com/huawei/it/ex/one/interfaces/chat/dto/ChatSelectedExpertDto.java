/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

import jakarta.validation.constraints.Size;

/** 前端选择的聚合意图专家展示摘要。 */
public record ChatSelectedExpertDto(
        @Size(max = 128, message = "selectedExpert.expertId 长度不能超过 128")
        String expertId,
        @Size(max = 256, message = "selectedExpert.expertName 长度不能超过 256")
        String expertName
) {
}
