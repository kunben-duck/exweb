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
 * @param content 下游返回的原始文本片段。
 * @param receivedAt 接收到该片段的时间。
 */
public record RuntimeRawStreamChunk(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String runtimeProvider,
        String apiAdapter,
        String content,
        Instant receivedAt
) {
}
