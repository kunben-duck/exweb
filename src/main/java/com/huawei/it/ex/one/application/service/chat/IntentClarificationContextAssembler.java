package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles Intent clarification commands, private document context and the final folded query.
 *
 * <p>This component is a pure data transformer. It performs no persistence, scheduling or external calls.</p>
 */
final class IntentClarificationContextAssembler {
    static final String DOCUMENT_IDS = "_intentClarificationDocumentIds";
    private static final String DOMAIN_AGENT_REROUTE_CONTEXT = "domainAgentRerouteContext";

    static String answerWithAttachments(String answerText, List<AttachmentRef> attachments) {
        String normalizedAnswer = answerText == null || answerText.isBlank() ? null : answerText.trim();
        String fileText = uploadedDocumentText(attachments);
        if (normalizedAnswer == null) {
            return fileText;
        }
        return fileText == null ? normalizedAnswer : normalizedAnswer + " " + fileText;
    }

    private static String uploadedDocumentText(List<AttachmentRef> attachments) {
        return attachments == null || attachments.isEmpty()
                ? null
                : "[用户上传文档] " + attachments.stream()
                .map(AttachmentRef::name)
                .collect(java.util.stream.Collectors.joining("，"));
    }

    ChatCommand command(UserContext user, ChatSession session, ChatInteractionRequest interaction,
                        Map<String, Object> responsePayload, ContinuationInput input) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, Object> requestPayload = interaction.requestPayload() == null
                ? Map.of()
                : interaction.requestPayload();
        String clarifyAnswer = blankToDefault(input.intentQuery(), answer(responsePayload));
        String originalQuery = blankToDefault(firstText(requestPayload.get("originalQuery"), clarifyAnswer), "");
        List<Map<String, Object>> history = appendHistory(
                requestPayload, responsePayload, clarifyAnswer, originalQuery);
        Map<String, Object> clarification = new LinkedHashMap<>();
        clarification.put("interactionId", interaction.id());
        clarification.put("intentSessionId",
                interaction.runtimeSessionId() == null ? "" : interaction.runtimeSessionId());
        clarification.put("intentRequestId", requestPayload.getOrDefault("intentRequestId", ""));
        clarification.put("originalQuery", originalQuery);
        putNonNull(clarification, "clarifyQuestion", question(requestPayload));
        putNonNull(clarification, "clarificationType", type(requestPayload));
        clarification.put("answerText", clarifyAnswer == null ? "" : clarifyAnswer);
        clarification.put("clarificationHistory", history);
        clarification.put("request", withoutDocumentIds(requestPayload));
        clarification.put("response", responsePayload == null ? Map.of() : responsePayload);
        metadata.put("routeTrigger", "clarify_answer");
        metadata.put("intentClarification", Map.copyOf(clarification));
        Object rerouteContext = requestPayload.get(DOMAIN_AGENT_REROUTE_CONTEXT);
        if (rerouteContext instanceof Map<?, ?> map) {
            metadata.put(DOMAIN_AGENT_REROUTE_CONTEXT, mapOrEmpty(map));
        }
        return new ChatCommand(null, user.tenantId(), user.ownerUserId(), session.id(), null,
                null, clarifyAnswer == null ? "" : clarifyAnswer,
                input.cumulativeAttachments(), Map.copyOf(metadata),
                null, null, ChatRunMode.NEXT, interaction.assistantMessageId(), null, null, "clarify_answer",
                null, null, null, Map.of(), null, null, input.agentMode());
    }

    ChatCommand selectionCommand(
            UserContext user,
            ChatSession session,
            ChatInteractionRequest interaction,
            ContinuationInput input,
            String foldedQuery) {
        return new ChatCommand(
                null,
                user.tenantId(),
                user.ownerUserId(),
                session.id(),
                null,
                null,
                foldedQuery == null ? "" : foldedQuery,
                input.cumulativeAttachments(),
                Map.of(),
                null,
                null,
                ChatRunMode.NEXT,
                interaction.assistantMessageId(),
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                null,
                null,
                input.agentMode());
    }

    String routeMemoryQuery(ChatRunMessagePlan messagePlan, ChatInteractionRequest interaction) {
        return routeMemoryQuery(messagePlan, interaction, null);
    }

    String routeMemoryQuery(ChatRunMessagePlan messagePlan, ChatInteractionRequest interaction,
                            String intentAnswer) {
        if (messagePlan == null || messagePlan.userMessage() == null) {
            return "";
        }
        if (interaction != null && interaction.interactionType() == ChatInteractionType.INTENT_CLARIFICATION) {
            String folded = foldedQuery(interaction, messagePlan.userMessage().content(), intentAnswer);
            if (folded != null && !folded.isBlank()) {
                return folded;
            }
        }
        String content = messagePlan.userMessage().content();
        return content == null ? "" : content;
    }

    String routeMemoryQueryForSelection(ChatInteractionRequest interaction) {
        if (interaction == null) {
            return "";
        }
        Map<String, Object> payload = interaction.requestPayload() == null
                ? Map.of()
                : interaction.requestPayload();
        String originalQuery = firstText(payload.get("originalQuery"));
        StringBuilder builder = new StringBuilder();
        builder.append("用户:").append(originalQuery == null ? "" : originalQuery);
        for (Map<String, Object> item : history(payload)) {
            String clarificationQuestion = firstText(
                    item.get("clarifyQuestion"), item.get("question"));
            String clarificationAnswer = firstText(
                    item.get("answer"), item.get("answerText"));
            if (clarificationQuestion != null) {
                builder.append("；系统追问:").append(clarificationQuestion);
            }
            if (clarificationAnswer != null) {
                builder.append("；用户:").append(clarificationAnswer);
            }
        }
        return builder.toString();
    }

    List<String> documentIds(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get(DOCUMENT_IDS);
        if (!(value instanceof Iterable<?> values)) {
            return List.of();
        }
        LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
        for (Object item : values) {
            if (item != null && !String.valueOf(item).isBlank()) {
                ids.putIfAbsent(String.valueOf(item).trim(), Boolean.TRUE);
            }
        }
        return List.copyOf(ids.keySet());
    }

    Map<String, Object> withoutDocumentIds(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty() || !payload.containsKey(DOCUMENT_IDS)) {
            return payload == null ? Map.of() : payload;
        }
        Map<String, Object> copy = new LinkedHashMap<>(payload);
        copy.remove(DOCUMENT_IDS);
        return ChatPayloadMaps.immutableCopy(copy);
    }

    private String foldedQuery(ChatInteractionRequest interaction, String fallbackAnswer, String intentAnswer) {
        Map<String, Object> payload = interaction.requestPayload() == null ? Map.of() : interaction.requestPayload();
        String originalQuery = firstText(payload.get("originalQuery"), fallbackAnswer);
        List<Map<String, Object>> history = new ArrayList<>(history(payload));
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("type", "clarify");
        putNonNull(current, "query", firstText(
                payload.get("clarifyTriggerQuery"), payload.get("originalQuery"), originalQuery));
        putNonNull(current, "clarifyQuestion", question(payload));
        putNonNull(current, "clarificationType", type(payload));
        Map<String, Object> responsePayload = interaction.responsePayload() == null
                ? Map.of()
                : interaction.responsePayload();
        putNonNull(current, "answer", firstText(intentAnswer, responsePayload.get("answerText"), fallbackAnswer));
        if (current.size() > 1) {
            history.add(Map.copyOf(current));
        }
        StringBuilder builder = new StringBuilder();
        builder.append("用户:").append(originalQuery == null ? "" : originalQuery);
        for (Map<String, Object> item : history) {
            String clarificationQuestion = firstText(item.get("clarifyQuestion"), item.get("question"));
            String clarificationAnswer = firstText(item.get("answer"), item.get("answerText"));
            if (clarificationQuestion != null) {
                builder.append("；系统追问:").append(clarificationQuestion);
            }
            if (clarificationAnswer != null) {
                builder.append("；用户:").append(clarificationAnswer);
            }
        }
        return builder.toString();
    }

    private List<Map<String, Object>> appendHistory(Map<String, Object> requestPayload,
                                                     Map<String, Object> responsePayload,
                                                     String answerText,
                                                     String originalQuery) {
        List<Map<String, Object>> result = new ArrayList<>(history(requestPayload));
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("type", "clarify");
        putNonNull(current, "query", firstText(
                requestPayload.get("clarifyTriggerQuery"), requestPayload.get("originalQuery"), originalQuery));
        putNonNull(current, "clarifyQuestion", question(requestPayload));
        putNonNull(current, "clarificationType", type(requestPayload));
        if (answerText != null && !answerText.isBlank()) {
            current.put("answer", answerText);
        }
        if (responsePayload != null && !responsePayload.isEmpty()) {
            current.put("response", responsePayload);
        }
        if (current.size() > 1) {
            result.add(Map.copyOf(current));
        }
        return List.copyOf(result);
    }

    private String answer(Map<String, Object> responsePayload) {
        if (responsePayload == null || responsePayload.isEmpty()) {
            return "";
        }
        String normalized = firstText(responsePayload.get("answerText"));
        if (normalized != null) {
            return normalized;
        }
        List<Map.Entry<String, String>> answers = normalizedAnswers(responsePayload.get("questionnaireAnswers"));
        if (answers.size() == 1) {
            return answers.getFirst().getValue();
        }
        if (!answers.isEmpty()) {
            return answers.stream()
                    .map(entry -> (entry.getKey().isBlank() ? "问题" : entry.getKey()) + "：" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
        return firstText(responsePayload.get("answer"),
                responsePayload.get("content"), responsePayload.get("message"));
    }

    private List<Map.Entry<String, String>> normalizedAnswers(Object answers) {
        if (!(answers instanceof Map<?, ?> answerMap) || answerMap.isEmpty()) {
            return List.of();
        }
        return answerMap.entrySet().stream()
                .map(entry -> Map.entry(
                        entry.getKey() == null ? "" : String.valueOf(entry.getKey()).trim(),
                        entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim()))
                .filter(entry -> !entry.getValue().isBlank())
                .sorted(Map.Entry.comparingByKey())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> history(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("clarificationHistory");
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Map.class::isInstance)
                .map(item -> {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    ((Map<String, Object>) item).forEach((key, itemValue) -> {
                        if (key != null && itemValue != null) {
                            copy.put(key, itemValue);
                        }
                    });
                    return Map.copyOf(copy);
                })
                .toList();
    }

    private String question(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        Object clarification = payload.get("clarification");
        String nested = clarification instanceof Map<?, ?> map ? firstText(map.get("clarifyQuestion")) : null;
        return firstText(payload.get("clarifyQuestion"), payload.get("question"), nested);
    }

    private String type(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        Object clarification = payload.get("clarification");
        String nested = clarification instanceof Map<?, ?> map ? firstText(map.get("type")) : null;
        return firstText(payload.get("clarificationType"), payload.get("type"), nested);
    }

    private void putNonNull(Map<String, Object> target, String key, Object value) {
        if (target != null && key != null && value != null) {
            target.put(key, value);
        }
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

    private Map<String, Object> mapOrEmpty(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return Map.copyOf(copy);
    }

    record ContinuationInput(
            String messageText,
            String intentQuery,
            List<AttachmentRef> currentAttachments,
            List<AttachmentRef> cumulativeAttachments,
            List<UploadedDocument> cumulativeDocuments,
            List<String> cumulativeDocumentIds,
            Map<String, Object> runtimeMetadata,
            AgentModeProfile agentMode
    ) {
        ContinuationInput {
            messageText = messageText == null ? "" : messageText;
            intentQuery = intentQuery == null ? "" : intentQuery;
            currentAttachments = currentAttachments == null ? List.of() : List.copyOf(currentAttachments);
            cumulativeAttachments = cumulativeAttachments == null ? List.of() : List.copyOf(cumulativeAttachments);
            cumulativeDocuments = cumulativeDocuments == null ? List.of() : List.copyOf(cumulativeDocuments);
            cumulativeDocumentIds = cumulativeDocumentIds == null ? List.of() : List.copyOf(cumulativeDocumentIds);
            runtimeMetadata = runtimeMetadata == null ? Map.of() : ChatPayloadMaps.immutableCopy(runtimeMetadata);
        }
    }
}
