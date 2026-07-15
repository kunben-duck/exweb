package com.huawei.it.ex.one.domain.chat;

import java.util.List;

/**
 * 聊天会话分页结果。
 *
 * @param items 当前页会话，按最近更新时间倒序排列。
 * @param nextCursor 下一页游标；为空表示没有更多会话。
 */
public record ChatSessionPage(
        List<ChatSession> items,
        String nextCursor
) {
    public ChatSessionPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
