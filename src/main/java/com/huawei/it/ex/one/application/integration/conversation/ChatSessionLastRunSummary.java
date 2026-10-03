/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.conversation;

import com.huawei.it.ex.one.domain.chat.ChatRunStatus;

/** 当前页一个会话最后创建的run及状态；轻量查询不装配Runtime调用标识。 */
public record ChatSessionLastRunSummary(
        ChatRunStatus status,
        String skillId,
        String runId
) {
    public ChatSessionLastRunSummary(ChatRunStatus status, String skillId) {
        this(status, skillId, null);
    }
}
