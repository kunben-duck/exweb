package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;

/**
 * Runtime 原始流响应日志行。
 *
 * <p>该记录保存的是 normalizer 之前的下游响应文本。它只用于排障和协议分析，不作为
 * WebSocket/Event Resume 的事实源。</p>
 *
 * @param id 日志主键。
 * @param tenantId 租户标识。
 * @param userId 用户标识。
 * @param sessionId ChatService 会话标识。
 * @param runId ChatService run 标识。
 * @param runtimeProvider Runtime provider，例如 relay。
 * @param apiAdapter Runtime API adapter，例如 relay-stream-http。
 * @param chunkIndex 本 run 内日志片段顺序。
 * @param rawContent 保存的原始响应文本，可能是合并片段或分片。
 * @param rawContentHash 保存内容的 SHA-256 hash。
 * @param contentLength 本行保存文本长度。
 * @param sourceContentLength 本行对应脱敏前原始内容长度；脱敏或截断时可能不同于 contentLength。
 * @param chunkCount 本行合并的下游原始 chunk 数。
 * @param splitPartIndex 单个超大 chunk 分片序号；普通合并为 0。
 * @param splitPartCount 单个超大 chunk 分片总数；普通合并为 0。
 * @param truncated 是否确实丢弃了部分原始内容。
 * @param terminal 是否包含下游流终态标记。
 * @param createdAt 日志创建时间。
 */
public record RuntimeRawStreamLogEntry(
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
