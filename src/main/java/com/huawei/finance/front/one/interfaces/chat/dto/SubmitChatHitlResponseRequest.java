package com.huawei.finance.front.one.interfaces.chat.dto;

import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 前端提交澄清/审批响应的请求体。
 *
 * @param approved 是否同意或确认继续；questionnaire 澄清场景通常传 true。
 * @param scope 授权或确认范围，首版 Relay questionnaire 默认使用 once。
 * @param questionnaireAnswers 澄清问题答案，key 为 Relay 下发的问题文案。
 * @param metadata 前端透传的非敏感扩展信息。
 */
public record SubmitChatHitlResponseRequest(
        Boolean approved,
        @Size(max = 32, message = "scope 长度不能超过 32")
        String scope,
        @Size(max = 50, message = "questionnaireAnswers 最多允许 50 个字段")
        Map<String, Object> questionnaireAnswers,
        @Size(max = 50, message = "metadata 最多允许 50 个字段")
        Map<String, Object> metadata
) {
    public Map<String, Object> safeQuestionnaireAnswers() {
        return copy(questionnaireAnswers);
    }

    public Map<String, Object> safeMetadata() {
        return copy(metadata);
    }

    private static Map<String, Object> copy(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(value));
    }
}
