package com.huawei.it.ex.one.interfaces.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Trusted DomainAgent background-task callback body. */
public record DomainAgentAsyncTaskCallbackRequest(
        @NotBlank @Size(max = 64) String runId,
        @NotBlank String status,
        String error
) {
}
