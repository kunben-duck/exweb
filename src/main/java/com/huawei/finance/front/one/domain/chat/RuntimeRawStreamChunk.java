package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;

/**
 * 下游 Runtime 返回的一段原始流式响应。
 *
 * <p>该模型只用于 raw stream log 诊断链路，不进入前端协议，也不参与 assistant 历史消息拼接。</p>
 *
 * @param tenantId 租户标识，来自本轮 run 的用户上下文。
 * @param userId 用户标识，来自本轮 run 的用户上下文。
 * @param sessionId ChatService 会话标识。
 * @param runId ChatService run 标识。
 * @param runtimeProvider Runtime provider，例如 relay。
 * @param apiAdapter Runtime API adapter，例如 relay-stream-http。
 * @param chunkIndex 本 run 内原始 chunk 顺序，由 ChatService 捕获端生成，用于 MQ 排障和消费端排序参考。
 * @param content 下游返回的原始文本片段。
 * @param sourceContentLength 捕获端看到的原始内容长度；发送前截断时会大于 {@code content.length()}。
 * @param truncated 捕获端是否已经丢弃过部分原始内容。
 * @param terminalCandidate 捕获端是否识别到下游终态标记；消费端仍会做兜底判断。
 * @param receivedAt 接收到该片段的时间。
 */
public record RuntimeRawStreamChunk(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String runtimeProvider,
        String apiAdapter,
        long chunkIndex,
        String content,
        int sourceContentLength,
        boolean truncated,
        boolean terminalCandidate,
        Instant receivedAt
) {
    /**
     * MQ 顺序键。按 run 粒度发送可最大化保证单次回答内 raw chunk 的消费顺序。
     *
     * @return 当前 run 的稳定顺序键。
     */
    public String orderingKey() {
        return (tenantId == null ? "" : tenantId) + ":"
                + (userId == null ? "" : userId) + ":"
                + (sessionId == null ? "" : sessionId) + ":"
                + (runId == null ? "" : runId);
    }
}
