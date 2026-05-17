package com.huawei.finance.front.one.interfaces.chat.dto;

/**
 * 会话事件流状态。
 *
 * @param sessionId 前端聊天会话标识。
 * @param latestSeq 当前已持久化的最大事件序号。
 * @param activeRunId 当前仍在运行或取消中的 run 标识。
 * @param activeRunStatus 当前 active run 状态。
 * @param activeStreamTopicId 当前 active run 对应的 WebSocket topic。
 * @param cancellable 当前 active run 是否可取消。
 */
public record FrontStreamStatusDto(
        String sessionId,
        long latestSeq,
        String activeRunId,
        String activeRunStatus,
        String activeStreamTopicId,
        boolean cancellable
) {}
