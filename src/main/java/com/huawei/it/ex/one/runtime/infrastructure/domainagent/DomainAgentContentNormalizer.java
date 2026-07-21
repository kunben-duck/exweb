package com.huawei.it.ex.one.runtime.infrastructure.domainagent;

import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.common.event.MessageDeltaEvent;
import com.huawei.it.ex.one.common.event.RuntimeEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DomainAgentContentNormalizer {
    List<ChatEvent> normalize(String runId, String sessionId, String content,
                              DomainAgentResponseNormalizer.DomainAgentStreamState state) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        DomainAgentResponseNormalizer.DomainAgentStreamState streamState = state == null
                ? new DomainAgentResponseNormalizer.DomainAgentStreamState()
                : state;
        List<ChatEvent> events = new ArrayList<>();
        String input = streamState.pending + content;
        streamState.pending = "";
        while (!input.isEmpty()) {
            if (streamState.inThinking) {
                int end = indexOfIgnoreCase(input, "</think>");
                if (end >= 0) {
                    addThinkingText(runId, sessionId, events, input.substring(0, end));
                    events.add(thinkingBoundary(runId, sessionId, "COMPLETED", null));
                    streamState.inThinking = false;
                    input = input.substring(end + "</think>".length());
                    continue;
                }
                int keep = partialSuffixLength(input, "</think>");
                addThinkingText(runId, sessionId, events, input.substring(0, input.length() - keep));
                streamState.pending = input.substring(input.length() - keep);
                break;
            }
            int start = indexOfIgnoreCase(input, "<think>");
            if (start >= 0) {
                addAnswerDelta(runId, sessionId, events, input.substring(0, start));
                events.add(thinkingBoundary(runId, sessionId, "STARTED", null));
                streamState.inThinking = true;
                input = input.substring(start + "<think>".length());
                continue;
            }
            int keep = partialSuffixLength(input, "<think>");
            addAnswerDelta(runId, sessionId, events, input.substring(0, input.length() - keep));
            streamState.pending = input.substring(input.length() - keep);
            break;
        }
        return events;
    }

    List<ChatEvent> flush(String runId, String sessionId,
                          DomainAgentResponseNormalizer.DomainAgentStreamState state) {
        if (state == null) {
            return List.of();
        }
        List<ChatEvent> events = new ArrayList<>();
        if (!state.pending.isEmpty()) {
            if (state.inThinking) {
                addThinkingText(runId, sessionId, events, state.pending);
            } else {
                addAnswerDelta(runId, sessionId, events, state.pending);
            }
            state.pending = "";
        }
        if (state.inThinking) {
            events.add(thinkingBoundary(runId, sessionId, "COMPLETED", null));
            state.inThinking = false;
        }
        return events;
    }

    private void addAnswerDelta(String runId, String sessionId, List<ChatEvent> events, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        events.add(new MessageDeltaEvent(runId, sessionId, 0, Instant.now(), delta, Map.of(
                "delta", delta,
                "sourceType", "domain-agent-content"
        )));
    }

    private void addThinkingText(String runId, String sessionId, List<ChatEvent> events, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        events.add(RuntimeEvent.thinking(runId, sessionId, Map.of(
                "source", "domain-agent",
                "sourceType", "content.think",
                "status", "STREAMING",
                "text", text
        )));
    }

    private RuntimeEvent thinkingBoundary(String runId, String sessionId, String status, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", "content.think");
        payload.put("status", status);
        if (text != null && !text.isBlank()) {
            payload.put("text", text);
        }
        return RuntimeEvent.thinking(runId, sessionId, payload);
    }

    private int indexOfIgnoreCase(String value, String target) {
        return value.toLowerCase(Locale.ROOT).indexOf(target.toLowerCase(Locale.ROOT));
    }

    private int partialSuffixLength(String value, String target) {
        String lowerValue = value.toLowerCase(Locale.ROOT);
        String lowerTarget = target.toLowerCase(Locale.ROOT);
        int max = Math.min(lowerValue.length(), lowerTarget.length() - 1);
        for (int length = max; length > 0; length--) {
            if (lowerValue.endsWith(lowerTarget.substring(0, length))) {
                return length;
            }
        }
        return 0;
    }
}
