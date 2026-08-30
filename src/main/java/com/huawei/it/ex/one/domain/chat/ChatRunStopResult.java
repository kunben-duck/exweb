/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

import java.time.Instant;

/**
 * stop 接口返回给前端的标准结果。
 *
 * @param runId 被停止或查询的 run 标识。
 * @param sessionId run 所属聊天会话标识。
 * @param status stop 后的 run 状态。
 * @param latestSeq 当前 run 最后一个已持久化事件序号。
 * @param stoppedAt run 进入终态的时间；未终态时为当前响应时间。
 * @param messageReady 是否已有可反馈的 assistant 消息。
 * @param assistantMessageId stop 后可见的 assistant 消息 ID；没有可保存内容时为空。
 * @param feedbackTargetMessageId 前端点赞/点踩应使用的消息 ID；当前等同 assistantMessageId。
 * @param waitingUserInput stop 后当前请求是否仍在等待用户输入。
 * @param interactionId 本次等待态 stop 定位到的 Interaction ID。
 * @param interactionStatus stop 后的 Interaction 状态。
 * @param interactionCancelledAt Interaction 取消时间。
 * @param effectiveRunId 请求历史 run-A 时实际被停止的 continuation run-B。
 */
public record ChatRunStopResult(
        String runId,
        String sessionId,
        ChatRunStatus status,
        long latestSeq,
        Instant stoppedAt,
        boolean messageReady,
        String assistantMessageId,
        String feedbackTargetMessageId,
        boolean waitingUserInput,
        String interactionId,
        String interactionStatus,
        Instant interactionCancelledAt,
        String effectiveRunId
) {
    public ChatRunStopResult(String runId, String sessionId, ChatRunStatus status,
                             long latestSeq, Instant stoppedAt, boolean messageReady,
                             String assistantMessageId, String feedbackTargetMessageId) {
        this(runId, sessionId, status, latestSeq, stoppedAt, messageReady,
                assistantMessageId, feedbackTargetMessageId, false, null, null, null, null);
    }

    public ChatRunStopResult(String runId, String sessionId, ChatRunStatus status,
                             long latestSeq, Instant stoppedAt) {
        this(runId, sessionId, status, latestSeq, stoppedAt, false, null, null);
    }

    /** 使用请求中的 source run 保持兼容，同时附加等待态取消结果。 */
    public ChatRunStopResult withWaitingInteraction(
            String nextInteractionId,
            String nextInteractionStatus,
            Instant nextInteractionCancelledAt,
            String nextEffectiveRunId) {
        return new ChatRunStopResult(
                runId, sessionId, status, latestSeq, stoppedAt, messageReady,
                assistantMessageId, feedbackTargetMessageId, false,
                nextInteractionId, nextInteractionStatus, nextInteractionCancelledAt, nextEffectiveRunId);
    }
}
