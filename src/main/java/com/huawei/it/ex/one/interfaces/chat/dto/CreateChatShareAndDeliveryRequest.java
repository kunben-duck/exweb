package com.huawei.it.ex.one.interfaces.chat.dto;

import java.time.Instant;
import java.util.List;

/**
 * 创建分享并立即发送的便捷请求。
 *
 * @param title 分享标题。
 * @param expiresAt 分享过期时间。
 * @param provider 发送 provider 编码，例如 welink。
 * @param targetAccounts 目标用户账号列表。
 * @param groupIds 目标群组 ID 列表。
 * @param content 分享卡片正文覆盖值。
 * @param language 前端透传语言标识。
 */
public record CreateChatShareAndDeliveryRequest(
        String title,
        Instant expiresAt,
        String provider,
        List<String> targetAccounts,
        List<String> groupIds,
        String content,
        String language
) {
}
