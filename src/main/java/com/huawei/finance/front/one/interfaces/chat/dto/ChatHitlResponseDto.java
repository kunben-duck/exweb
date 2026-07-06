package com.huawei.finance.front.one.interfaces.chat.dto;

/**
 * 提交 HITL 响应后返回的续接 run 订阅信息。
 *
 * @param hitlRequestId 被响应的 HITL 请求 ID。
 * @param continueRunId 后台续接 run ID。
 * @param sessionId 会话 ID。
 * @param assistantMessageId 续接复用的 assistant 消息 ID。
 * @param streamTopicId 续接 run 的 WebSocket topic。
 * @param firstSeq 续接 run 的首个事件 seq。
 * @param status HITL 当前状态。
 */
public record ChatHitlResponseDto(
        String hitlRequestId,
        String continueRunId,
        String sessionId,
        String assistantMessageId,
        String streamTopicId,
        long firstSeq,
        String status
) {
}
