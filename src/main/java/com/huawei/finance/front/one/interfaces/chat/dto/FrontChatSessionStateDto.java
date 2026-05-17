package com.huawei.finance.front.one.interfaces.chat.dto;

/**
 * 前端进入或切换会话时的一次性状态 DTO。
 *
 * <p>该接口把会话元数据、最近一页历史消息和流式状态聚合返回，前端可以据此决定是否订阅
 * active run topic，以及是否调用 SSE resume 补发缺失事件。</p>
 *
 * @param session 会话元数据。
 * @param messages 最近一页历史消息。
 * @param streamStatus 当前会话事件流状态。
 */
public record FrontChatSessionStateDto(
        FrontChatSessionDto session,
        FrontChatMessagePageDto messages,
        FrontStreamStatusDto streamStatus
) {}
