/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Trusted internal callback command after HTTP body validation. */
public record DomainAgentAsyncTaskCallbackCommand(
        String runId,
        String status,
        String resultMode,
        List<JsonNode> frames,
        String error
) {
    public DomainAgentAsyncTaskCallbackCommand {
        runId = normalize(runId);
        status = normalize(status);
        resultMode = normalize(resultMode);
        frames = frames == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(frames));
        error = normalize(error);
    }

    public DomainAgentAsyncTaskCallbackCommand(String runId, String status, String error) {
        this(runId, status, null, List.of(), error);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
