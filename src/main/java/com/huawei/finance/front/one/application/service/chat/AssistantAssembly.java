package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatMessagePartDraft;
import java.util.ArrayList;
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
    private String snapshot;
    private String structuredFallbackContent;

    void observe(ChatEvent event) {
        if (event == null || event.payload() == null) {
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
            if (isIntentClarificationResponse(event.payload())) {
                return;
            }
            if (isIntentClarificationRequest(event.payload())) {
                structuredFallbackContent = intentClarificationQuestion(event.payload());
            }
            parts.add(runtimePart(event));
        }
    }

    boolean shouldPersistMessage() {
        return hasContent() || parts.stream().anyMatch(AssistantAssembly::userVisiblePart);
    }

    String finalContent() {
        if (snapshot != null) {
            return snapshot;
        }
        if (!deltaDraft.isEmpty()) {
            return deltaDraft.toString();
        }
        return structuredFallbackContent == null ? "" : structuredFallbackContent;
    }

    List<ChatMessagePartDraft> parts() {
        return List.copyOf(parts);
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
                 "DOMAIN_AGENT_REFUSAL", "DOMAIN_AGENT_SWITCH_CONFIRMATION_REQUEST",
                 "DOMAIN_AGENT_SWITCH_DECLINED" -> true;
            default -> false;
        };
    }

    private static ChatMessagePartDraft runtimePart(ChatEvent event) {
        Map<String, Object> payload = event.payload() == null ? Map.of() : event.payload();
        String sourceType = stringValue(payload.get("sourceType"));
        return new ChatMessagePartDraft(partType(event.type(), payload), sourceType, contentText(event.type(), payload), payload);
    }

    private static ChatMessagePartDraft snapshotPart(ChatEvent event) {
        Map<String, Object> payload = event.payload() == null ? Map.of() : event.payload();
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
        if ("runtime.card".equals(eventType) && isDomainAgentSwitchConfirmation(payload)) {
            return "DOMAIN_AGENT_SWITCH_CONFIRMATION_REQUEST";
        }
        if ("runtime.card".equals(eventType) && isDomainAgentSwitchDeclined(payload)) {
            return "DOMAIN_AGENT_SWITCH_DECLINED";
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
            if (isDomainAgentSwitchConfirmation(payload)) {
                return firstText(payload, "candidateIntentName", "candidateDomainAgentId", "sourceType");
            }
            if (isDomainAgentSwitchDeclined(payload)) {
                return firstText(payload, "currentDomainAgentId", "sourceType");
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

    private static boolean isDomainAgentSwitchConfirmation(Map<String, Object> payload) {
        return "domain-agent-switch-confirmation-request".equals(stringValue(payload.get("sourceType")));
    }

    private static boolean isDomainAgentSwitchDeclined(Map<String, Object> payload) {
        return "domain-agent-switch-declined".equals(stringValue(payload.get("sourceType")));
    }

    private static boolean isDomainAgentRefusal(Map<String, Object> payload) {
        return "domain-agent-reroute".equals(stringValue(payload.get("sourceType")))
                && !"AUTO_SWITCH".equals(stringValue(payload.get("action")));
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
