package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.model.IntentClarificationContinuationInput;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import com.huawei.it.ex.one.chat.domain.ChatInteractionType;
import com.huawei.it.ex.one.chat.domain.ChatRunMessagePlan;
import com.huawei.it.ex.one.chat.domain.ChatRunMode;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.application.model.IntentClarificationDocuments;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图澄清命令与折叠查询装配器。
 *
 * <p>这里只承载原主编排服务中的纯数据转换，不访问仓储、外部服务或调度器。</p>
 */
public final class IntentClarificationContextAssembler {
    private static final String DOMAIN_AGENT_REROUTE_CONTEXT_METADATA = "domainAgentRerouteContext";

    public ChatCommand command(UserContext user, ChatSession session,
                               ChatInteractionRequest interaction,
                               Map<String, Object> responsePayload,
                               IntentClarificationContinuationInput input) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, Object> requestPayload = interaction.requestPayload() == null
                ? Map.of()
                : interaction.requestPayload();
        Map<String, Object> publicRequestPayload = IntentClarificationDocuments.withoutInternalIds(requestPayload);
        String clarifyAnswer = blankToDefault(input.intentQuery(), intentClarificationAnswer(responsePayload));
        String resolvedOriginalQuery = blankToDefault(
                firstText(requestPayload.get("originalQuery"), clarifyAnswer), "");
        List<Map<String, Object>> clarificationHistory = appendClarificationHistory(
                requestPayload, responsePayload, clarifyAnswer, resolvedOriginalQuery);
        Map<String, Object> intentClarification = new LinkedHashMap<>();
        intentClarification.put("interactionId", interaction.id());
        intentClarification.put("intentSessionId",
                interaction.runtimeSessionId() == null ? "" : interaction.runtimeSessionId());
        intentClarification.put("intentRequestId", requestPayload.getOrDefault("intentRequestId", ""));
        intentClarification.put("originalQuery", resolvedOriginalQuery);
        putNonNull(intentClarification, "clarifyQuestion", clarifyQuestion(requestPayload));
        putNonNull(intentClarification, "clarificationType", clarificationType(requestPayload));
        intentClarification.put("answerText", clarifyAnswer == null ? "" : clarifyAnswer);
        intentClarification.put("clarificationHistory", clarificationHistory);
        intentClarification.put("request", publicRequestPayload);
        intentClarification.put("response", responsePayload == null ? Map.of() : responsePayload);
        metadata.put("routeTrigger", "clarify_answer");
        metadata.put("intentClarification", Map.copyOf(intentClarification));
        Object domainAgentRerouteContext = requestPayload.get(DOMAIN_AGENT_REROUTE_CONTEXT_METADATA);
        if (domainAgentRerouteContext instanceof Map<?, ?> rerouteContext) {
            metadata.put(DOMAIN_AGENT_REROUTE_CONTEXT_METADATA, mapOrEmpty(rerouteContext));
        }
        return new ChatCommand(null, user.tenantId(), user.ownerUserId(), session.id(), null,
                null, clarifyAnswer == null ? "" : clarifyAnswer,
                input.cumulativeAttachments(), Map.copyOf(metadata),
                null, null, ChatRunMode.NEXT, interaction.assistantMessageId(), null, null, "clarify_answer");
    }

    public String routeMemoryQuery(ChatRunMessagePlan messagePlan, ChatInteractionRequest interaction) {
        return routeMemoryQuery(messagePlan, interaction, null);
    }

    public String routeMemoryQuery(ChatRunMessagePlan messagePlan, ChatInteractionRequest interaction,
                                   String intentAnswer) {
        if (messagePlan == null || messagePlan.userMessage() == null) {
            return "";
        }
        if (interaction != null && interaction.interactionType() == ChatInteractionType.INTENT_CLARIFICATION) {
            String folded = foldedIntentClarificationQuery(
                    interaction, messagePlan.userMessage().content(), intentAnswer);
            if (folded != null && !folded.isBlank()) {
                return folded;
            }
        }
        String content = messagePlan.userMessage().content();
        return content == null ? "" : content;
    }

    private String foldedIntentClarificationQuery(ChatInteractionRequest interaction, String fallbackAnswer,
                                                   String intentAnswer) {
        Map<String, Object> payload = interaction.requestPayload() == null ? Map.of() : interaction.requestPayload();
        String originalQuery = firstText(payload.get("originalQuery"), fallbackAnswer);
        List<Map<String, Object>> history = new ArrayList<>(clarificationHistory(payload));
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("type", "clarify");
        putNonNull(current, "query", firstText(
                payload.get("clarifyTriggerQuery"), payload.get("originalQuery"), originalQuery));
        putNonNull(current, "clarifyQuestion", clarifyQuestion(payload));
        putNonNull(current, "clarificationType", clarificationType(payload));
        Map<String, Object> responsePayload = interaction.responsePayload() == null
                ? Map.of()
                : interaction.responsePayload();
        String answer = firstText(intentAnswer, responsePayload.get("answerText"), fallbackAnswer);
        putNonNull(current, "answer", answer);
        if (current.size() > 1) {
            history.add(Map.copyOf(current));
        }
        StringBuilder builder = new StringBuilder();
        builder.append("user:").append(originalQuery == null ? "" : originalQuery);
        for (Map<String, Object> item : history) {
            String question = firstText(item.get("clarifyQuestion"), item.get("question"));
            String itemAnswer = firstText(item.get("answer"), item.get("answerText"));
            if (question != null) {
                builder.append("；澄清问:").append(question);
            }
            if (itemAnswer != null) {
                builder.append("；用户:").append(itemAnswer);
            }
        }
        return builder.toString();
    }

    private List<Map<String, Object>> appendClarificationHistory(Map<String, Object> requestPayload,
                                                                  Map<String, Object> responsePayload,
                                                                  String answerText,
                                                                  String originalQuery) {
        List<Map<String, Object>> history = new ArrayList<>(clarificationHistory(requestPayload));
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("type", "clarify");
        putNonNull(current, "query", firstText(requestPayload.get("clarifyTriggerQuery"),
                requestPayload.get("originalQuery"), originalQuery));
        String question = clarifyQuestion(requestPayload);
        if (question != null) {
            current.put("clarifyQuestion", question);
        }
        String type = clarificationType(requestPayload);
        if (type != null) {
            current.put("clarificationType", type);
        }
        if (answerText != null && !answerText.isBlank()) {
            current.put("answer", answerText);
        }
        if (responsePayload != null && !responsePayload.isEmpty()) {
            current.put("response", responsePayload);
        }
        if (current.size() > 1) {
            history.add(Map.copyOf(current));
        }
        return List.copyOf(history);
    }

    private String intentClarificationAnswer(Map<String, Object> responsePayload) {
        if (responsePayload == null || responsePayload.isEmpty()) {
            return "";
        }
        String normalized = firstText(responsePayload.get("answerText"));
        if (normalized != null) {
            return normalized;
        }
        List<Map.Entry<String, String>> entries = normalizedAnswers(responsePayload.get("questionnaireAnswers"));
        if (entries.size() == 1) {
            return entries.getFirst().getValue();
        }
        if (!entries.isEmpty()) {
            return entries.stream()
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
    private List<Map<String, Object>> clarificationHistory(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("clarificationHistory");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
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

    private String clarifyQuestion(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        Object clarification = payload.get("clarification");
        String nested = clarification instanceof Map<?, ?> map ? firstText(map.get("clarifyQuestion")) : null;
        return firstText(payload.get("clarifyQuestion"), payload.get("question"), nested);
    }

    private String clarificationType(Map<String, Object> payload) {
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
}
