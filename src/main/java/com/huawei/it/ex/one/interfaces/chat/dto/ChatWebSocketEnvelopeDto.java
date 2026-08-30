/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * WebSocket 输出 envelope。
 *
 * <p>WebSocket 外层只表达连接控制、topic 和 offset；本轮回答的传输结构放在 {@code payload}
 * 中。真实 ChatService 标准事件位于 {@code payload.payload.encodedItem.data}，heartbeat/done 则只表达
 * turn stream 连接状态，不对应持久化事件。</p>
 *
 * @param id 客户端命令 ID，reply/error 会原样返回；服务端主动 message 可为空。
 * @param type envelope 类型：reply、message、error。
 * @param topicId run 级 stream topic。
 * @param offset 当前 stream-item 的数据库 seq 字符串；heartbeat/done 不推进 offset。
 * @param payload conversation turn stream 片段，仅 message 使用。
 * @param reply 控制命令响应，仅 reply 使用。
 * @param code 错误码，仅 error 使用。
 * @param message 错误说明，仅 error 使用。
 * @param details 可选诊断信息；旧前端可忽略。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatWebSocketEnvelopeDto(
        String id,
        String type,
        String topicId,
        String offset,
        ConversationTurnStreamDto payload,
        Map<String, Object> reply,
        String code,
        String message,
        Map<String, Object> details
) {
    public static ChatWebSocketEnvelopeDto reply(String id, Map<String, Object> reply) {
        return new ChatWebSocketEnvelopeDto(id, "reply", null, null, null, reply, null, null, null);
    }

    public static ChatWebSocketEnvelopeDto message(String topicId, ConversationTurnStreamDto payload, String offset) {
        return new ChatWebSocketEnvelopeDto(null, "message", topicId, offset, payload, null, null, null, null);
    }

    public static ChatWebSocketEnvelopeDto error(String id, String code, String message) {
        return new ChatWebSocketEnvelopeDto(id, "error", null, null, null, null, code, message, null);
    }

    public static ChatWebSocketEnvelopeDto recoverRequired(String topicId, long afterSeq, long actualSeq) {
        return recoverRequired(topicId, afterSeq, actualSeq, Map.of(
                "reason", "UNKNOWN",
                "topicId", topicId,
                "recoveryAfterSeq", afterSeq,
                "actualSeq", actualSeq
        ));
    }

    public static ChatWebSocketEnvelopeDto recoverRequired(String topicId, long afterSeq, long actualSeq,
                                                           Map<String, Object> details) {
        return new ChatWebSocketEnvelopeDto(null, "error", topicId, String.valueOf(actualSeq), null, null,
                "RECOVER_REQUIRED", "实时事件需要恢复，请使用 Event Resume 从 afterSeq=" + afterSeq + " 补齐",
                details);
    }
}
