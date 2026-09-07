/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat;

import com.huawei.it.ex.one.application.service.routing.IntentFeedbackHistoryService;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.intent.IntentFeedback;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatMessageDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatMessagePartDto;
import com.huawei.it.ex.one.interfaces.chat.dto.IntentFeedbackDto;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-time overlay only: original Event/Part payloads and cached messages remain untouched. */
@Component
public class IntentFeedbackViewAssembler {
    private static final AppLogger log = AppLoggerFactory.getLogger(IntentFeedbackViewAssembler.class);
    private static final Set<String> SKILL_NODES = Set.of("selectedDomainAgent", "selectedDomainExpert", "intent-result");
    private final IntentFeedbackHistoryService history;

    public IntentFeedbackViewAssembler(IntentFeedbackHistoryService history) {
        this.history = history;
    }

    public List<ChatMessageDto> enrich(UserContext user, String sessionId, List<ChatMessageDto> messages) {
        Set<String> ids = new LinkedHashSet<>();
        for (ChatMessageDto message : messages) {
            if (!eligible(message)) {
                continue;
            }
            add(ids, message.runId());
            for (ChatMessagePartDto part : message.parts()) {
                if (node(part) || marker(part)) {
                    add(ids, origin(message, part));
                }
            }
        }
        if (ids.isEmpty()) {
            return messages;
        }
        Map<String, IntentFeedback> feedbacks;
        try {
            feedbacks = history.find(user, sessionId, ids);
        } catch (RuntimeException failure) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_READ_FAILED,
                            "Intent feedback history lookup failed; returning history without feedback")
                    .operation("intent-feedback.history").attribute("runCount", ids.size()).build(), failure);
            return messages;
        }
        return messages.stream().map(message -> enrichMessage(message, feedbacks)).toList();
    }

    private ChatMessageDto enrichMessage(ChatMessageDto message, Map<String, IntentFeedback> feedbacks) {
        if (!eligible(message)) {
            return message;
        }
        Map<String, Integer> nodes = new LinkedHashMap<>();
        Map<String, Integer> markers = new LinkedHashMap<>();
        List<ChatMessagePartDto> parts = message.parts();
        for (int i = 0; i < parts.size(); i++) {
            ChatMessagePartDto part = parts.get(i);
            String origin = origin(message, part);
            if (origin != null && node(part)) {
                nodes.put(origin, i);
            } else if (origin != null && marker(part)) {
                markers.put(origin, i);
            }
        }
        markers.forEach(nodes::putIfAbsent);
        List<ChatMessagePartDto> result = new ArrayList<>(parts);
        nodes.forEach((runId, index) -> {
            IntentFeedback feedback = feedbacks.get(runId);
            if (feedback != null) {
                ChatMessagePartDto part = parts.get(index);
                Map<String, Object> payload = new LinkedHashMap<>(part.payload());
                payload.put("intentFeedback", IntentFeedbackDto.from(feedback));
                result.set(index, new ChatMessagePartDto(part.partId(), part.messageId(), part.runId(),
                        part.partType(), part.sourceType(), part.contentText(), part.title(), part.status(),
                        part.channel(), part.displayHint(), part.visible(), payload, part.partOrder(), part.createdAt()));
            }
        });
        return message.withIntentFeedback(IntentFeedbackDto.from(feedbacks.get(message.runId())), List.copyOf(result));
    }

    private boolean eligible(ChatMessageDto message) {
        return "assistant".equals(message.role()) && !message.locked()
                && !"BRANCH_SNAPSHOT".equals(message.originType());
    }

    private boolean node(ChatMessagePartDto part) {
        return part.sourceType() != null && SKILL_NODES.contains(part.sourceType());
    }

    private boolean marker(ChatMessagePartDto part) {
        return "candidate-skill-switch".equals(part.sourceType());
    }

    private String origin(ChatMessageDto message, ChatMessagePartDto part) {
        Map<String, Object> payload = part.payload();
        if (payload != null) {
            // A switch marker evaluates its source, not the run that emitted the marker.
            if (marker(part) && text(payload.get("sourceRunId")) != null) {
                return text(payload.get("sourceRunId"));
            }
            Object replay = payload.get("candidateSwitchReplay");
            if (replay instanceof Map<?, ?> map) {
                return text(map.get("originRunId"));
            }
        }
        return part.runId() == null ? message.runId() : part.runId();
    }

    private void add(Set<String> ids, String value) {
        if (value != null && !value.isBlank()) {
            ids.add(value);
        }
    }

    private String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
