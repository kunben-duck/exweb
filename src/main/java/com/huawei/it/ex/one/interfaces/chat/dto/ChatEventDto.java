/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

import java.util.Map;

/**
 * 前端聊天事件 DTO。
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param sequence 数据库全局事件序号；仅已持久化事件可用作 Event Resume afterSeq。
 * @param type 事件类型，例如 run.started、message.delta。
 * @param payload 事件载荷。
 */
public record ChatEventDto(
        String runId,
        String sessionId,
        long sequence,
        String type,
        Map<String, Object> payload
) {}
