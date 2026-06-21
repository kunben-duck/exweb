package com.huawei.finance.front.one.interfaces.chat.dto;

/**
 * Conversation turn stream 传输层 DTO。
 *
 * <p>该 DTO 是 WebSocket message 与 Event Resume SSE 的统一 data 结构。它把传输层
 * {@code stream-item/heartbeat/done} 与内部稳定的 {@link ChatEventDto} 解耦，便于未来扩展
 * turn 级状态而不污染 ChatService 事件类型。</p>
 *
 * @param type 固定为 {@code conversation-turn-stream}。
 * @param payload turn stream 片段。
 */
public record ConversationTurnStreamDto(
        String type,
        ConversationTurnStreamPayloadDto payload
) {
}
