/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

import java.time.Instant;
import java.util.Map;

/**
 * 单轮 SuperAgent 执行完成事件。
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param sequence 事件持久化后的恢复游标序号，由数据库事实源生成。
 * @param createdAt 事件创建时间。
 * @param payload 完成事件载荷，包含路由、binding 等诊断字段。
 */
public record RunCompletedEvent(
        String runId,
        String sessionId,
        long sequence,
        Instant createdAt,
        Map<String, Object> payload
) implements ChatEvent {
    public static RunCompletedEvent of(String runId, String sessionId) {
        return new RunCompletedEvent(runId, sessionId, 0, Instant.now(), Map.of("status", "COMPLETED"));
    }

    public static RunCompletedEvent of(String runId, String sessionId, Map<String, Object> payload) {
        return new RunCompletedEvent(runId, sessionId, 0, Instant.now(), payload == null ? Map.of("status", "COMPLETED") : Map.copyOf(payload));
    }

    @Override public String type() { return "run.completed"; }
}
