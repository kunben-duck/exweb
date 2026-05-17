package com.huawei.finance.front.one.domain.document;

import java.util.List;

/**
 * 文档库分页结果。
 *
 * @param items 当前页文档资产列表。
 * @param nextCursor 下一页游标；为空表示已经没有更多数据。
 */
public record DocumentLibraryPage(
        List<UploadedDocument> items,
        String nextCursor
) {}
