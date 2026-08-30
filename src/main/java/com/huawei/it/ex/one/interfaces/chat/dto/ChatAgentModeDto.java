/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Agent 模式完整快照。
 *
 * <p>字段本身非必填；对象存在时 selections 必须存在，空列表表示显式清除已有模式。</p>
 */
public record ChatAgentModeDto(
        @NotNull(message = "agentMode.selections 不能为空")
        @Size(max = 16, message = "agentMode.selections 最多允许 16 项")
        List<@Valid ChatAgentModeSelectionDto> selections
) {
}
