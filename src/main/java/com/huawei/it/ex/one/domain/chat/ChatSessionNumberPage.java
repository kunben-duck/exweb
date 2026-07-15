package com.huawei.it.ex.one.domain.chat;

import java.util.List;

/**
 * 基于页码的聊天会话分页结果。
 *
 * <p>该模型用于前端需要 {@code curPage/pageSize/totalRows} 的传统分页场景。
 * 现有游标分页仍由 {@link ChatSessionPage} 承载，两者互不替代。</p>
 *
 * @param items 当前页会话，按最近更新时间倒序排列。
 * @param curPage 当前页码，从 1 开始。
 * @param pageSize 每页条数，应用层会做最大值保护。
 * @param totalRows 满足过滤条件的总会话数。
 * @param totalPages 根据 totalRows 与 pageSize 计算出的总页数。
 */
public record ChatSessionNumberPage(
        List<ChatSession> items,
        int curPage,
        int pageSize,
        long totalRows,
        long totalPages
) {
    public ChatSessionNumberPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
