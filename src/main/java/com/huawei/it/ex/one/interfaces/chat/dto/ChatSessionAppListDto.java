/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

import java.util.List;

/**
 * 当前用户会话应用分类列表。
 *
 * @param items 按最近会话活动时间倒序排列的应用分类。
 */
public record ChatSessionAppListDto(
        List<ChatSessionAppDto> items
) {
    public ChatSessionAppListDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
