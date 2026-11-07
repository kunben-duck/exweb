/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** 从Intent候选中选择DomainAgent或Relay专家并立即替换当前Run。 */
public record SwitchDomainAgentRequest(
        @NotBlank(message = "messageId不能为空")
        @Size(max = 64, message = "messageId长度不能超过64")
        String messageId,
        @NotBlank(message = "skillId不能为空")
        @Size(max = 128, message = "skillId长度不能超过128")
        String skillId,
        @NotNull(message = "selectedIntent不能为空")
        @Valid
        ChatSelectedIntentDto selectedIntent,
        @Size(max = 50, message = "metadata最多允许50个字段")
        Map<String, Object> metadata,
        @Valid
        ChatAgentModeDto agentMode,
        @Size(max = 128, message = "intentAccessName长度不能超过128")
        String intentAccessName,
        @Size(max = 64, message = "channel长度不能超过64")
        String channel
) {
    public SwitchDomainAgentRequest(String messageId, String skillId, ChatSelectedIntentDto selectedIntent,
                                     Map<String, Object> metadata, ChatAgentModeDto agentMode,
                                     String intentAccessName) {
        this(messageId, skillId, selectedIntent, metadata, agentMode, intentAccessName, null);
    }
}
