/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

import java.util.List;

/**
 * 当前用户创建的分享页码分页结果。
 */
public record ChatSharePage(
        List<ChatShareSummary> items,
        int curPage,
        int pageSize,
        long totalRows,
        long totalPages
) {
    public ChatSharePage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
