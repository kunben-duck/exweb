package com.huawei.it.ex.one.domain.chat;

import java.time.Instant;
import java.util.Map;

/**
 * 协议级等待用户输入请求。
 *
 * <p>该对象是后端续接状态事实源，不承担前端展示；展示快照保存在 assistant message parts。</p>
 *
 * @param id Interaction 请求 ID。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param sessionId 会话标识。
 * @param sourceRunId 触发等待态的 run。
 * @param continueRunId 用户提交后续接的 run。
 * @param userMessageId 触发当前 Interaction 的用户消息；意图多轮澄清时为最近一轮回答 user。
 * @param assistantMessageId 承载当前交互问题的 assistant 消息；意图澄清续接会在其后创建新 user/assistant。
 * @param runtimeProvider Runtime provider，例如 relay。
 * @param runtimeBindingId 触发等待态时使用的 RuntimeBinding，续接时必须复用并刷新它。
 * @param runtimeSessionId Runtime 实际会话 ID。
 * @param approvalId Relay approval_id。
 * @param interactionType 等待类型。
 * @param status 当前状态。
 * @param requestPayload Relay 原始澄清请求 payload。
 * @param responsePayload 用户提交的回答 payload。
 * @param expiresAt 过期时间。
 * @param answeredAt 已回答时间。
 * @param cancelledAt 取消时间。
 * @param createdAt 创建时间。
 * @param updatedAt 更新时间。
 */
public record ChatInteractionRequest(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String sourceRunId,
        String continueRunId,
        String userMessageId,
        String assistantMessageId,
        String runtimeProvider,
        String runtimeBindingId,
        String runtimeSessionId,
        String approvalId,
        ChatInteractionType interactionType,
        ChatInteractionStatus status,
        Map<String, Object> requestPayload,
        Map<String, Object> responsePayload,
        Instant expiresAt,
        Instant answeredAt,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt
) {
    public ChatInteractionRequest {
        requestPayload = ChatPayloadMaps.immutableCopy(requestPayload);
        responsePayload = ChatPayloadMaps.immutableCopy(responsePayload);
        interactionType = interactionType == null ? ChatInteractionType.AGENT_CLARIFICATION : interactionType;
        status = status == null ? ChatInteractionStatus.WAITING : status;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public boolean waiting() {
        return status == ChatInteractionStatus.WAITING;
    }

    public boolean expiredAt(Instant now) {
        return expiresAt != null && now != null && !expiresAt.isAfter(now);
    }
}
