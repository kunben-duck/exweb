package com.huawei.finance.front.one.interfaces.chat.dto;

import java.time.Instant;

/**
 * 前端 stop 接口返回 DTO。
 *
 * @param runId 被停止或查询的 run 标识。
 * @param sessionId run 所属聊天会话标识。
 * @param status stop 后的 run 状态。
 * @param latestSeq 当前 run 最后一个已持久化事件序号。
 * @param stoppedAt run 进入终态的时间；未终态时为当前响应时间。
 */
public record ChatRunStopDto(
        String runId,
        String sessionId,
        String status,
        long latestSeq,
        Instant stoppedAt
) {}
