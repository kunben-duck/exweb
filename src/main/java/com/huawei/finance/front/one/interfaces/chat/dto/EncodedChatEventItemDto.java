package com.huawei.finance.front.one.interfaces.chat.dto;

/**
 * turn stream 内承载的真实聊天事件。
 *
 * <p>外层 {@code conversation-turn-stream} 只表达传输与恢复语义；前端真正需要渲染的
 * ChatService 标准事件放在该 encoded item 中。首版只支持 JSON 结构化编码，不引入外部协议的
 * JSON Patch 协议。</p>
 *
 * @param encoding 事件编码版本，当前固定为 {@code chat-event-json-v1}。
 * @param event ChatService 标准事件类型，例如 {@code message.delta}。
 * @param data 标准聊天事件 DTO。
 */
public record EncodedChatEventItemDto(
        String encoding,
        String event,
        ChatEventDto data
) {
}
