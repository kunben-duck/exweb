package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceMetadata;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessagePartDraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次 run 内的 assistant 汇总状态。
 *
 * <p>流式 delta 负责实时草稿；下游最终 {@code message.snapshot} 是更权威的最终正文。
 * {@code runtime.*} 事件只保存为历史过程 parts，不混入 assistant 正文。若没有正文但存在卡片、
 * 引用、思考或进度等用户可见 part，也会创建一条空正文 assistant 消息作为 parts 挂载点。</p>
 */
final class AssistantAssembly {
    private final StringBuilder deltaDraft = new StringBuilder();
    private final List<ChatMessagePartDraft> parts = new ArrayList<>();
    private final AgentDataPersistenceState persistenceState;
    private String snapshot;
    private String structuredFallbackContent;
    private boolean persistableOutputObserved;
    private int activeContentAgentPartIndex = -1;
    private StringBuilder activeContentAgentContent;
    private Map<String, Object> activeContentAgentPayload;

    AssistantAssembly() {
        this(AgentDataPersistenceState.full());
    }

    AssistantAssembly(AgentDataPersistenceState persistenceState) {
        this.persistenceState = persistenceState == null
                ? AgentDataPersistenceState.full()
                : persistenceState;
    }

    void observe(ChatEvent event) {
        if (event == null || event.payload() == null) {
            return;
        }
        persistableOutputObserved = persistableOutputObserved || persistableOutput(event);
        if (persistenceState.placeholderMode() && !controlEvent(event)) {
            return;
        }
        if ("message.delta".equals(event.type())) {
            Object delta = event.payload().get("delta");
            if (delta != null) {
                deltaDraft.append(delta);
            }
            return;
        }
        if ("message.snapshot".equals(event.type())) {
            Object content = event.payload().get("content");
            if (content != null) {
                snapshot = String.valueOf(content);
            }
            parts.add(snapshotPart(event));
            return;
        }
        if (event.type() != null && event.type().startsWith("runtime.")) {
            if (isTransientIntentProcessEvent(event.payload())) {
                return;
            }
            if (isDomainAgentStructuredCard(event)) {
                closeContentAgentPart();
            }
            if (isIntentClarificationResponse(event.payload())) {
                if (!AmbiguousRouteSupport.isAmbiguous(event.payload())) {
                    return;
                }
            }
            if (isDomainAgentRefusal(event.payload())) {
                closeContentAgentPart();
                // 拒答前已经输出的正文只作为 MESSAGE_SNAPSHOT/过程 part 保留，不能与新 Agent
                // 的回答拼成最终 content；卡片内容也必须在此切断归属。
                snapshot = null;
                deltaDraft.setLength(0);
                structuredFallbackContent = firstText(event.payload(),
                        "reason", "userMessage", "reasonCode", "code");
            }
            if (isIntentClarificationRequest(event.payload())) {
                structuredFallbackContent = intentClarificationQuestion(event.payload());
            }
            if (isRouteSwitchConfirmationRequest(event.payload()) || isRouteSwitchDeclined(event.payload())) {
                if (isRouteSwitchConfirmationRequest(event.payload())) {
                    snapshot = null;
                    deltaDraft.setLength(0);
                }
                structuredFallbackContent = firstText(event.payload(), "message", "reason", "sourceType");
            }
            if (isContentAgentCard(event)) {
                appendContentAgentPart(event);
                return;
            }
            parts.add(runtimePart(event));
        }
    }

    boolean shouldPersistMessage() {
        if (persistenceState.placeholderMode()) {
            return persistenceState.runtimeDispatchStarted()
                    || persistableOutputObserved
                    || persistedParts().stream().anyMatch(AssistantAssembly::userVisiblePart);
        }
        return hasContent() || persistedParts().stream().anyMatch(AssistantAssembly::userVisiblePart);
    }

    String finalContent() {
        if (persistenceState.placeholderMode()) {
            return persistenceState.placeholderContent();
        }
        if (snapshot != null) {
            return snapshot;
        }
        if (!deltaDraft.isEmpty()) {
            return deltaDraft.toString();
        }
        return structuredFallbackContent == null ? "" : structuredFallbackContent;
    }

    List<ChatMessagePartDraft> parts() {
        return List.copyOf(persistedParts());
    }

    boolean appendAnswerPart() {
        return !persistenceState.placeholderMode();
    }

    String assistantMetadata(String metadataJson) {
        return AgentDataPersistenceMetadata.mergeAssistantMetadata(metadataJson, persistenceState);
    }

    AgentDataPersistenceState persistenceState() {
        return persistenceState;
    }

    static List<ChatMessagePartDraft> controlParts(List<ChatMessagePartDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        return drafts.stream().filter(AssistantAssembly::controlPart).toList();
    }

    private List<ChatMessagePartDraft> persistedParts() {
        materializeContentAgentPart();
        if (!persistenceState.placeholderMode()) {
            return parts;
        }
        return parts.stream()
                .filter(AssistantAssembly::controlPart)
                .toList();
    }

    private void appendContentAgentPart(ChatEvent event) {
        String chunk = stringValue(event.payload().get("contentAgent"));
        if (chunk == null) {
            return;
        }
        if (activeContentAgentPartIndex < 0) {
            activeContentAgentPartIndex = parts.size();
            activeContentAgentContent = new StringBuilder();
            activeContentAgentPayload = eventPartPayload(event);
            parts.add(contentAgentPart(chunk, activeContentAgentPayload));
        } else {
            activeContentAgentPayload = eventPartPayload(event);
        }
        activeContentAgentContent.append(chunk);
    }

    private void closeContentAgentPart() {
        materializeContentAgentPart();
        activeContentAgentPartIndex = -1;
        activeContentAgentContent = null;
        activeContentAgentPayload = null;
    }

    private void materializeContentAgentPart() {
        if (activeContentAgentPartIndex < 0 || activeContentAgentContent == null
                || activeContentAgentPayload == null) {
            return;
        }
        String content = activeContentAgentContent.toString();
        Map<String, Object> payload = new LinkedHashMap<>(activeContentAgentPayload);
        payload.put("contentAgent", content);
        parts.set(activeContentAgentPartIndex, contentAgentPart(content, payload));
    }

    private static ChatMessagePartDraft contentAgentPart(String content, Map<String, Object> payload) {
        return new ChatMessagePartDraft("CARD", "contentAgent", null, payload);
    }

    private boolean hasContent() {
        return snapshot != null && !snapshot.isEmpty()
                || !deltaDraft.isEmpty()
                || structuredFallbackContent != null && !structuredFallbackContent.isEmpty();
    }

    private static boolean userVisiblePart(ChatMessagePartDraft part) {
        if (part == null || part.partType() == null) {
            return false;
        }
        return switch (part.partType()) {
            case "PROGRESS", "AGENT", "THINKING", "TOOL", "REFERENCE", "CARD",
                 "CLARIFICATION_REQUEST", "CLARIFICATION_RESPONSE",
                 "AGENT_CLARIFICATION_REQUEST", "AGENT_CLARIFICATION_RESPONSE",
                 "INTENT_CLARIFICATION_REQUEST", "INTENT_CLARIFICATION_RESPONSE",
                 "DOMAIN_AGENT_REFUSAL", "ROUTE_SWITCH_CONFIRMATION_REQUEST",
                 "ROUTE_SWITCH_CONFIRMATION_RESPONSE", "ROUTE_SWITCH_DECLINED" -> true;
            default -> false;
        };
    }

    private static boolean controlPart(ChatMessagePartDraft part) {
        if (part == null || part.partType() == null) {
            return false;
        }
        return switch (part.partType()) {
            case "CLARIFICATION_REQUEST", "CLARIFICATION_RESPONSE",
                 "AGENT_CLARIFICATION_REQUEST", "AGENT_CLARIFICATION_RESPONSE",
                 "INTENT_CLARIFICATION_REQUEST", "INTENT_CLARIFICATION_RESPONSE",
                 "ROUTE_SWITCH_CONFIRMATION_REQUEST", "ROUTE_SWITCH_CONFIRMATION_RESPONSE",
                 "ROUTE_SWITCH_DECLINED" -> true;
            default -> false;
        };
    }

    private static boolean controlEvent(ChatEvent event) {
        if (event == null || event.type() == null || !event.type().startsWith("runtime.")) {
            return false;
        }
        return controlPart(runtimePart(event));
    }

    private static boolean persistableOutput(ChatEvent event) {
        if ("message.delta".equals(event.type())) {
            return nonEmpty(event.payload().get("delta"));
        }
        if ("message.snapshot".equals(event.type())) {
            return nonEmpty(event.payload().get("content"));
        }
        return event.type() != null
                && event.type().startsWith("runtime.")
                && !isTransientIntentProcessEvent(event.payload())
                && userVisiblePart(runtimePart(event));
    }

    private static boolean isContentAgentCard(ChatEvent event) {
        return event != null
                && "runtime.card".equals(event.type())
                && "domain-agent".equals(stringValue(event.payload().get("source")))
                && "contentAgent".equals(stringValue(event.payload().get("sourceType")))
                && "contentAgent".equals(stringValue(event.payload().get("cardType")))
                && event.payload().get("contentAgent") instanceof String;
    }

    private static boolean isDomainAgentStructuredCard(ChatEvent event) {
        return event != null
                && "runtime.card".equals(event.type())
                && "domain-agent".equals(stringValue(event.payload().get("source")))
                && !isContentAgentCard(event);
    }

    private static boolean nonEmpty(Object value) {
        return value != null && !String.valueOf(value).isEmpty();
    }

    private static ChatMessagePartDraft runtimePart(ChatEvent event) {
        Map<String, Object> payload = eventPartPayload(event);
        String sourceType = stringValue(payload.get("sourceType"));
        return new ChatMessagePartDraft(partType(event.type(), payload), sourceType, contentText(event.type(), payload), payload);
    }

    private static ChatMessagePartDraft snapshotPart(ChatEvent event) {
        Map<String, Object> payload = eventPartPayload(event);
        String sourceType = stringValue(payload.get("sourceType"));
        if (sourceType == null || sourceType.isBlank()) {
            sourceType = "message.snapshot";
        }
        return new ChatMessagePartDraft(
                "MESSAGE_SNAPSHOT",
                sourceType,
                stringValue(payload.get("content")),
                "回答快照",
                "INFO",
                "answer",
                "collapsible",
                false,
                payload
        );
    }

    private static Map<String, Object> eventPartPayload(ChatEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (event.payload() != null) {
            payload.putAll(event.payload());
        }
        payload.put("serverTimestampMs", event.createdAt().toEpochMilli());
        return payload;
    }

    private static String partType(String eventType, Map<String, Object> payload) {
        if ("runtime.card".equals(eventType) && isQuestionnaireApprovalRequest(payload)) {
            return "AGENT_CLARIFICATION_REQUEST";
        }
        if ("runtime.card".equals(eventType) && isClarificationResponse(payload)) {
            return agentClarificationResponse(payload) ? "AGENT_CLARIFICATION_RESPONSE" : "CLARIFICATION_RESPONSE";
        }
        if ("runtime.card".equals(eventType) && isIntentClarificationRequest(payload)) {
            return "INTENT_CLARIFICATION_REQUEST";
        }
        if ("runtime.card".equals(eventType) && isIntentClarificationResponse(payload)) {
            return "INTENT_CLARIFICATION_RESPONSE";
        }
        if ("runtime.card".equals(eventType) && isRouteSwitchConfirmationRequest(payload)) {
            return "ROUTE_SWITCH_CONFIRMATION_REQUEST";
        }
        if ("runtime.card".equals(eventType) && isRouteSwitchConfirmationResponse(payload)) {
            return "ROUTE_SWITCH_CONFIRMATION_RESPONSE";
        }
        if ("runtime.card".equals(eventType) && isRouteSwitchDeclined(payload)) {
            return "ROUTE_SWITCH_DECLINED";
        }
        if (isDomainAgentRefusal(payload)) {
            return "DOMAIN_AGENT_REFUSAL";
        }
        return switch (eventType) {
            case "runtime.progress" -> "PROGRESS";
            case "runtime.metadata" -> "METADATA";
            case "runtime.agent" -> "AGENT";
            case "runtime.thinking" -> "THINKING";
            case "runtime.tool" -> "TOOL";
            case "runtime.reference" -> "REFERENCE";
            case "runtime.card" -> "CARD";
            default -> "RUNTIME_EVENT";
        };
    }

    private static String contentText(String eventType, Map<String, Object> payload) {
        if ("runtime.progress".equals(eventType)) {
            return firstText(payload, "text", "message");
        }
        if ("runtime.agent".equals(eventType)) {
            return firstText(payload, "task", "agentName");
        }
        if ("runtime.tool".equals(eventType)) {
            String toolName = firstText(payload, "toolName");
            String preview = firstText(payload, "inputPreview");
            if (toolName != null && preview != null) {
                return toolName + ": " + preview;
            }
            return toolName == null ? preview : toolName;
        }
        if ("runtime.thinking".equals(eventType)) {
            String text = firstText(payload, "text", "title");
            if (text != null) {
                return text;
            }
            String status = firstText(payload, "status");
            String operationId = firstText(payload, "operationId");
            return operationId == null ? status : status + ": " + operationId;
        }
        if ("runtime.metadata".equals(eventType)) {
            if (isDomainAgentRefusal(payload)) {
                return firstText(payload, "reason", "userMessage", "reasonCode", "code");
            }
            return firstText(payload, "projectHome", "metadataType");
        }
        if ("runtime.reference".equals(eventType)) {
            return firstText(payload, "delta", "title", "url", "referenceType", "sourceType");
        }
        if ("runtime.card".equals(eventType)) {
            if (isQuestionnaireApprovalRequest(payload)) {
                return firstText(payload, "title", "message", "detail", "sourceType");
            }
            if (isClarificationResponse(payload)) {
                return firstText(payload, "answerText", "sourceType");
            }
            if (isIntentClarificationRequest(payload)) {
                return intentClarificationQuestion(payload);
            }
            if (isIntentClarificationResponse(payload)) {
                return firstText(payload, "answerText", "sourceType");
            }
            if (isRouteSwitchConfirmationRequest(payload)) {
                return firstText(payload, "message", "candidateIntentName", "candidateTargetId", "sourceType");
            }
            if (isRouteSwitchConfirmationResponse(payload)) {
                return firstText(payload, "message", "candidateTargetId", "sourceType");
            }
            if (isRouteSwitchDeclined(payload)) {
                return firstText(payload, "message", "currentTargetId", "sourceType");
            }
            return firstText(payload, "delta", "cardUrl", "intent", "domainAgentId", "skillId", "cardType", "sourceType");
        }
        return firstText(payload, "text", "displayText", "sourceType");
    }

    private static boolean isQuestionnaireApprovalRequest(Map<String, Object> payload) {
        return "approval-request".equals(stringValue(payload.get("sourceType")))
                && "questionnaire".equalsIgnoreCase(stringValue(payload.get("operation_type")));
    }

    private static boolean isClarificationResponse(Map<String, Object> payload) {
        return "clarification-response".equals(stringValue(payload.get("sourceType")));
    }

    private static boolean agentClarificationResponse(Map<String, Object> payload) {
        return "AGENT_CLARIFICATION".equals(stringValue(payload.get("interactionType")))
                || "CLARIFICATION".equals(stringValue(payload.get("interactionType")));
    }

    private static boolean isIntentClarificationRequest(Map<String, Object> payload) {
        return "intent-clarification-request".equals(stringValue(payload.get("sourceType")));
    }

    private static boolean isIntentClarificationResponse(Map<String, Object> payload) {
        return "intent-clarification-response".equals(stringValue(payload.get("sourceType")));
    }

    private static boolean isTransientIntentProcessEvent(Map<String, Object> payload) {
        if (!"intent-agent".equals(stringValue(payload.get("source")))) {
            return false;
        }
        String sourceType = stringValue(payload.get("sourceType"));
        return "intent-start".equals(sourceType)
                || "intent-progress".equals(sourceType)
                || "intent-delta".equals(sourceType);
    }

    private static String intentClarificationQuestion(Map<String, Object> payload) {
        String direct = firstText(payload, "clarifyQuestion", "question", "message", "detail");
        if (direct != null) {
            return direct;
        }
        Object clarification = payload == null ? null : payload.get("clarification");
        if (clarification instanceof Map<?, ?> map) {
            return firstText(castStringMap(map), "clarifyQuestion", "question", "message");
        }
        return null;
    }

    private static Map<String, Object> castStringMap(Map<?, ?> source) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    private static boolean isRouteSwitchConfirmationRequest(Map<String, Object> payload) {
        return "route-switch-confirmation-request".equals(stringValue(payload.get("sourceType")));
    }

    private static boolean isRouteSwitchConfirmationResponse(Map<String, Object> payload) {
        return "route-switch-confirmation-response".equals(stringValue(payload.get("sourceType")));
    }

    private static boolean isRouteSwitchDeclined(Map<String, Object> payload) {
        return "route-switch-declined".equals(stringValue(payload.get("sourceType")));
    }

    private static boolean isDomainAgentRefusal(Map<String, Object> payload) {
        return "agent.refusal".equals(stringValue(payload.get("sourceType")))
                && "domain_agent_control".equals(stringValue(payload.get("metadataType")))
                && "REROUTE".equals(stringValue(payload.get("supervisorAction")));
    }

    private static String firstText(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            String value = stringValue(payload.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
