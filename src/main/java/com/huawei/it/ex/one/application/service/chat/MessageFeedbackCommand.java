package com.huawei.it.ex.one.application.service.chat;

import java.util.Map;

/**
 * 当前用户对 assistant 消息的反馈命令。
 *
 * @param messageId 被反馈的 assistant 消息 ID。
 * @param runId 可选 run ID；传入时必须与消息同会话。
 * @param rating LIKE 或 DISLIKE。
 * @param reasonCode 前端选择的原因编码，可为空。
 * @param commentText 用户补充说明，可为空。
 * @param metadata 扩展元数据，不应包含 Cookie 或认证头。
 */
public record MessageFeedbackCommand(
        String messageId,
        String runId,
        String rating,
        String reasonCode,
        String commentText,
        Map<String, Object> metadata
) {
}
