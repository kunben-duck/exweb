package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.service.routing.RouteSignalProgress;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;
import com.huawei.it.ex.one.domain.chat.MessageCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds Interaction and route progress events without changing their wire payloads. */
final class InteractionEventFactory {
    Flux<ChatEvent> intentClarificationWaitingBody(String runId, String sessionId,
                                                   Map<String, Object> requestPayload) {
        return Flux.just(
                intentClarificationRequestEvent(runId, sessionId, requestPayload),
                MessageCompletedEvent.of(runId, sessionId));
    }

    RuntimeEvent intentClarificationRequestEvent(String runId, String sessionId,
                                                 Map<String, Object> requestPayload) {
        Map<String, Object> payload = new LinkedHashMap<>(mapOrEmpty(requestPayload));
        payload.put("source", "intent-agent");
        payload.put("sourceType", "intent-clarification-request");
        payload.put("interactionType", ChatInteractionType.INTENT_CLARIFICATION.name());
        return RuntimeEvent.card(runId, sessionId, Map.copyOf(payload));
    }

    RuntimeEvent routeProgressEvent(String runId, String sessionId, RouteSignalProgress progress) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "route-progress");
        payload.put("stage", progress == null
                ? "route_progress"
                : blankToDefault(progress.stage(), "route_progress"));
        payload.put("message", progress == null
                ? "正在选择合适能力"
                : blankToDefault(progress.message(), "正在选择合适能力"));
        if (progress != null && progress.attributes() != null) {
            progress.attributes().forEach((key, value) -> {
                if (key != null && value != null && !payload.containsKey(key)) {
                    payload.put(key, value);
                }
            });
        }
        return RuntimeEvent.progress(runId, sessionId, Map.copyOf(payload));
    }

    RuntimeEvent clarificationResponseEvent(String runId, String sessionId,
                                            ChatInteractionRequest interaction,
                                            Map<String, Object> responsePayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", interaction.interactionType() == ChatInteractionType.INTENT_CLARIFICATION
                ? "intent-clarification-response"
                : "clarification-response");
        payload.put("interactionId", interaction.id());
        payload.put("interactionType", interaction.interactionType().name());
        putIfNotNull(payload, "approval_id", interaction.approvalId());
        putIfNotNull(payload, "approved", responsePayload.get("approved"));
        putIfNotNull(payload, "scope", responsePayload.get("scope"));
        payload.put("questionnaireAnswers", mapOrEmpty(responsePayload.get("questionnaireAnswers")));
        putIfNotNull(payload, "answerText", responsePayload.get("answerText"));
        payload.put("metadata", mapOrEmpty(responsePayload.get("metadata")));
        return RuntimeEvent.card(runId, sessionId, Map.copyOf(payload));
    }

    RuntimeEvent routeSwitchResponseEvent(String runId, String sessionId,
                                          ChatInteractionRequest interaction,
                                          Map<String, Object> responsePayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "route-switch-confirmation-response");
        payload.put("interactionId", interaction.id());
        payload.put("interactionType", interaction.interactionType().name());
        putIfNotNull(payload, "approved", responsePayload.get("approved"));
        putIfNotNull(payload, "currentProvider", interaction.requestPayload().get("currentProvider"));
        putIfNotNull(payload, "currentTargetId", interaction.requestPayload().get("currentTargetId"));
        putIfNotNull(payload, "currentRouteSource", interaction.requestPayload().get("currentRouteSource"));
        putIfNotNull(payload, "candidateProvider", interaction.requestPayload().get("candidateProvider"));
        putIfNotNull(payload, "candidateTargetId", interaction.requestPayload().get("candidateTargetId"));
        putIfNotNull(payload, "candidateIntentName", interaction.requestPayload().get("candidateIntentName"));
        payload.put("metadata", mapOrEmpty(responsePayload.get("metadata")));
        return RuntimeEvent.card(runId, sessionId, Map.copyOf(payload));
    }

    RuntimeEvent routeSwitchDeclinedEvent(String runId, String sessionId,
                                          ChatInteractionRequest interaction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "route-switch-declined");
        payload.put("interactionId", interaction.id());
        payload.put("message", "已保留当前领域 Agent，本轮不切换处理能力。");
        putIfNotNull(payload, "currentProvider", interaction.requestPayload().get("currentProvider"));
        putIfNotNull(payload, "currentTargetId", interaction.requestPayload().get("currentTargetId"));
        putIfNotNull(payload, "candidateProvider", interaction.requestPayload().get("candidateProvider"));
        putIfNotNull(payload, "candidateTargetId", interaction.requestPayload().get("candidateTargetId"));
        putIfNotNull(payload, "refusalCode", interaction.requestPayload().get("refusalCode"));
        putIfNotNull(payload, "refusalReason", interaction.requestPayload().get("refusalReason"));
        return RuntimeEvent.card(runId, sessionId, Map.copyOf(payload));
    }

    RuntimeEvent routeSwitchAppliedEvent(String runId, String sessionId,
                                         ChatInteractionRequest interaction,
                                         RouteTarget route,
                                         RuntimeBinding binding) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "route-switch-applied");
        payload.put("metadataType", "route_switch_applied");
        payload.put("interactionId", interaction.id());
        payload.put("targetProvider", binding == null ? null : binding.provider());
        putIfNotNull(payload, "targetId", firstText(
                route == null ? null : route.selectedAgentCode(),
                interaction.requestPayload().get("candidateTargetId")));
        putIfNotNull(payload, "routeSource", route == null ? null : route.routeSource());
        return RuntimeEvent.metadata(runId, sessionId, ChatPayloadMaps.immutableCopy(payload));
    }

    private void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private Map<String, Object> mapOrEmpty(Object value) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return Map.copyOf(copy);
        }
        return Map.of();
    }

    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
