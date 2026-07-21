package com.huawei.it.ex.one.share.domain;

import com.huawei.it.ex.one.common.event.ChatPayloadMaps;
import java.time.Instant;
import java.util.Map;

/**
 * 分享快照中的 assistant 结构化过程信息。
 */
public record ChatShareSnapshotPart(
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
    public ChatShareSnapshotPart {
        payload = ChatPayloadMaps.immutableCopy(payload);
    }
}
