package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.domain.auth.UserContext;
import java.util.Map;

/**
 * 用户提交澄清/审批响应的应用命令。
 */
public record ChatHitlResponseCommand(
        UserContext user,
        String hitlRequestId,
        boolean approved,
        String scope,
        Map<String, Object> questionnaireAnswers,
        Map<String, Object> metadata
) {
    public ChatHitlResponseCommand {
        questionnaireAnswers = questionnaireAnswers == null ? Map.of() : Map.copyOf(questionnaireAnswers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
