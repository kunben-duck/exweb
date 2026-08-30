/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

/**
 * 前端会话应用分类 DTO。
 *
 * @param appId 应用分类标识。
 * @param appName 应用展示名称；没有已保存名称时为空。
 */
public record ChatSessionAppDto(
        String appId,
        String appName
) {}
