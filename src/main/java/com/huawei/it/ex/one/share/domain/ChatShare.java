package com.huawei.it.ex.one.share.domain;

import java.time.Instant;

/**
 * 单轮问答分享记录。
 *
 * <p>分享记录保存固定展示快照，访问时不再回源读取原会话消息，避免原消息编辑、删除或版本切换影响
 * 已经发出的分享链接。</p>
 *
 * @param id 分享主键，业务生成的 shareId。
 * @param tenantId 租户标识，用于企业登录后的租户级隔离。
 * @param ownerUserId 创建分享的用户标识。
 * @param sourceSessionId 来源会话 ID。
 * @param sourceUserMessageId 来源 user 问题消息 ID。
 * @param sourceAssistantMessageId 来源 assistant 回答消息 ID。
 * @param sourceRunId 来源 runId。
 * @param title 分享标题。
 * @param scope 分享范围，首版固定为 SINGLE_TURN。
 * @param visibility 访问模型，首版固定为 INTERNAL。
 * @param status 分享状态，ACTIVE 或 REVOKED。
 * @param expiresAt 过期时间；为空表示不过期。
 * @param revokedAt 撤销时间；未撤销为空。
 * @param snapshot 固定展示快照。
 * @param createdAt 创建时间。
 * @param updatedAt 更新时间。
 */
public record ChatShare(
        String id,
        String tenantId,
        String ownerUserId,
        String sourceSessionId,
        String sourceUserMessageId,
        String sourceAssistantMessageId,
        String sourceRunId,
        String title,
        String scope,
        String visibility,
        String status,
        Instant expiresAt,
        Instant revokedAt,
        ChatShareSnapshot snapshot,
        Instant createdAt,
        Instant updatedAt
) {
    public ChatShare {
        scope = scope == null || scope.isBlank() ? "SINGLE_TURN" : scope;
        visibility = visibility == null || visibility.isBlank() ? "INTERNAL" : visibility;
        status = status == null || status.isBlank() ? "ACTIVE" : status;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public boolean revoked() {
        return "REVOKED".equals(status);
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now == null ? Instant.now() : now);
    }

    public ChatShare revoke(Instant now) {
        Instant timestamp = now == null ? Instant.now() : now;
        return new ChatShare(id, tenantId, ownerUserId, sourceSessionId, sourceUserMessageId,
                sourceAssistantMessageId, sourceRunId, title, scope, visibility, "REVOKED",
                expiresAt, revokedAt == null ? timestamp : revokedAt, snapshot, createdAt, timestamp);
    }
}
