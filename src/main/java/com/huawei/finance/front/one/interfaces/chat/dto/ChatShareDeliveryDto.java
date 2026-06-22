package com.huawei.finance.front.one.interfaces.chat.dto;

import java.time.Instant;

/**
 * 分享发送结果。
 *
 * @param deliveryId 发送记录 ID。
 * @param shareId 分享 ID。
 * @param provider 发送 provider 编码。
 * @param status 发送状态，SUCCESS 或 FAILED。
 * @param linkUrl 发送给 provider 的分享页面 URL。
 * @param errorCode 失败错误码。
 * @param errorMessage 失败错误信息。
 * @param sentAt provider 调用完成时间。
 * @param createdAt 发送记录创建时间。
 * @param updatedAt 发送记录更新时间。
 */
public record ChatShareDeliveryDto(
        String deliveryId,
        String shareId,
        String provider,
        String status,
        String linkUrl,
        String errorCode,
        String errorMessage,
        Instant sentAt,
        Instant createdAt,
        Instant updatedAt
) {
}
