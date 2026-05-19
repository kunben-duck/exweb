package com.huawei.finance.front.one.interfaces.chat.dto;

/**
 * 切换当前会话 active path 的请求。
 *
 * @param leafMessageId 目标路径叶子消息 ID。
 */
public record SelectChatPathRequest(
        String leafMessageId
) {}
