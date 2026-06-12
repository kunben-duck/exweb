package com.huawei.finance.front.one.interfaces.chat.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 前端历史消息结构化 part DTO。
 *
 * <p>assistant 正文仍通过 {@link ChatMessageDto#content()} 展示；parts 用于刷新历史后恢复
 * 思考、工具调用、进度、引用、agent 调用和最终回答快照等过程信息。</p>
 *
 * @param partId part 标识。
 * @param messageId 所属 assistant 消息标识。
 * @param runId 产生该 part 的 run 标识。
 * @param partType part 类型，例如 ANSWER、THINKING、TOOL、PROGRESS、REFERENCE。
 * @param sourceType 下游原始事件类型，例如 agent、relay-progress。
 * @param contentText 可展示文本摘要。
 * @param title 前端展示标题。
 * @param status 展示状态，例如 INFO、STARTED、STREAMING、COMPLETED、FAILED、UNKNOWN。
 * @param channel 展示频道，例如 answer、progress、thinking、tool。
 * @param displayHint 展示建议，例如 inline、collapsible、hidden、debug。
 * @param visible 是否默认展示。
 * @param payload 结构化展示载荷。
 * @param partOrder 同一 assistant 消息内展示顺序。
 * @param createdAt part 创建时间。
 */
public record ChatMessagePartDto(
        String partId,
        String messageId,
        String runId,
        String partType,
        String sourceType,
        String contentText,
        String title,
        String status,
        String channel,
        String displayHint,
        Boolean visible,
        Map<String, Object> payload,
        Integer partOrder,
        Instant createdAt
) {}
