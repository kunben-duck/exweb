package com.huawei.it.ex.one.share.interfaces.dto;

import java.time.Instant;
import java.util.Map;

/** Historical part contained in a fixed share snapshot. */
public record ChatSharePartDto(
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
}
