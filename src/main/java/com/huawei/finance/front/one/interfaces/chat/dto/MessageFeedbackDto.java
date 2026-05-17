package com.huawei.finance.front.one.interfaces.chat.dto;

import java.time.Instant;

/**
 * 前端消息反馈响应 DTO。
 *
 * @param feedbackId 反馈记录标识。
 * @param messageId 被反馈消息标识。
 * @param runId 关联 run 标识，可为空。
 * @param rating 反馈评级。
 * @param createdAt 反馈创建时间。
 */
public record MessageFeedbackDto(
        String feedbackId,
        String messageId,
        String runId,
        String rating,
        Instant createdAt
) {}
