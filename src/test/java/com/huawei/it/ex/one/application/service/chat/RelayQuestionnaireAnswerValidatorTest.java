/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class RelayQuestionnaireAnswerValidatorTest {
    private final RelayQuestionnaireAnswerValidator validator = new RelayQuestionnaireAnswerValidator();

    @Test
    void acceptsSingleMultiSelectAndCustomTextAnswers() {
        ChatInteractionResponseCommand command = command(true, Map.of(
                "label", Map.of(
                        "请选择技术方案", "用户自定义答案",
                        "请选择部署环境", List.of("开发环境", "测试环境"))));

        assertThatCode(() -> validator.validate(command, questionnaire()))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsQuestionnaireIgnore() {
        ChatInteractionResponseCommand command = command(false, Map.of("ignore", true));

        assertThatCode(() -> validator.validate(command, questionnaire()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsLegacyFlatQuestionnaireAnswers() {
        ChatInteractionResponseCommand command = command(
                true, Map.of("请选择技术方案", "方案A"));

        assertThatThrownBy(() -> validator.validate(command, questionnaire()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("label 或 ignore");
    }

    @Test
    void rejectsLabelAndIgnoreCombination() {
        ChatInteractionResponseCommand command = command(true, Map.of(
                "label", Map.of("请选择技术方案", "方案A"),
                "ignore", true));

        assertThatThrownBy(() -> validator.validate(command, questionnaire()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能包含 label 或 ignore");
    }

    @Test
    void rejectsAnswerTypeThatDoesNotMatchQuestionMode() {
        ChatInteractionResponseCommand command = command(true, Map.of(
                "label", Map.of("请选择部署环境", "开发环境")));

        assertThatThrownBy(() -> validator.validate(command, questionnaire()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多选题答案必须是非空字符串数组");
    }

    @Test
    void leavesOtherInteractionProtocolsUnchanged() {
        ChatInteractionResponseCommand command = command(
                true, Map.of("旧问题", "旧答案"));
        ChatInteractionRequest interaction = interaction(Map.of("sourceType", "approval-request"));

        assertThatCode(() -> validator.validate(command, interaction))
                .doesNotThrowAnyException();
    }

    private ChatInteractionResponseCommand command(
            boolean approved,
            Map<String, Object> questionnaireAnswers) {
        return new ChatInteractionResponseCommand(
                new UserContext("tenant1", "user1", "User One"),
                "interaction1",
                approved,
                "once",
                questionnaireAnswers,
                Map.of());
    }

    private ChatInteractionRequest questionnaire() {
        return interaction(Map.of(
                "sourceType", "approval-request",
                "operation_type", "questionnaire",
                "approval_id", "approval-1",
                "questions", List.of(
                        Map.of("question", "请选择技术方案", "multi_select", false),
                        Map.of("question", "请选择部署环境", "multi_select", true))));
    }

    private ChatInteractionRequest interaction(Map<String, Object> requestPayload) {
        Instant now = Instant.now();
        return new ChatInteractionRequest(
                "interaction1",
                "tenant1",
                "user1",
                "session1",
                "run-source",
                null,
                "msg-user",
                "msg-assistant",
                "relay",
                "binding1",
                "relay-session-1",
                "approval-1",
                ChatInteractionType.AGENT_CLARIFICATION,
                ChatInteractionStatus.WAITING,
                requestPayload,
                Map.of(),
                now.plusSeconds(3600),
                null,
                null,
                now,
                now);
    }
}
