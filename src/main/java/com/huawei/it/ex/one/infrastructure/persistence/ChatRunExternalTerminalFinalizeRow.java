/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import java.time.Instant;

/** fin_ex_chat_run_t 外部终态游标回填参数。 */
public record ChatRunExternalTerminalFinalizeRow(
        String runId,
        String tenantId,
        String userId,
        String sessionId,
        String terminalStatus,
        long sequence,
        String cancelReason,
        Instant finishedAt
) {
}
