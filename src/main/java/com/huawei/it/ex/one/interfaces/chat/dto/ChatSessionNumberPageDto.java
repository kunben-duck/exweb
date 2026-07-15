package com.huawei.it.ex.one.interfaces.chat.dto;

import java.util.List;

/**
 * 前端会话列表页码分页 DTO。
 *
 * @param items 当前页会话，按最近更新时间倒序排列，每个会话可包含 firstAssistantAnswer 摘要。
 * @param curPage 当前页码，从 1 开始。
 * @param pageSize 每页条数。
 * @param totalRows 满足当前用户和状态过滤条件的总会话数。
 * @param totalPages 总页数；当 totalRows 为 0 时返回 0。
 */
public record ChatSessionNumberPageDto(
        List<ChatSessionDto> items,
        int curPage,
        int pageSize,
        long totalRows,
        long totalPages
) {}
