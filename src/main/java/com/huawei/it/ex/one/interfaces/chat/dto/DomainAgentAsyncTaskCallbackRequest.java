/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Trusted DomainAgent background-task callback body. */
public record DomainAgentAsyncTaskCallbackRequest(
        @NotBlank @Size(max = 64) String runId,
        @NotBlank String status,
        String resultMode,
        List<JsonNode> frames,
        String error
) {
}
