/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

class RuntimeQuestionnaireAnswerValidatorTest {
    private final RuntimeQuestionnaireAnswerValidator validator = new RuntimeQuestionnaireAnswerValidator();

    @ParameterizedTest
    @ValueSource(strings = {"relay", "domain-agent"})
    void acceptsSingleMultiSelectAndCustomTextAnswers(String provider) {
        ChatInteractionResponseCommand command = command(true, Map.of(
                "label", Map.of(
                        "请选择技术方案", "用户自定义答案",
                        "请选择部署环境", List.of("开发环境", "测试环境"))));

        assertThatCode(() -> validator.validate(command, questionnaire(provider)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"relay", "domain-agent"})
    void acceptsQuestionnaireIgnore(String provider) {
        ChatInteractionResponseCommand command = command(false, Map.of("ignore", true));

        assertThatCode(() -> validator.validate(command, questionnaire(provider)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"relay", "domain-agent"})
    void rejectsLegacyFlatQuestionnaireAnswers(String provider) {
        ChatInteractionResponseCommand command = command(
                true, Map.of("请选择技术方案", "方案A"));

        assertThatThrownBy(() -> validator.validate(command, questionnaire(provider)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("问卷 questionnaireAnswers 必须且只能包含 label 或 ignore");
    }

    @ParameterizedTest
    @ValueSource(strings = {"relay", "domain-agent"})
    void rejectsLabelAndIgnoreCombination(String provider) {
        ChatInteractionResponseCommand command = command(true, Map.of(
                "label", Map.of("请选择技术方案", "方案A"),
                "ignore", true));

        assertThatThrownBy(() -> validator.validate(command, questionnaire(provider)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("问卷 questionnaireAnswers 必须且只能包含 label 或 ignore");
    }

    @ParameterizedTest
    @ValueSource(strings = {"relay", "domain-agent"})
    void rejectsAnswerTypeThatDoesNotMatchQuestionMode(String provider) {
        ChatInteractionResponseCommand command = command(true, Map.of(
                "label", Map.of("请选择部署环境", "开发环境")));

        assertThatThrownBy(() -> validator.validate(command, questionnaire(provider)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("多选题答案必须是非空字符串数组: 请选择部署环境");
    }

    @ParameterizedTest
    @ValueSource(strings = {"relay", "domain-agent"})
    void leavesOtherInteractionProtocolsUnchanged(String provider) {
        ChatInteractionResponseCommand command = command(
                true, Map.of("旧问题", "旧答案"));
        ChatInteractionRequest interaction = interaction(provider, Map.of("sourceType", "approval-request"));

        assertThatCode(() -> validator.validate(command, interaction))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"relay", "domain-agent"})
    void rejectsInvalidAnswersWithNeutralMessagesAndOriginalValidationOrder(String provider) {
        List<InvalidAnswer> cases = List.of(
                new InvalidAnswer(null, "invalid", Map.of(), "问卷回答 approved 不能为空"),
                new InvalidAnswer(true, "invalid", Map.of(), "问卷回答 scope 仅支持 once"),
                new InvalidAnswer(true, "once", Map.of(),
                        "问卷 questionnaireAnswers 必须且只能包含 label 或 ignore"),
                new InvalidAnswer(true, "once", Map.of("ignore", false), "问卷 ignore 必须为 true"),
                new InvalidAnswer(false, "once", Map.of("ignore", "true"), "问卷 ignore 必须为 true"),
                new InvalidAnswer(true, "once", Map.of("ignore", true), "忽略问卷时 approved 必须为 false"),
                new InvalidAnswer(false, "once", Map.of("label", Map.of()), "提交问卷答案时 approved 必须为 true"),
                new InvalidAnswer(true, "once", Map.of("label", Map.of()), "问卷 label 必须是非空 JSON object"),
                new InvalidAnswer(true, "once", Map.of("label", "text"), "问卷 label 必须是非空 JSON object"),
                new InvalidAnswer(true, "once", Map.of("label", Map.of("unknown", "x")),
                        "问卷回答包含未知问题: unknown"),
                new InvalidAnswer(true, "once", Map.of("label", Map.of("请选择技术方案", "  ")),
                        "单选题答案必须是非空字符串: 请选择技术方案"),
                new InvalidAnswer(true, "once", Map.of("label", Map.of("请选择技术方案", List.of("方案A"))),
                        "单选题答案必须是非空字符串: 请选择技术方案"),
                new InvalidAnswer(true, "once", Map.of("label", Map.of("请选择部署环境", List.of())),
                        "多选题答案必须是非空字符串数组: 请选择部署环境"),
                new InvalidAnswer(true, "once", Map.of("label", Map.of("请选择部署环境", List.of("开发环境", " "))),
                        "多选题答案必须是非空字符串数组: 请选择部署环境"));
        for (InvalidAnswer invalid : cases) {
            ChatInteractionResponseCommand command = command(invalid.approved(), invalid.scope(), invalid.answers());
            assertThatThrownBy(() -> validator.validate(command, questionnaire(provider)))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage(invalid.message());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"relay", "domain-agent"})
    void missingQuestionsKeepSameFailure(String provider) {
        ChatInteractionRequest interaction = interaction(provider, Map.of(
                "sourceType", "approval-request", "operation_type", "questionnaire"));
        assertThatThrownBy(() -> validator.validate(command(true, Map.of("label", Map.of("question", "answer"))), interaction))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("问卷请求缺少有效 questions");
    }

    @ParameterizedTest
    @ValueSource(strings = {"relay", "domain-agent"})
    void acceptsExistingScopeNormalization(String provider) {
        for (String scope : Arrays.asList(null, "", "  ", " OnCe ")) {
            assertThatCode(() -> validator.validate(command(false, scope, Map.of("ignore", true)), questionnaire(provider)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void questionnaireRecognitionKeepsNullAndCaseRules() {
        assertThat(RuntimeQuestionnaireAnswerValidator.isQuestionnaire((ChatInteractionRequest) null)).isFalse();
        assertThat(RuntimeQuestionnaireAnswerValidator.isQuestionnaire((Map<String, Object>) null)).isFalse();
        assertThat(RuntimeQuestionnaireAnswerValidator.isQuestionnaire(Map.of())).isFalse();
        assertThat(RuntimeQuestionnaireAnswerValidator.isQuestionnaire(Map.of(
                "sourceType", "approval-request", "operation_type", "QUESTIONNAIRE"))).isTrue();
        assertThat(RuntimeQuestionnaireAnswerValidator.isQuestionnaire(Map.of(
                "sourceType", "APPROVAL-REQUEST", "operation_type", "questionnaire"))).isFalse();
        assertThat(RuntimeQuestionnaireAnswerValidator.isQuestionnaire(Map.of(
                "sourceType", "approval-request", "operation_type", " questionnaire "))).isFalse();
    }

    private ChatInteractionResponseCommand command(
            boolean approved,
            Map<String, Object> questionnaireAnswers) {
        return command(approved, "once", questionnaireAnswers);
    }

    private ChatInteractionResponseCommand command(
            Boolean approved, String scope, Map<String, Object> questionnaireAnswers) {
        return new ChatInteractionResponseCommand(
                new UserContext("tenant1", "user1", "User One"),
                "interaction1",
                approved,
                scope,
                questionnaireAnswers,
                Map.of());
    }

    private ChatInteractionRequest questionnaire(String provider) {
        return interaction(provider, Map.of(
                "source", provider,
                "sourceType", "approval-request",
                "operation_type", "questionnaire",
                "approval_id", "approval-1",
                "questions", List.of(
                        Map.of("question", "请选择技术方案", "multi_select", false),
                        Map.of("question", "请选择部署环境", "multi_select", true))));
    }

    private ChatInteractionRequest interaction(String provider, Map<String, Object> requestPayload) {
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
                provider,
                "binding1",
                "runtime-session-1",
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

    private record InvalidAnswer(Boolean approved, String scope, Map<String, Object> answers, String message) {
    }
}
