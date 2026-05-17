package com.huawei.finance.front.one.domain.chat;

import java.util.List;

/**
 * 会话历史消息分页结果。
 *
 * @param items 当前页消息，按创建时间正序排列。
 * @param nextCursor 下一页游标；为空表示没有更早的历史消息。
 */
public record ChatMessagePage(
        List<ChatMessage> items,
        String nextCursor
) {
    public ChatMessagePage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
