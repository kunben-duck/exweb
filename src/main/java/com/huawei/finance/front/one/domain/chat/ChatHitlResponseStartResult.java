package com.huawei.finance.front.one.domain.chat;

/**
 * HITL 响应提交后启动续接 run 的结果。
 *
 * @param hitlRequestId HITL 请求 ID。
 * @param continueRunId 续接 run ID。
 * @param sessionId 会话 ID。
 * @param assistantMessageId 复用的 assistant 消息 ID。
 * @param streamTopicId 续接 run 的 WebSocket topic。
 * @param firstSeq 续接 run.started 事件 seq。
 * @param status HITL 当前状态。
 */
public record ChatHitlResponseStartResult(
        String hitlRequestId,
        String continueRunId,
        String sessionId,
        String assistantMessageId,
        String streamTopicId,
        long firstSeq,
        String status
) {
}
