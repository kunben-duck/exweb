package com.huawei.it.ex.one.runtime.infrastructure.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.common.event.RuntimeEvent;
import java.time.Instant;
import java.util.Set;

/** Maps known Relay source types to the existing ChatEvent categories. */
final class RelayRuntimeEventFactory {
    private static final Set<String> PROGRESS_TYPES = Set.of(
            "relay-start", "relay-end", "relay-progress", "clarified-query", "plan-update",
            "subagent-plan-created", "subagent-subtask", "approval-result", "approval-response");
    private static final Set<String> METADATA_TYPES = Set.of(
            "project-home", "available-modes", "availbale-modes", "session-ready", "session-state",
            "self-evolution-status", "token-update", "heartbeat-response");
    private static final Set<String> THINKING_TYPES = Set.of(
            "agent-reasoning", "thinking-operation-start", "thinkink-operation-start",
            "thinking-content-update", "thinking-operation-end", "thinking-operation-finish");
    private static final Set<String> TOOL_TYPES = Set.of(
            "tool-call-streaming", "tool-execution", "tool-structured-result");
    private static final Set<String> REFERENCE_TYPES = Set.of(
            "url-moderation", "url-moderation-result", "search-result-groups", "content-references",
            "citations", "sources", "references", "safe-urls");

    private final RelayPayloadMapper payloadMapper;

    RelayRuntimeEventFactory(RelayPayloadMapper payloadMapper) {
        this.payloadMapper = payloadMapper;
    }

    ChatEvent mappedRuntimeEvent(String runId, String sessionId, JsonNode root,
                                 String sourceType, String normalizedType) {
        if (PROGRESS_TYPES.contains(normalizedType)) {
            return RuntimeEvent.progress(runId, sessionId, payloadMapper.relayPayload(root, sourceType));
        }
        if (METADATA_TYPES.contains(normalizedType)) {
            return RuntimeEvent.metadata(runId, sessionId, payloadMapper.relayPayload(root, sourceType));
        }
        if ("agent-call".equals(normalizedType)) {
            return RuntimeEvent.agent(runId, sessionId, payloadMapper.relayPayload(root, sourceType));
        }
        if (THINKING_TYPES.contains(normalizedType)) {
            return RuntimeEvent.thinking(runId, sessionId, payloadMapper.relayPayload(root, sourceType));
        }
        if (TOOL_TYPES.contains(normalizedType)) {
            return RuntimeEvent.tool(runId, sessionId, payloadMapper.relayPayload(root, sourceType));
        }
        if ("approval-request".equals(normalizedType)) {
            return RuntimeEvent.card(runId, sessionId, payloadMapper.relayPayload(root, sourceType));
        }
        if (REFERENCE_TYPES.contains(normalizedType)) {
            return RuntimeEvent.reference(runId, sessionId, payloadMapper.relayPayload(root, sourceType));
        }
        return null;
    }

    RuntimeEvent fallbackRuntimeEvent(String runId, String sessionId, JsonNode root, String type) {
        return new RuntimeEvent(runId, sessionId, 0, Instant.now(), "runtime.event",
                payloadMapper.relayPayload(root, type));
    }
}
