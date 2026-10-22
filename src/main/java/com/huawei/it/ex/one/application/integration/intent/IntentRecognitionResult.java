/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.intent;

import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;
import com.huawei.it.ex.one.domain.intent.IntentDecision;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;

/**
 * 意图服务多轮识别结果。
 *
 * <p>意图澄清属于路由阶段，不创建 RuntimeBinding，也不调用 AgentRuntime。该模型让应用层能区分
 * “已经有最终意图”和“还需要继续向用户追问”，同时保留旧 IntentDecision 作为 FINAL 兼容路径。
 * auditRawResponse 仅供异步识别记录使用，不属于公开澄清数据。</p>
 */
public record IntentRecognitionResult(
        Status status,
        IntentDecision decision,
        Map<String, Object> clarificationPayload,
        String intentSessionId,
        String intentRequestId,
        @JsonIgnore Map<String, Object> auditRawResponse
) {
    public enum Status {
        FINAL,
        WAITING_CLARIFICATION,
        FAILED_OR_DEGRADED
    }

    public IntentRecognitionResult {
        status = status == null ? Status.FINAL : status;
        clarificationPayload = clarificationPayload == null ? Map.of() : Map.copyOf(clarificationPayload);
        auditRawResponse = ChatPayloadMaps.immutableCopy(auditRawResponse);
    }

    public IntentRecognitionResult(Status status, IntentDecision decision, Map<String, Object> clarificationPayload,
                                   String intentSessionId, String intentRequestId) {
        this(status, decision, clarificationPayload, intentSessionId, intentRequestId, Map.of());
    }

    public static IntentRecognitionResult finalDecision(IntentDecision decision) {
        return new IntentRecognitionResult(Status.FINAL, decision, Map.of(), null, null);
    }

    public static IntentRecognitionResult degraded(IntentDecision decision) {
        return new IntentRecognitionResult(Status.FAILED_OR_DEGRADED, decision, Map.of(), null, null);
    }

    public static IntentRecognitionResult waitingClarification(Map<String, Object> payload,
                                                               String intentSessionId,
                                                               String intentRequestId) {
        return new IntentRecognitionResult(Status.WAITING_CLARIFICATION, null,
                payload, intentSessionId, intentRequestId);
    }

    public boolean waitingClarification() {
        return status == Status.WAITING_CLARIFICATION;
    }

    public Map<String, Object> normalizedClarificationPayload() {
        if (clarificationPayload == null || clarificationPayload.isEmpty()) {
            return Map.of("questions", List.of());
        }
        return clarificationPayload;
    }
}
