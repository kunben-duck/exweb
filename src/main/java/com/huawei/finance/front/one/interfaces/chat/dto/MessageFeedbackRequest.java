package com.huawei.finance.front.one.interfaces.chat.dto;

import java.util.Map;

/**
 * 前端消息反馈请求。
 *
 * @param runId 反馈关联的 run 标识，可为空。
 * @param rating 反馈评级，例如 LIKE、DISLIKE。
 * @param reasonCode 结构化原因编码，可为空。
 * @param commentText 用户补充说明，可为空。
 * @param metadata 前端扩展诊断信息。
 */
public record MessageFeedbackRequest(
        String runId,
        String rating,
        String reasonCode,
        String commentText,
        Map<String, Object> metadata
) {}
