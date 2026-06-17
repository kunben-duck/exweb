package com.huawei.finance.front.one.infrastructure.persistence;

import java.time.Instant;

/**
 * fin_ex_runtime_raw_stream_log_t 写入参数对象。
 */
public record RuntimeRawStreamLogWriteRow(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String runtimeProvider,
        String apiAdapter,
        long chunkIndex,
        String rawContent,
        String rawContentHash,
        int contentLength,
        int sourceContentLength,
        int chunkCount,
        int splitPartIndex,
        int splitPartCount,
        boolean truncated,
        boolean terminal,
        Instant createdAt
) {
}
