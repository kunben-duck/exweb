package com.huawei.finance.front.one.infrastructure.persistence;

import java.time.Instant;

/** fin_ex_chat_run_t 外部终态条件更新参数。 */
public record ChatRunExternalTerminalClaimRow(
        String runId,
        String tenantId,
        String userId,
        String sessionId,
        String terminalStatus,
        String cancelReason,
        Instant finishedAt,
        String guard,
        String recoveredByInstanceId,
        Long fencingToken,
        String interactionId,
        Instant orphanBefore
) {
}
