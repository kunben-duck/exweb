package com.huawei.finance.front.one.interfaces.chat.dto;

import java.util.Map;

/**
 * 前端聊天事件 DTO。
 *
 * @param runId 本轮执行追踪标识。
 * @param sessionId 前端聊天会话标识。
 * @param sequence 事件在本轮 run 内的序号。
 * @param type 事件类型，例如 run.started、message.delta。
 * @param messageType 前端展示消息类型。
 * @param payload 事件载荷。
 */
public record FrontChatEventDto(
        String runId,
        String sessionId,
        long sequence,
        String type,
        String messageType,
        Map<String, Object> payload
) {}
