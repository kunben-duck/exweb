package com.huawei.finance.front.one.interfaces.chat.dto;

import jakarta.validation.constraints.Size;

/**
 * 前端创建聊天会话请求 DTO。
 *
 * @param title 会话标题。
 * @param channel 会话来源渠道，例如 web、im、mobile。
 * @param appId 会话所属应用标识，用于前端分组和列表过滤。
 * @param appName 会话所属应用名称快照；存在时必须同时提供 appId。
 */
public record CreateChatSessionRequest(
        String title,
        String channel,
        @Size(max = 128, message = "appId 长度不能超过 128")
        String appId,
        @Size(max = 256, message = "appName 长度不能超过 256")
        String appName
) {
    /** 兼容未携带 App Tag 的创建请求。 */
    public CreateChatSessionRequest(String title, String channel) {
        this(title, channel, null, null);
    }
}
