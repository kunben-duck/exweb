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
 */
public record ChatStreamStatusDto(
        String sessionId,
        long latestSeq,
        String activeRunId,
        String activeRunStatus,
        String activeStreamTopicId,
        Long activeRunFirstSeq,
        Long activeRunLastSeq,
        boolean cancellable
) {}
