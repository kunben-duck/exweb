package com.huawei.finance.front.one.interfaces.chat.dto;

import java.util.List;

/**
 * 前端历史消息分页 DTO。
 *
 * @param items 当前页历史消息，按创建时间正序排列。
 * @param nextCursor 下一页游标；为空表示没有更早消息。
 */
public record ChatMessagePageDto(
        List<ChatMessageDto> items,
        String nextCursor
) {}
