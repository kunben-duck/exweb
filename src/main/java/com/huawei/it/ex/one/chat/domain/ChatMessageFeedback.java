package com.huawei.it.ex.one.chat.domain;

import java.time.Instant;
import java.util.Map;

/**
 * 用户对 assistant 消息的反馈事实。
 *
 * <p>反馈数据用于产品体验分析、模型/Agent 评估和问题追踪。它独立于消息正文保存，
 * 不改变原始聊天历史。</p>
 *
 * @param id 反馈记录唯一标识。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param sessionId 消息所属会话标识。
 * @param messageId 被反馈的消息标识。
 * @param runId 反馈关联的 run 标识，可为空。
 * @param rating 反馈评级，例如 LIKE、DISLIKE；取消状态下保留最后一次有效评级或为空。
 * @param status 当前反馈状态，ACTIVE 表示前端应高亮，CANCELLED 表示当前用户已撤销反馈。
 * @param reasonCode 结构化原因编码。
 * @param commentText 用户补充说明。
 * @param metadata 扩展诊断字段。
 * @param createdAt 创建时间。
 * @param updatedAt 更新时间。
 */
public record ChatMessageFeedback(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String messageId,
        String runId,
        String rating,
        String status,
        String reasonCode,
        String commentText,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public ChatMessageFeedback {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
