package com.huawei.finance.front.one.interfaces.chat.dto;

/**
 * 前端创建聊天会话请求 DTO。
 *
 * @param title 会话标题。
 * @param channel 会话来源渠道，例如 web、im、mobile。
 */
public record CreateChatSessionRequest(
        String title,
        String channel
) {}
