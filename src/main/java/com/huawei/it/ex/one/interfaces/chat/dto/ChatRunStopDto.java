/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 前端 stop 接口返回 DTO。
 *
 * @param runId 被停止或查询的 run 标识。
 * @param sessionId run 所属聊天会话标识。
 * @param status stop 后的 run 状态。
 * @param latestSeq 当前 run 最后一个已持久化事件序号。
 * @param stoppedAt run 进入终态的时间；未终态时为当前响应时间。
 * @param messageReady 是否已有可反馈的 assistant 消息。
 * @param assistantMessageId stop 后可见的 assistant 消息 ID；没有可保存内容时为空。
 * @param feedbackTargetMessageId 前端点赞/点踩应使用的消息 ID。
 * @param waitingUserInput stop 后是否仍在等待用户输入。
 * @param interactionId 本次等待态 stop 对应的 Interaction ID。
 * @param interactionStatus stop 后的 Interaction 状态。
 * @param interactionCancelledAt Interaction 取消时间。
 * @param effectiveRunId 请求历史等待 run 时实际被停止的 continuation run。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRunStopDto(
        String runId,
        String sessionId,
        String status,
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
) {}
