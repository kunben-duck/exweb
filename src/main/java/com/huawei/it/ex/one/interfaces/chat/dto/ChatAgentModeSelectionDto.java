/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 单个 Agent 模式维度。 */
public record ChatAgentModeSelectionDto(
        @NotBlank(message = "agentMode.selections[].scheme 不能为空")
        @Size(max = 64, message = "agentMode.selections[].scheme 长度不能超过 64")
        String scheme,
        @NotBlank(message = "agentMode.selections[].code 不能为空")
        @Size(max = 128, message = "agentMode.selections[].code 长度不能超过 128")
        String code,
        @Size(max = 256, message = "agentMode.selections[].displayName 长度不能超过 256")
        String displayName
) {
}
