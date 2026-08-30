/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

import java.util.List;

/**
 * 前端会话列表分页 DTO。
 *
 * @param items 当前页会话，按最近更新时间倒序排列。
 * @param nextCursor 下一页游标；为空表示没有更多会话。
 */
public record ChatSessionPageDto(
        List<ChatSessionDto> items,
        String nextCursor
) {}
