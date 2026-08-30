/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.conversation;

import com.huawei.it.ex.one.domain.chat.ChatRunStatus;

/** 当前页一个会话最后创建的run状态及其最终Runtime调用标识。 */
public record ChatSessionLastRunSummary(
        ChatRunStatus status,
        String skillId
) {
}
