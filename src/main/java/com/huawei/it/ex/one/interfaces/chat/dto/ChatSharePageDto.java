package com.huawei.it.ex.one.interfaces.chat.dto;

import java.util.List;

/**
 * 当前用户创建的分享页码分页 DTO。
 *
 * @param items 当前页分享元数据。
 * @param curPage 当前页码，从 1 开始。
 * @param pageSize 每页条数。
 * @param totalRows 当前用户创建的分享总数。
 * @param totalPages 总页数；没有数据时为 0。
 */
public record ChatSharePageDto(
        List<ChatShareDto> items,
        int curPage,
        int pageSize,
        long totalRows,
        long totalPages
) {}
