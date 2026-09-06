/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventStore;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.CandidateDomainAgentSwitchCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从source Run提取有界路由过程，并追加本次候选技能切换标识。 */
@Service
final class CandidateSwitchRouteTraceService {
    static final int SOURCE_SCAN_LIMIT = 256;
    static final int MAX_TRACE_EVENTS = 32;
    static final long MAX_TRACE_BYTES = 256L * 1024L;

    private static final AppLogger log = AppLoggerFactory.getLogger(CandidateSwitchRouteTraceService.class);
    private static final Set<String> INTENT_SOURCE_TYPES = Set.of(
            "intent-start",
            "intent-progress",
            "intent-delta",
            "intent-result");
    private static final Set<String> CHAT_SERVICE_SOURCE_TYPES = Set.of(
            "selectedIntentExpert",
            "selectedDomainAgent",
            "selectedDomainExpert",
            "candidate-skill-switch");

    private final ChatEventStore eventStore;
    private final ObjectMapper objectMapper;

    CandidateSwitchRouteTraceService(ChatEventStore eventStore, ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    CandidateSwitchRouteTrace load(
            UserContext user,
            ChatRun sourceRun,
            CandidateDomainAgentSwitchCommand command) {
        CandidateSwitchRouteTrace.Entry marker = switchMarker(sourceRun, command);
        List<ChatEvent> sourceEvents;
        try {
            long afterSeq = sourceRun.firstSeq() == null || sourceRun.firstSeq() <= 0
                    ? 0L
                    : sourceRun.firstSeq() - 1L;
            sourceEvents = eventStore.findFirstByOwnerAndRunAfterSeq(
                    user.tenantId(),
                    user.ownerUserId(),
                    sourceRun.sessionId(),
                    sourceRun.id(),
                    new ChatEventStore.RunEventWindow(afterSeq, SOURCE_SCAN_LIMIT));
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "Candidate switch route trace could not be loaded; continuing without inherited events")
                    .runId(sourceRun.id())
                    .sessionId(sourceRun.sessionId())
                    .operation("candidate-switch.route-trace-load")
                    .build(), ex);
            return new CandidateSwitchRouteTrace(List.of(marker));
        }
        return boundedTrace(sourceEvents, marker);
    }

    private CandidateSwitchRouteTrace boundedTrace(
            List<ChatEvent> sourceEvents,
            CandidateSwitchRouteTrace.Entry marker) {
        LinkedHashMap<Origin, CandidateSwitchRouteTrace.Entry> unique = new LinkedHashMap<>();
        if (sourceEvents != null) {
            for (ChatEvent event : sourceEvents) {
                if (!replayable(event)) {
                    continue;
                }
                Origin origin = origin(event);
                if (origin == null) {
                    continue;
                }
                unique.putIfAbsent(origin, replayEntry(event, origin));
            }
        }

        long remainingBytes = Math.max(0L, MAX_TRACE_BYTES - serializedBytes(marker));
        int remainingEvents = Math.max(0, MAX_TRACE_EVENTS - 1);
        List<CandidateSwitchRouteTrace.Entry> candidates = new ArrayList<>(unique.values());
        List<CandidateSwitchRouteTrace.Entry> selectedReversed = new ArrayList<>();
        for (int index = candidates.size() - 1;
             index >= 0 && selectedReversed.size() < remainingEvents;
             index--) {
            CandidateSwitchRouteTrace.Entry candidate = candidates.get(index);
            long candidateBytes = serializedBytes(candidate);
            if (candidateBytes <= remainingBytes) {
                selectedReversed.add(candidate);
                remainingBytes -= candidateBytes;
            }
        }
        Collections.reverse(selectedReversed);
        selectedReversed.add(marker);
        return new CandidateSwitchRouteTrace(selectedReversed);
    }

    private boolean replayable(ChatEvent event) {
        if (event == null || event.type() == null || !event.type().startsWith("runtime.")
                || event.payload() == null) {
            return false;
        }
        String source = text(event.payload().get("source"));
        String sourceType = text(event.payload().get("sourceType"));
        return "intent-agent".equals(source) && INTENT_SOURCE_TYPES.contains(sourceType)
                || "chatservice".equals(source) && CHAT_SERVICE_SOURCE_TYPES.contains(sourceType);
    }

    private CandidateSwitchRouteTrace.Entry replayEntry(ChatEvent event, Origin origin) {
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        payload.remove("runtimeBindingId");
        payload.remove("runtimeSessionId");
        payload.put(CandidateSwitchRouteTrace.REPLAY_METADATA_KEY, Map.of(
                "originRunId", origin.runId(),
                "originSequence", origin.sequence()));
        return new CandidateSwitchRouteTrace.Entry(event.type(), payload);
    }

    private Origin origin(ChatEvent event) {
        Object replay = event.payload().get(CandidateSwitchRouteTrace.REPLAY_METADATA_KEY);
        if (replay instanceof Map<?, ?> replayMap) {
            String runId = text(replayMap.get("originRunId"));
            Long sequence = positiveLong(replayMap.get("originSequence"));
            if (runId != null && sequence != null) {
                return new Origin(runId, sequence);
            }
        }
        return event.runId() == null || event.runId().isBlank() || event.sequence() <= 0
                ? null
                : new Origin(event.runId(), event.sequence());
    }

    private CandidateSwitchRouteTrace.Entry switchMarker(
            ChatRun sourceRun,
            CandidateDomainAgentSwitchCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "candidate-skill-switch");
        payload.put("stage", "candidate_skill_switch");
        payload.put("status", "COMPLETED");
        payload.put("message", "已选择候选技能重新调用");
        payload.put("sourceRunId", sourceRun.id());
        payload.put("targetType", "DOMAIN_AGENT");
        payload.put("targetId", command.skillId());
        payload.put("skillId", command.skillId());
        putIfPresent(payload, "intentId", SelectedIntentContext.intentId(command.metadata()));
        putIfPresent(payload, "intentName", SelectedIntentContext.intentName(command.metadata()));
        return new CandidateSwitchRouteTrace.Entry("runtime.progress", payload);
    }

    private long serializedBytes(CandidateSwitchRouteTrace.Entry entry) {
        Map<String, Object> wireEvent = new LinkedHashMap<>();
        wireEvent.put("runId", "r".repeat(64));
        wireEvent.put("sessionId", "s".repeat(64));
        wireEvent.put("sequence", Long.MAX_VALUE);
        wireEvent.put("type", entry.eventType());
        wireEvent.put("createdAt", Instant.MAX.toString());
        wireEvent.put("payload", entry.payload());
        try {
            return objectMapper.writeValueAsBytes(wireEvent).length;
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return Long.MAX_VALUE;
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Long positiveLong(Object value) {
        if (value instanceof Number number) {
            long normalized = number.longValue();
            return normalized > 0 ? normalized : null;
        }
        try {
            long normalized = value == null ? 0L : Long.parseLong(String.valueOf(value));
            return normalized > 0 ? normalized : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private record Origin(String runId, long sequence) {
    }
}
