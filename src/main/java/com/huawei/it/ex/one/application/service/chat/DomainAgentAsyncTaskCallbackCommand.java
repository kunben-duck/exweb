package com.huawei.it.ex.one.application.service.chat;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Trusted internal callback command after HTTP body validation. */
public record DomainAgentAsyncTaskCallbackCommand(
        String runId,
        String status,
        String resultMode,
        List<JsonNode> frames,
        JsonNode error
) {
    public DomainAgentAsyncTaskCallbackCommand {
        runId = normalize(runId);
        status = normalize(status);
        resultMode = normalize(resultMode);
        frames = frames == null ? List.of() : List.copyOf(frames);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
