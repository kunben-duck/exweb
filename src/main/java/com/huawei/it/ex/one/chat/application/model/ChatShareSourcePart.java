package com.huawei.it.ex.one.chat.application.model;

import com.huawei.it.ex.one.common.event.ChatPayloadMaps;
import java.time.Instant;
import java.util.Map;

/** Immutable message-part projection exposed to the share context. */
public record ChatShareSourcePart(
        String partId,
        String messageId,
        String runId,
        String partType,
        String sourceType,
        String contentText,
        String title,
        String status,
        String channel,
        String displayHint,
        Boolean visible,
        Map<String, Object> payload,
        Integer partOrder,
        Instant createdAt
) {
    public ChatShareSourcePart {
        payload = ChatPayloadMaps.immutableCopy(payload);
    }
}
