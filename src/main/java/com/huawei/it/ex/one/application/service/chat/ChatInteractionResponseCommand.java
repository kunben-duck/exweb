package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;

import java.util.List;
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
        String appName,
        List<AttachmentRef> attachments,
        AgentModeProfile agentMode,
        String targetType,
        String targetId,
        String interactionAction,
        String channel,
        String intentAccessName
) {
    /** 兼容尚未携带Intent入口名称的完整内部命令。 */
    public ChatInteractionResponseCommand(
            UserContext user, String interactionId, Boolean approved, String scope,
            Map<String, Object> questionnaireAnswers, Map<String, Object> metadata,
            String sessionId, String appId, String appName, List<AttachmentRef> attachments,
            AgentModeProfile agentMode, String targetType, String targetId, String interactionAction,
            String channel) {
        this(user, interactionId, approved, scope, questionnaireAnswers, metadata,
                sessionId, appId, appName, attachments, agentMode, targetType, targetId, interactionAction,
                channel, null);
    }

    /** 兼容尚未携带会话 channel 的完整内部命令。 */
    public ChatInteractionResponseCommand(
            UserContext user, String interactionId, Boolean approved, String scope,
            Map<String, Object> questionnaireAnswers, Map<String, Object> metadata,
            String sessionId, String appId, String appName, List<AttachmentRef> attachments,
            AgentModeProfile agentMode, String targetType, String targetId, String interactionAction) {
        this(user, interactionId, approved, scope, questionnaireAnswers, metadata,
                sessionId, appId, appName, attachments, agentMode, targetType, targetId, interactionAction,
                null, null);
    }

    /** 兼容尚未携带 AMBIGUOUS_ROUTE 选择字段的完整内部命令。 */
    public ChatInteractionResponseCommand(
            UserContext user, String interactionId, Boolean approved, String scope,
            Map<String, Object> questionnaireAnswers, Map<String, Object> metadata,
            String sessionId, String appId, String appName, List<AttachmentRef> attachments,
            AgentModeProfile agentMode) {
        this(user, interactionId, approved, scope, questionnaireAnswers, metadata,
                sessionId, appId, appName, attachments, agentMode, null, null, null, null, null);
    }

    public ChatInteractionResponseCommand(
            UserContext user, String interactionId, Boolean approved, String scope,
            Map<String, Object> questionnaireAnswers, Map<String, Object> metadata,
            String sessionId, String appId, String appName, List<AttachmentRef> attachments) {
        this(user, interactionId, approved, scope, questionnaireAnswers, metadata,
                sessionId, appId, appName, attachments, null, null, null, null, null, null);
    }

    /** 兼容不携带会话 App Tag 的内部调用。 */
    public ChatInteractionResponseCommand(UserContext user, String interactionId, Boolean approved, String scope,
                                          Map<String, Object> questionnaireAnswers, Map<String, Object> metadata) {
        this(user, interactionId, approved, scope, questionnaireAnswers, metadata,
                null, null, null, List.of(), null, null, null, null, null, null);
    }

    /** 兼容不携带附件的 App Tag 续接调用。 */
    public ChatInteractionResponseCommand(UserContext user, String interactionId, Boolean approved, String scope,
                                          Map<String, Object> questionnaireAnswers, Map<String, Object> metadata,
                                          String sessionId, String appId, String appName) {
        this(user, interactionId, approved, scope, questionnaireAnswers, metadata,
                sessionId, appId, appName, List.of(), null, null, null, null, null, null);
    }

    public ChatInteractionResponseCommand {
        questionnaireAnswers = questionnaireAnswers == null ? Map.of() : Map.copyOf(questionnaireAnswers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        sessionId = normalize(sessionId);
        appId = normalize(appId);
        appName = normalize(appName);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        targetType = normalize(targetType);
        targetId = normalize(targetId);
        interactionAction = normalize(interactionAction);
        channel = normalize(channel);
        intentAccessName = normalize(intentAccessName);
        if (intentAccessName != null && intentAccessName.length() > 128) {
            throw new IllegalArgumentException("intentAccessName 长度不能超过 128");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
