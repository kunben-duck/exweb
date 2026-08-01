package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 Interaction claim 前校验 Relay 问卷回答，避免非法答案消耗等待态。
 */
final class RelayQuestionnaireAnswerValidator {
    private static final String SOURCE_TYPE = "approval-request";
    private static final String OPERATION_TYPE = "questionnaire";

    void validate(ChatInteractionResponseCommand command, ChatInteractionRequest interaction) {
        if (!isRelayQuestionnaire(interaction)) {
            return;
        }
        if (command.approved() == null) {
            throw new IllegalArgumentException("Relay 问卷回答 approved 不能为空");
        }
        if (command.scope() != null && !command.scope().isBlank()
                && !"once".equalsIgnoreCase(command.scope().trim())) {
            throw new IllegalArgumentException("Relay 问卷回答 scope 仅支持 once");
        }
        Map<String, Object> answers = command.questionnaireAnswers();
        if (answers == null || answers.size() != 1) {
            throw new IllegalArgumentException(
                    "Relay 问卷 questionnaireAnswers 必须且只能包含 label 或 ignore");
        }
        if (answers.containsKey("ignore")) {
            validateIgnore(command, answers.get("ignore"));
            return;
        }
        if (!answers.containsKey("label")) {
            throw new IllegalArgumentException(
                    "Relay 问卷 questionnaireAnswers 必须且只能包含 label 或 ignore");
        }
        validateLabels(command, interaction, answers.get("label"));
    }

    static boolean isRelayQuestionnaire(ChatInteractionRequest interaction) {
        return interaction != null && isRelayQuestionnaire(interaction.requestPayload());
    }

    static boolean isRelayQuestionnaire(Map<String, Object> payload) {
        return payload != null
                && SOURCE_TYPE.equals(String.valueOf(payload.get("sourceType")))
                && OPERATION_TYPE.equalsIgnoreCase(String.valueOf(payload.get("operation_type")));
    }

    private void validateIgnore(ChatInteractionResponseCommand command, Object ignore) {
        if (!Boolean.TRUE.equals(ignore)) {
            throw new IllegalArgumentException("Relay 问卷 ignore 必须为 true");
        }
        if (Boolean.TRUE.equals(command.approved())) {
            throw new IllegalArgumentException("忽略 Relay 问卷时 approved 必须为 false");
        }
    }

    private void validateLabels(ChatInteractionResponseCommand command,
                                ChatInteractionRequest interaction,
                                Object labelValue) {
        if (!Boolean.TRUE.equals(command.approved())) {
            throw new IllegalArgumentException("提交 Relay 问卷答案时 approved 必须为 true");
        }
        if (!(labelValue instanceof Map<?, ?> labels) || labels.isEmpty()) {
            throw new IllegalArgumentException("Relay 问卷 label 必须是非空 JSON object");
        }
        Map<String, Boolean> questionModes = questionModes(interaction.requestPayload());
        if (questionModes.isEmpty()) {
            throw new IllegalArgumentException("Relay 问卷请求缺少有效 questions");
        }
        for (Map.Entry<?, ?> entry : labels.entrySet()) {
            String question = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
            if (question.isBlank() || !questionModes.containsKey(question)) {
                throw new IllegalArgumentException("Relay 问卷回答包含未知问题: " + question);
            }
            validateAnswer(question, entry.getValue(), questionModes.get(question));
        }
    }

    private Map<String, Boolean> questionModes(Map<String, Object> payload) {
        Object questionsValue = payload == null ? null : payload.get("questions");
        if (!(questionsValue instanceof List<?> questions)) {
            return Map.of();
        }
        Map<String, Boolean> modes = new LinkedHashMap<>();
        for (Object questionValue : questions) {
            if (!(questionValue instanceof Map<?, ?> question)) {
                continue;
            }
            Object textValue = question.get("question");
            String text = textValue == null ? "" : String.valueOf(textValue);
            if (!text.isBlank()) {
                modes.put(text, Boolean.TRUE.equals(question.get("multi_select")));
            }
        }
        return Map.copyOf(modes);
    }

    private void validateAnswer(String question, Object answer, boolean multiSelect) {
        if (multiSelect) {
            if (!(answer instanceof List<?> selections) || selections.isEmpty()
                    || selections.stream().anyMatch(this::notText)) {
                throw new IllegalArgumentException("Relay 多选题答案必须是非空字符串数组: " + question);
            }
            return;
        }
        if (notText(answer)) {
            throw new IllegalArgumentException("Relay 单选题答案必须是非空字符串: " + question);
        }
    }

    private boolean notText(Object value) {
        return !(value instanceof String text) || text.isBlank();
    }
}
