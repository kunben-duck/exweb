package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.domain.auth.UserContext;
import java.util.Map;

/**
 * 用户提交澄清/审批响应的应用命令。
 */
public record ChatInteractionResponseCommand(
        UserContext user,
        String interactionId,
        Boolean approved,
        String scope,
        Map<String, Object> questionnaireAnswers,
        Map<String, Object> metadata,
        String sessionId,
        String appId,
        String appName
) {
    /** 兼容不携带会话 App Tag 的内部调用。 */
    public ChatInteractionResponseCommand(UserContext user, String interactionId, Boolean approved, String scope,
                                          Map<String, Object> questionnaireAnswers, Map<String, Object> metadata) {
        this(user, interactionId, approved, scope, questionnaireAnswers, metadata, null, null, null);
    }

    public ChatInteractionResponseCommand {
        questionnaireAnswers = questionnaireAnswers == null ? Map.of() : Map.copyOf(questionnaireAnswers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        sessionId = normalize(sessionId);
        appId = normalize(appId);
        appName = normalize(appName);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
