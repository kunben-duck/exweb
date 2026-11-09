/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在 Interaction claim 前校验 Runtime 问卷回答，供 DomainAgent 和 Relay 共用，避免非法答案消耗等待态。
 */
final class RuntimeQuestionnaireAnswerValidator {
    private static final String SOURCE_TYPE = "approval-request";
    private static final String OPERATION_TYPE = "questionnaire";
    private static final String IGNORED_QUESTIONS = "ignoredQuestions";
    private final Clock clock;

    RuntimeQuestionnaireAnswerValidator() {
        this(Clock.systemUTC());
    }

    RuntimeQuestionnaireAnswerValidator(Clock clock) {
        this.clock = clock;
    }

    void validate(ChatInteractionResponseCommand command, ChatInteractionRequest interaction) {
        if (!isQuestionnaire(interaction)) {
            return;
        }
        if (command.approved() == null) {
            throw new IllegalArgumentException("问卷回答 approved 不能为空");
        }
        if (command.scope() != null && !command.scope().isBlank()
                && !"once".equalsIgnoreCase(command.scope().trim())) {
            throw new IllegalArgumentException("问卷回答 scope 仅支持 once");
        }
        Map<String, Object> answers = command.questionnaireAnswers();
        if (answers != null && answers.containsKey(IGNORED_QUESTIONS)) {
            validateDetailedIgnore(command, interaction, answers);
            return;
        }
        if (answers == null || answers.size() != 1) {
            throw new IllegalArgumentException(
                    "问卷 questionnaireAnswers 必须且只能包含 label 或 ignore");
        }
        if (answers.containsKey("ignore")) {
            validateIgnore(command, answers.get("ignore"));
            return;
        }
        if (!answers.containsKey("label")) {
            throw new IllegalArgumentException(
                    "问卷 questionnaireAnswers 必须且只能包含 label 或 ignore");
        }
        validateLabels(command, interaction, answers.get("label"));
    }

    private void validateDetailedIgnore(ChatInteractionResponseCommand command,
                                       ChatInteractionRequest interaction,
                                       Map<String, Object> answers) {
        if (!"domain-agent".equals(interaction.runtimeProvider()) && !"relay".equals(interaction.runtimeProvider())) {
            throw new IllegalArgumentException("逐题忽略仅支持 DomainAgent 和 Relay 问卷");
        }
        if (!Set.of("label", "ignore", IGNORED_QUESTIONS).containsAll(answers.keySet())) {
            throw new IllegalArgumentException("问卷 questionnaireAnswers 包含未知字段");
        }
        Map<String, Boolean> modes = questionModes(interaction.requestPayload());
        if (modes.isEmpty()) {
            throw new IllegalArgumentException("问卷请求缺少有效 questions");
        }
        Set<String> ignored = validateIgnoredQuestions(answers.get(IGNORED_QUESTIONS), modes, interaction);
        if (answers.containsKey("ignore")) {
            validateIgnore(command, answers.get("ignore"));
            if (answers.containsKey("label") || !ignored.equals(modes.keySet())) {
                throw new IllegalArgumentException("整份忽略不能包含 label，忽略明细必须覆盖全部问题");
            }
            return;
        }
        if (!Boolean.TRUE.equals(command.approved())) {
            throw new IllegalArgumentException("提交逐题结果时 approved 必须为 true");
        }
        validateDetailedLabels(command, interaction, answers, ignored);
    }

    private Set<String> validateIgnoredQuestions(Object value, Map<String, Boolean> modes,
                                                ChatInteractionRequest interaction) {
        if (!(value instanceof List<?> details) || details.isEmpty() || details.size() > modes.size()) {
            throw new IllegalArgumentException("问卷 ignoredQuestions 必须是非空数组，且数量不能超过问题数");
        }
        Set<String> ignored = new HashSet<>();
        boolean hasTimeout = false;
        for (Object detail : details) {
            if (!(detail instanceof Map<?, ?> entry)
                    || !entry.keySet().equals(Set.of("question", "reason"))) {
                throw new IllegalArgumentException("忽略明细必须且只能包含 question 和 reason");
            }
            if (!(entry.get("question") instanceof String question) || question.isBlank()
                    || !modes.containsKey(question) || !ignored.add(question)) {
                throw new IllegalArgumentException("忽略明细包含无效、未知或重复的问题");
            }
            Object reason = entry.get("reason");
            if (!"USER".equals(reason) && !"TIMEOUT".equals(reason)) {
                throw new IllegalArgumentException("忽略原因仅支持 USER 或 TIMEOUT");
            }
            hasTimeout |= "TIMEOUT".equals(reason);
        }
        if (hasTimeout) {
            validateTimeout(interaction.requestPayload());
        }
        return ignored;
    }

    private void validateTimeout(Map<String, Object> payload) {
        if (!RelayQuestionnaireWaitPolicy.AUTO_ACTION_TYPE.equals(payload.get("autoActionType"))
                || !(payload.get("autoActionAt") instanceof String deadline)) {
            throw new IllegalArgumentException("问卷未配置有效的自动忽略时间");
        }
        Instant autoActionAt;
        try {
            autoActionAt = Instant.parse(deadline);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("问卷未配置有效的自动忽略时间");
        }
        if (clock.instant().isBefore(autoActionAt)) {
            throw new IllegalArgumentException("尚未到达问卷自动忽略时间");
        }
    }

    private void validateDetailedLabels(ChatInteractionResponseCommand command,
                                        ChatInteractionRequest interaction,
                                        Map<String, Object> answers, Set<String> ignored) {
        if (!answers.containsKey("label")) {
            return;
        }
        if (!(answers.get("label") instanceof Map<?, ?> labels)) {
            throw new IllegalArgumentException("问卷 label 必须是 JSON object");
        }
        if (labels.isEmpty()) {
            return;
        }
        validateLabels(command, interaction, labels);
        if (labels.keySet().stream().anyMatch(ignored::contains)) {
            throw new IllegalArgumentException("同一道题不能同时回答和忽略");
        }
    }

    static boolean isQuestionnaire(ChatInteractionRequest interaction) {
        return interaction != null && isQuestionnaire(interaction.requestPayload());
    }

    static boolean isQuestionnaire(Map<String, Object> payload) {
        return payload != null
                && SOURCE_TYPE.equals(String.valueOf(payload.get("sourceType")))
                && OPERATION_TYPE.equalsIgnoreCase(String.valueOf(payload.get("operation_type")));
    }

    private void validateIgnore(ChatInteractionResponseCommand command, Object ignore) {
        if (!Boolean.TRUE.equals(ignore)) {
            throw new IllegalArgumentException("问卷 ignore 必须为 true");
        }
        if (Boolean.TRUE.equals(command.approved())) {
            throw new IllegalArgumentException("忽略问卷时 approved 必须为 false");
        }
    }

    private void validateLabels(ChatInteractionResponseCommand command,
                                ChatInteractionRequest interaction,
                                Object labelValue) {
        if (!Boolean.TRUE.equals(command.approved())) {
            throw new IllegalArgumentException("提交问卷答案时 approved 必须为 true");
        }
        if (!(labelValue instanceof Map<?, ?> labels) || labels.isEmpty()) {
            throw new IllegalArgumentException("问卷 label 必须是非空 JSON object");
        }
        Map<String, Boolean> questionModes = questionModes(interaction.requestPayload());
        if (questionModes.isEmpty()) {
            throw new IllegalArgumentException("问卷请求缺少有效 questions");
        }
        for (Map.Entry<?, ?> entry : labels.entrySet()) {
            String question = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
            if (question.isBlank() || !questionModes.containsKey(question)) {
                throw new IllegalArgumentException("问卷回答包含未知问题: " + question);
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
                throw new IllegalArgumentException("多选题答案必须是非空字符串数组: " + question);
            }
            return;
        }
        if (notText(answer)) {
            throw new IllegalArgumentException("单选题答案必须是非空字符串: " + question);
        }
    }

    private boolean notText(Object value) {
        return !(value instanceof String text) || text.isBlank();
    }
}
