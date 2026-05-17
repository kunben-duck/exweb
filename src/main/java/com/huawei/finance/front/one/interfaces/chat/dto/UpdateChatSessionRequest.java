package com.huawei.finance.front.one.interfaces.chat.dto;

/**
 * 前端更新会话元数据请求。
 *
 * @param title 新会话标题；为空时保留原标题。
 */
public record UpdateChatSessionRequest(
        String title
) {}
