package com.huawei.it.ex.one.interfaces.chat.dto;

import java.time.Instant;

/**
 * 前端消息反馈响应 DTO。
 *
 * @param feedbackId 反馈记录标识。
 * @param messageId 被反馈消息标识。
 * @param runId 关联 run 标识，可为空。
 * @param rating 反馈评级，ACTIVE 状态下为 LIKE 或 DISLIKE；取消时可能为空或保留最后一次评级。
 * @param status 当前反馈状态，ACTIVE 表示当前用户仍保留反馈，CANCELLED 表示已取消。
 * @param reasonCode 结构化反馈原因编码。
 * @param commentText 用户补充的反馈说明文本。
 * @param createdAt 反馈创建时间。
 * @param updatedAt 反馈最后更新时间。
 */
public record MessageFeedbackDto(
        String feedbackId,
        String messageId,
        String runId,
        String rating,
        String status,
        String reasonCode,
        String commentText,
        Instant createdAt,
        Instant updatedAt
) {}
