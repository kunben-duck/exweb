package com.huawei.it.ex.one.domain.chat;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天消息分享发送记录。
 *
 * <p>分享快照和分享发送是两个独立生命周期：发送失败不会删除或撤销已创建的分享，
 * 后续可以基于该记录排障或再次调用发送接口重试。</p>
 *
 * @param id 发送记录主键。
 * @param tenantId 租户标识。
 * @param ownerUserId 分享创建者用户标识，也是首版默认发送权限边界。
 * @param shareId 被发送的分享 ID。
 * @param provider 发送 provider 编码，例如 welink。
 * @param status 发送状态，SUCCESS 或 FAILED。
 * @param targetAccounts 目标用户账号列表。
 * @param groupIds 目标群组 ID 列表。
 * @param title 发送卡片标题。
 * @param content 发送卡片正文摘要。
 * @param language 前端透传语言标识。
 * @param linkUrl 分享页面完整 URL。
 * @param providerResponse provider 安全响应摘要。
 * @param errorCode 失败错误码。
 * @param errorMessage 失败错误信息。
 * @param createdAt 创建时间。
 * @param sentAt provider 调用完成时间。
 * @param updatedAt 更新时间。
 */
public record ChatShareDelivery(
        String id,
        String tenantId,
        String ownerUserId,
        String shareId,
        String provider,
        String status,
        List<String> targetAccounts,
        List<String> groupIds,
        String title,
        String content,
        String language,
        String linkUrl,
        Map<String, Object> providerResponse,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant sentAt,
        Instant updatedAt
) {
    public ChatShareDelivery {
        targetAccounts = targetAccounts == null ? List.of() : List.copyOf(targetAccounts);
        groupIds = groupIds == null ? List.of() : List.copyOf(groupIds);
        providerResponse = immutableNullableMap(providerResponse);
        status = status == null || status.isBlank() ? "FAILED" : status;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public boolean succeeded() {
        return "SUCCESS".equals(status);
    }

    private static Map<String, Object> immutableNullableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
