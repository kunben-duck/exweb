/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单条 assistant 消息完成事件。
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param sequence 事件持久化后的恢复游标序号，由数据库事实源生成。
 * @param createdAt 事件创建时间。
 * @param payload 完成事件载荷，包含 message 状态以及下游执行诊断字段。
 */
public record MessageCompletedEvent(
        String runId,
        String sessionId,
        long sequence,
        Instant createdAt,
        Map<String, Object> payload
) implements ChatEvent {
    public static MessageCompletedEvent of(String runId, String sessionId) {
        return new MessageCompletedEvent(runId, sessionId, 0, Instant.now(), Map.of("status", "MESSAGE_COMPLETED"));
    }

    public static MessageCompletedEvent of(String runId, String sessionId, Map<String, Object> payload) {
        Map<String, Object> nextPayload = new LinkedHashMap<>();
        nextPayload.put("status", "MESSAGE_COMPLETED");
        if (payload != null) {
            nextPayload.putAll(payload);
        }
        return new MessageCompletedEvent(runId, sessionId, 0, Instant.now(), Map.copyOf(nextPayload));
    }

    @Override public String type() { return "message.completed"; }
}
