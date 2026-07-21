package com.huawei.it.ex.one.share.interfaces.dto;

/**
 * 创建分享并发送的组合响应。
 *
 * @param share 已创建的分享快照元数据。
 * @param delivery 分享发送结果。
 */
public record ChatShareAndDeliveryDto(
        ChatShareDto share,
        ChatShareDeliveryDto delivery
) {
}
