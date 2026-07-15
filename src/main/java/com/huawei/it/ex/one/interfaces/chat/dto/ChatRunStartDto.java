package com.huawei.it.ex.one.interfaces.chat.dto;

import java.time.Instant;

/**
 * 前端创建异步聊天 run 后收到的创建结果。
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param firstSeq run.started 事件的持久化序号。
 * @param createdAt run.started 创建时间。
 * @param streamTopicId 本轮回答的 WebSocket run 级订阅 topic；连接地址由前端 SDK 或网关配置管理。
 */
public record ChatRunStartDto(
        String runId,
        String sessionId,
        long firstSeq,
        Instant createdAt,
        String streamTopicId
) {}
