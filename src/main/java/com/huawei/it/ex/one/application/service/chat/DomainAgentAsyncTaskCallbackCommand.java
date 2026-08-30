/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

/** Trusted internal callback command after HTTP body validation. */
public record DomainAgentAsyncTaskCallbackCommand(
        String runId,
        String status,
        String error
) {
    public DomainAgentAsyncTaskCallbackCommand {
        runId = normalize(runId);
        status = normalize(status);
        error = normalize(error);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
