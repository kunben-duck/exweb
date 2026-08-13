package com.huawei.it.ex.one.interfaces.chat.dto;

import java.util.List;

/**
 * 分享发送请求。
 *
 * @param provider 发送 provider 编码，例如 welink。
 * @param targetAccounts 目标用户账号列表。
 * @param groupIds 目标群组 ID 列表。
 * @param title 分享卡片标题覆盖值；为空时使用分享标题。
 * @param content 分享卡片正文；为空时发送空字符串，非空时转换为纯文本摘要。
 * @param language 前端透传语言标识。
 */
public record CreateChatShareDeliveryRequest(
        String provider,
        List<String> targetAccounts,
        List<String> groupIds,
        String title,
        String content,
        String language
) {
}
