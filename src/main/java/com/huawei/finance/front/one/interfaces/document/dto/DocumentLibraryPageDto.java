package com.huawei.finance.front.one.interfaces.document.dto;

import java.util.List;

/**
 * 前端文档库游标分页 DTO。
 *
 * @param items 当前页文档列表。
 * @param nextCursor 下一页游标；为空表示没有更多数据。
 */
public record DocumentLibraryPageDto(
        List<UploadedDocumentDto> items,
        String nextCursor
) {}
