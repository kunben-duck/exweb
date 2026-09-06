/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 候选技能切换前需要在replacement Run中回放的可信路由事件快照。 */
record CandidateSwitchRouteTrace(List<Entry> entries) {
    static final String REPLAY_METADATA_KEY = "candidateSwitchReplay";

    CandidateSwitchRouteTrace {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    static CandidateSwitchRouteTrace empty() {
        return new CandidateSwitchRouteTrace(List.of());
    }

    List<ChatEvent> eventsFor(String runId, String sessionId) {
        if (entries.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        return entries.stream()
                .map(entry -> (ChatEvent) new RuntimeEvent(
                        runId, sessionId, 0L, now, entry.eventType(), entry.payload()))
                .toList();
    }

    static boolean replayed(Map<String, Object> payload) {
        return payload != null && payload.get(REPLAY_METADATA_KEY) instanceof Map<?, ?>;
    }

    record Entry(String eventType, Map<String, Object> payload) {
        Entry {
            payload = ChatPayloadMaps.immutableCopy(payload);
        }
    }
}
