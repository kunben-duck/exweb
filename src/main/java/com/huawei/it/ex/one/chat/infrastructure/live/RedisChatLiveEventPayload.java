package com.huawei.it.ex.one.chat.infrastructure.live;

import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.StoredChatEvent;
import java.time.Instant;
import java.util.Map;

/** Redis Pub/Sub 中传输的最小事件快照。 */
record RedisChatLiveEventPayload(
        String publisherInstanceId,
        String runId,
        String sessionId,
        long sequence,
        String eventType,
        Instant createdAt,
        Map<String, Object> payload
) {
    static RedisChatLiveEventPayload from(ChatEvent event, String publisherInstanceId) {
        return new RedisChatLiveEventPayload(publisherInstanceId, event.runId(), event.sessionId(),
                event.sequence(), event.type(), event.createdAt(), event.payload());
    }

    ChatEvent toEvent() {
        return new StoredChatEvent(runId, sessionId, sequence, eventType,
                createdAt == null ? Instant.EPOCH : createdAt, payload == null ? Map.of() : payload);
    }
}
