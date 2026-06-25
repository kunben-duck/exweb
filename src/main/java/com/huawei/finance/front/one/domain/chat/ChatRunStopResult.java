package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;

/**
 * stop 接口返回给前端的标准结果。
 *
 * @param runId 被停止或查询的 run 标识。
 * @param sessionId run 所属聊天会话标识。
 * @param status stop 后的 run 状态。
 * @param latestSeq 当前 run 最后一个已持久化事件序号。
 * @param stoppedAt run 进入终态的时间；未终态时为当前响应时间。
 * @param messageReady 是否已有可反馈的 assistant 消息。
 * @param assistantMessageId stop 后可见的 assistant 消息 ID；没有可保存内容时为空。
 * @param feedbackTargetMessageId 前端点赞/点踩应使用的消息 ID；当前等同 assistantMessageId。
 */
public record ChatRunStopResult(
        String runId,
        String sessionId,
        ChatRunStatus status,
        long latestSeq,
        Instant stoppedAt,
        boolean messageReady,
        String assistantMessageId,
        String feedbackTargetMessageId
) {
    public ChatRunStopResult(String runId, String sessionId, ChatRunStatus status,
                             long latestSeq, Instant stoppedAt) {
        this(runId, sessionId, status, latestSeq, stoppedAt, false, null, null);
    }
}
