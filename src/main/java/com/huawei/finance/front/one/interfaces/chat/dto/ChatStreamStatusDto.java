package com.huawei.finance.front.one.interfaces.chat.dto;

/**
 * 会话事件流状态。
 *
 * @param sessionId 前端聊天会话标识。
 * @param latestSeq 当前已持久化的最大事件序号。
 * @param activeRunId 当前仍在运行或取消中的 run 标识。
 * @param activeRunStatus 当前 active run 状态。
 * @param activeStreamTopicId 当前 active run 对应的 WebSocket topic。
 * @param activeRunFirstSeq 当前 active run 的首个事件序号；无 active run 或尚未落 run.started 时为空。
 * @param activeRunLastSeq 当前 active run 最近一个已持久化事件序号；无 active run 或尚无事件时为空。
 * @param cancellable 当前 active run 是否可取消。
 * @param waitingUserInput 当前会话是否存在等待用户输入请求。
 * @param hitlRequestId 当前等待请求 ID。
 * @param waitingType 等待类型。
 * @param assistantMessageId 承载等待卡片的 assistant 消息 ID。
 * @param expiresAt 等待请求过期时间。
 */
public record ChatStreamStatusDto(
        String sessionId,
        long latestSeq,
        String activeRunId,
        String activeRunStatus,
        String activeStreamTopicId,
        Long activeRunFirstSeq,
        Long activeRunLastSeq,
        boolean cancellable,
        boolean waitingUserInput,
        String hitlRequestId,
        String waitingType,
        String assistantMessageId,
        java.time.Instant expiresAt
) {}
