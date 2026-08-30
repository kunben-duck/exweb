/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.recovery;

import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecution;

/**
 * stale run 恢复策略上下文。
 *
 * @param run 业务 run 快照。
 * @param execution 已被判断为 stale 的执行控制面快照。
 * @param instanceId 当前执行恢复的实例 ID。
 * @param requestedStrategy 当前策略名。
 */
public record StaleRunRecoveryContext(
        ChatRun run,
        ChatRunExecution execution,
        String instanceId,
        String requestedStrategy
) {
}
