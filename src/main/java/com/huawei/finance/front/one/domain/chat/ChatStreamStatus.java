package com.huawei.finance.front.one.domain.chat;

/**
 * 前端页面初始化时读取的会话流状态。
 *
 * @param sessionId 前端聊天会话标识。
 * @param latestSeq 当前会话最大事件序号。
 * @param activeRunId 当前仍在运行或取消中的 run 标识。
 * @param activeRunStatus 当前 active run 状态。
 * @param activeStreamTopicId 当前 active run 对应的 WebSocket stream topic；无 active run 时为空。
 * @param cancellable 当前 active run 是否可通过 stop 接口取消。
 */
public record ChatStreamStatus(
        String sessionId,
        long latestSeq,
        String activeRunId,
        ChatRunStatus activeRunStatus,
        String activeStreamTopicId,
        boolean cancellable
) {}
