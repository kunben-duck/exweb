/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

/** stream-status 返回的当前聚合意图专家范围。 */
public record ChatIntentExpertScopeDto(
        String expertId,
        String expertName,
        String intentAccessName
) {
}
