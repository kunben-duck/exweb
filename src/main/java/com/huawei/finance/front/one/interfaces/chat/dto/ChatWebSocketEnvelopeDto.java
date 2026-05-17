package com.huawei.finance.front.one.interfaces.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * WebSocket 输出 envelope。
 *
 * <p>WebSocket 外层只表达连接控制、topic 和 offset；真正的聊天事件放在 {@code payload}
 * 中，仍复用稳定的 {@link ChatEventDto}。</p>
 *
 * @param id 客户端命令 ID，reply/error 会原样返回；服务端主动 message 可为空。
 * @param type envelope 类型：reply、message、error。
 * @param topicId run 级 stream topic。
 * @param offset 当前事件的 openGauss seq 字符串。
 * @param payload 聊天事件 DTO，仅 message 使用。
 * @param reply 控制命令响应，仅 reply 使用。
 * @param code 错误码，仅 error 使用。
 * @param message 错误说明，仅 error 使用。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatWebSocketEnvelopeDto(
        String id,
        String type,
        String topicId,
        String offset,
        ChatEventDto payload,
        Map<String, Object> reply,
        String code,
        String message
) {
    public static ChatWebSocketEnvelopeDto reply(String id, Map<String, Object> reply) {
        return new ChatWebSocketEnvelopeDto(id, "reply", null, null, null, reply, null, null);
    }

    public static ChatWebSocketEnvelopeDto message(String topicId, ChatEventDto payload) {
        String offset = payload == null ? null : String.valueOf(payload.sequence());
        return new ChatWebSocketEnvelopeDto(null, "message", topicId, offset, payload, null, null, null);
    }

    public static ChatWebSocketEnvelopeDto error(String id, String code, String message) {
        return new ChatWebSocketEnvelopeDto(id, "error", null, null, null, null, code, message);
    }

    public static ChatWebSocketEnvelopeDto recoverRequired(String topicId, long afterSeq, long actualSeq) {
        return new ChatWebSocketEnvelopeDto(null, "error", topicId, String.valueOf(actualSeq), null, null,
                "RECOVER_REQUIRED", "检测到实时事件乱序，请使用 SSE resume 从 afterSeq=" + afterSeq + " 补齐");
    }
}
