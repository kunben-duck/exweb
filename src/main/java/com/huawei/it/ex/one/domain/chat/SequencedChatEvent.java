/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

import java.time.Instant;
import java.util.Map;

/**
 * 仅分配全局序号、未写入事件事实表的实时 ChatEvent。
 *
 * <p>该事件只用于本机流和 Redis Pub/Sub 实时传输，不能通过 Event Resume 恢复。</p>
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param sequence 数据库全局序列分配的有序编号。
 * @param eventType 事件类型。
 * @param createdAt 事件创建时间。
 * @param payload 事件载荷。
 */
public record SequencedChatEvent(
        String runId,
        String sessionId,
        long sequence,
        String eventType,
        Instant createdAt,
        Map<String, Object> payload
) implements ChatEvent {
    public SequencedChatEvent {
        payload = ChatPayloadMaps.immutableCopy(payload);
    }

    @Override
    public String type() {
        return eventType;
    }
}
