package com.huawei.it.ex.one.runtime.infrastructure.domainagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.common.event.MessageCompletedEvent;
import com.huawei.it.ex.one.common.event.RuntimeEvent;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentControlEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DomainAgentResponseEventMapper {
    private final DomainAgentControlEventMapper controlEventMapper;
    private final DomainAgentContentNormalizer contentNormalizer;
    private final DomainAgentPayloadSanitizer payloadSanitizer;

    DomainAgentResponseEventMapper(DomainAgentControlEventMapper controlEventMapper,
                                   DomainAgentContentNormalizer contentNormalizer,
                                   DomainAgentPayloadSanitizer payloadSanitizer) {
        this.controlEventMapper = controlEventMapper;
        this.contentNormalizer = contentNormalizer;
        this.payloadSanitizer = payloadSanitizer;
    }

    List<ChatEvent> normalize(String runId, String sessionId, JsonNode root,
                              DomainAgentResponseNormalizer.DomainAgentStreamState state) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return List.of();
        }
        if (!root.isObject()) {
            return List.of(RuntimeEvent.fallback(runId, sessionId, new RuntimeEvent.FallbackPayload(
                    "domain-agent", "unknown", "event", "runtime", "debug", null,
                    Map.of("value", payloadSanitizer.truncate(root.asText(""))))));
        }
        List<ChatEvent> events = new ArrayList<>();
        DomainAgentControlEvent controlEvent = controlEventMapper.map(root).orElse(null);
        if (controlEvent != null) {
            if (controlEvent.reroute()) {
                events.addAll(contentNormalizer.flush(runId, sessionId, state));
            }
            events.add(RuntimeEvent.metadata(runId, sessionId, controlEvent.payload()));
            if (controlEvent.reroute()) {
                return List.copyOf(events);
            }
        }
        addMetadataEvents(runId, sessionId, root, events);
        addStateEvent(runId, sessionId, root, events);
        addStructuredEvents(runId, sessionId, root, events);
        String content = text(root, "content");
        if (content != null) {
            events.addAll(contentNormalizer.normalize(runId, sessionId, content, state));
        }
        if (bool(root, "endFlag")) {
            events.addAll(contentNormalizer.flush(runId, sessionId, state));
            events.add(MessageCompletedEvent.of(runId, sessionId, Map.of(
                    "status", "MESSAGE_COMPLETED",
                    "sourceType", "domain-agent-end"
            )));
        }
        if (!events.isEmpty()) {
            return List.copyOf(events);
        }
        RuntimeEvent.FallbackPayload payload = new RuntimeEvent.FallbackPayload(
                "domain-agent", "unknown", "event", "runtime", "debug", null,
                Map.of("sourcePayload", payloadSanitizer.sanitizeDiagnostic(root)));
        return List.of(RuntimeEvent.fallback(runId, sessionId, payload));
    }

    private void addMetadataEvents(String runId, String sessionId, JsonNode root, List<ChatEvent> events) {
        if (root.hasNonNull("traceId")) {
            events.add(RuntimeEvent.metadata(runId, sessionId,
                    metadataPayload("trace", Map.of("traceId", text(root, "traceId")))));
        }
        if (root.hasNonNull("sessionId")) {
            String domainSessionId = text(root, "sessionId");
            events.add(RuntimeEvent.metadata(runId, sessionId, metadataPayload("domain_agent_session",
                    Map.of("domainAgentSessionId", domainSessionId, "runtimeSessionId", domainSessionId))));
        }
        if (root.hasNonNull("messageId")) {
            events.add(RuntimeEvent.metadata(runId, sessionId, metadataPayload("domain_agent_message",
                    Map.of("domainAgentMessageId", text(root, "messageId")))));
        }
        if ((root.hasNonNull("intent") || root.hasNonNull("skillId")) && !hasCardPayload(root)) {
            Map<String, Object> values = new LinkedHashMap<>();
            putIfPresent(values, "intent", text(root, "intent"));
            putIfPresent(values, "domainAgentId", text(root, "skillId"));
            events.add(RuntimeEvent.metadata(runId, sessionId, metadataPayload("domain_agent", values)));
        }
    }

    private void addStateEvent(String runId, String sessionId, JsonNode root, List<ChatEvent> events) {
        String state = text(root, "state");
        if (state == null || state.isBlank()) {
            return;
        }
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", "state");
        payload.put("state", normalized);
        putIfPresent(payload, "stateDesc", text(root, "stateDesc"));
        if ("THINKING".equals(normalized)) {
            payload.put("status", "STARTED");
            putIfPresent(payload, "text", text(root, "stateDesc"));
            events.add(RuntimeEvent.thinking(runId, sessionId, payload));
            return;
        }
        if ("GENERATE".equals(normalized)) {
            payload.put("stage", "GENERATE");
            putIfPresent(payload, "text", text(root, "stateDesc"));
            events.add(RuntimeEvent.progress(runId, sessionId, payload));
            return;
        }
        payload.put("eventKind", "state");
        events.add(RuntimeEvent.fallback(runId, sessionId, new RuntimeEvent.FallbackPayload(
                "domain-agent", normalized, "state", "runtime", "inline", text(root, "stateDesc"),
                payload)));
    }

    private void addStructuredEvents(String runId, String sessionId, JsonNode root, List<ChatEvent> events) {
        if (root.hasNonNull("processResult")) {
            events.add(RuntimeEvent.progress(runId, sessionId, processPayload(root)));
        }
        if (root.hasNonNull("searchList")) {
            events.add(RuntimeEvent.reference(runId, sessionId,
                    referencePayload("search_list", "searchList", root.get("searchList"))));
        }
        if (root.hasNonNull("sourcesDocuments")) {
            events.add(RuntimeEvent.reference(runId, sessionId,
                    referencePayload("source_documents", "sourcesDocuments", root.get("sourcesDocuments"))));
        }
        if (root.hasNonNull("sourceDocuments")) {
            events.add(RuntimeEvent.reference(runId, sessionId,
                    referencePayload("source_documents", "sourceDocuments", root.get("sourceDocuments"))));
        }
        if (hasCardPayload(root)) {
            events.add(RuntimeEvent.card(runId, sessionId, cardPayload(root)));
        }
    }

    private boolean hasCardPayload(JsonNode root) {
        return root.hasNonNull("cardUrl")
                || root.hasNonNull("diyCardScene")
                || root.hasNonNull("cardList")
                || root.hasNonNull("openCard");
    }

    private Map<String, Object> processPayload(JsonNode root) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", "processResult");
        payload.put("status", "STREAMING");
        payload.put("title", "思考过程");
        payload.put("processResult", payloadSanitizer.sanitizeBusiness(root.get("processResult")));
        JsonNode processResult = root.get("processResult");
        JsonNode dynamicResponse = processResult == null ? null : processResult.get("dynamicResponse");
        if (dynamicResponse != null && dynamicResponse.isArray()) {
            payload.put("dynamicResponse", payloadSanitizer.sanitizeBusiness(dynamicResponse));
            String text = firstDynamicTitle(dynamicResponse);
            if (text != null) {
                payload.put("text", text);
            }
        }
        return payload;
    }

    private Map<String, Object> referencePayload(String referenceType, String fieldName, JsonNode value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", fieldName);
        payload.put("referenceType", referenceType);
        payload.put("references", payloadSanitizer.sanitizeBusiness(value));
        return payload;
    }

    private Map<String, Object> cardPayload(JsonNode root) {
        List<String> sources = cardSources(root);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", cardSourceType(sources));
        payload.put("cardType", cardType(sources));
        payload.put("cardSources", sources);
        putIfPresent(payload, "cardUrl", text(root, "cardUrl"));
        putIfPresent(payload, "openCard", text(root, "openCard"));
        putIfPresent(payload, "intent", text(root, "intent"));
        putIfPresent(payload, "domainAgentId", text(root, "skillId"));
        if (root.hasNonNull("diyCardScene")) {
            payload.put("diyCardScene", payloadSanitizer.sanitizeBusiness(root.get("diyCardScene")));
        }
        if (root.hasNonNull("cardList")) {
            payload.put("cardList", payloadSanitizer.sanitizeBusiness(root.get("cardList")));
        }
        return payload;
    }

    private List<String> cardSources(JsonNode root) {
        List<String> sources = new ArrayList<>(4);
        if (root.hasNonNull("cardUrl")) {
            sources.add("cardUrl");
        }
        if (root.hasNonNull("diyCardScene")) {
            sources.add("diyCardScene");
        }
        if (root.hasNonNull("cardList")) {
            sources.add("cardList");
        }
        if (root.hasNonNull("openCard")) {
            sources.add("openCard");
        }
        return List.copyOf(sources);
    }

    private String cardSourceType(List<String> sources) {
        return sources.size() == 1 ? sources.get(0) : "domain-agent-card";
    }

    private String cardType(List<String> sources) {
        if (sources.size() != 1) {
            return "mixed";
        }
        return switch (sources.get(0)) {
            case "cardUrl" -> "url";
            case "diyCardScene" -> "diyCardScene";
            case "cardList" -> "cardList";
            case "openCard" -> "openCard";
            default -> "domain-agent-card";
        };
    }

    private Map<String, Object> metadataPayload(String metadataType, Map<String, Object> values) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", metadataType);
        payload.put("metadataType", metadataType);
        values.forEach((key, value) -> {
            if (value != null) {
                payload.put(key, value);
            }
        });
        return payload;
    }

    private String firstDynamicTitle(JsonNode dynamicResponse) {
        for (JsonNode item : dynamicResponse) {
            String title = text(item, "title", "titile");
            if (title != null && !title.isBlank()) {
                return title;
            }
        }
        return null;
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private boolean bool(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && !value.isNull() && value.asBoolean(false);
    }

    private String text(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode value = root == null ? null : root.get(field);
            if (value != null && !value.isNull()) {
                return value.asText(null);
            }
        }
        return null;
    }
}
