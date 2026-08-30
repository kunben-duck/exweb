/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request to persist one user-selected Intent routing preference. */
public record IntentPreferenceCorrectionRequest(
        @NotBlank(message = "selectionType不能为空")
        @Size(max = 32, message = "selectionType长度不能超过32")
        String selectionType,
        @Size(max = 64, message = "sourceMessageId长度不能超过64")
        String sourceMessageId,
        @Valid
        ChatSelectedIntentDto selectedIntent,
        @Size(max = 64, message = "interactionId长度不能超过64")
        String interactionId,
        @Size(max = 128, message = "intentAccessName长度不能超过128")
        String intentAccessName
) {
}
