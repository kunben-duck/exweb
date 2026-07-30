package com.huawei.it.ex.one.domain.chat;

import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;

import java.util.List;
import java.util.Map;

/**
 * 聊天用例的统一输入命令。
 *
 * <p>正式版只有一个提问入口，接口层会把 {@code /chat/runs} 请求转换成该命令。
 * WebSocket 只负责订阅当前页面新建 run 的后台输出；Event Resume 负责恢复链路，其中会话级事件恢复
 * 做有限补发，run 级事件恢复可接续 active run 到终态。因此命令不再携带传输协议、
 * 前端消息类型或前端响应模式。</p>
 *
 * @param commandId 前端或调用方生成的命令标识，用于幂等和排障。
 * @param tenantId 租户标识；进入 application 后会被服务端身份上下文覆盖。
 * @param userId 用户标识；进入 application 后会被服务端身份上下文覆盖。
 * @param sessionId 前端聊天会话标识。
 * @param conversationId 前端对话标识，通常与 sessionId 一致或为空。
 * @param channel 请求来源渠道，当前正式版固定为 web，保留用于会话审计。
 * @param message 本轮用户输入文本。
 * @param attachments 本轮关联附件引用。
 * @param metadata 前端或上游传入的扩展元数据。
 * @param targetType 显式直连目标类型；为空时走普通路由。
 * @param targetId 显式直连目标 ID。
 * @param runMode 本轮消息树写入模式。
 * @param parentMessageId 普通继续提问时显式指定的父节点；为空时使用会话 current leaf。
 * @param editedMessageId 编辑历史 user 消息时被编辑的原消息。
 * @param regeneratedMessageId 重新生成 assistant 回复时被重新生成的原回答。
 * @param routeTrigger 内部路由触发原因；当前由 ChatService 自动生成，前端通过 forceReroute 触发 user_correction。
 * @param interactionId CONTINUE_INTERACTION 模式续接的 Interaction 请求 ID。
 * @param approved 审批、确认或切换确认结果；澄清类可省略。
 * @param scope 授权或确认范围；澄清类默认 once。
 * @param questionnaireAnswers 澄清问题答案。
 * @param appId 会话所属应用标识，只用于会话创建和一致性校验。
 * @param appName 会话所属应用名称快照，只用于会话创建和一致性校验。
 * @param agentMode 可选 Agent 模式完整快照；null 表示本轮未提交，空 selections 表示显式清除。
 * @param interactionAction Interaction 专用动作；当前仅 AMBIGUOUS_ROUTE 支持 AUTO_SELECT。
 */
public record ChatCommand(
        String commandId,
        String tenantId,
        String userId,
        String sessionId,
        String conversationId,
        String channel,
        String message,
        List<AttachmentRef> attachments,
        Map<String, Object> metadata,
        String targetType,
        String targetId,
        ChatRunMode runMode,
        String parentMessageId,
        String editedMessageId,
        String regeneratedMessageId,
        String routeTrigger,
        String interactionId,
        Boolean approved,
        String scope,
        Map<String, Object> questionnaireAnswers,
        String appId,
        String appName,
        AgentModeProfile agentMode,
        String interactionAction
) {
    /** 兼容尚未携带 Interaction 动作的完整命令构造器。 */
    public ChatCommand(
            String commandId, String tenantId, String userId, String sessionId, String conversationId,
            String channel, String message, List<AttachmentRef> attachments, Map<String, Object> metadata,
            String targetType, String targetId, ChatRunMode runMode, String parentMessageId,
            String editedMessageId, String regeneratedMessageId, String routeTrigger, String interactionId,
            Boolean approved, String scope, Map<String, Object> questionnaireAnswers, String appId, String appName,
            AgentModeProfile agentMode) {
        this(commandId, tenantId, userId, sessionId, conversationId, channel, message, attachments, metadata,
                targetType, targetId, runMode, parentMessageId, editedMessageId, regeneratedMessageId,
                routeTrigger, interactionId, approved, scope, questionnaireAnswers, appId, appName, agentMode, null);
    }

    /** 兼容尚未携带 Agent 模式的完整命令构造器。 */
    public ChatCommand(
            String commandId, String tenantId, String userId, String sessionId, String conversationId,
            String channel, String message, List<AttachmentRef> attachments, Map<String, Object> metadata,
            String targetType, String targetId, ChatRunMode runMode, String parentMessageId,
            String editedMessageId, String regeneratedMessageId, String routeTrigger, String interactionId,
            Boolean approved, String scope, Map<String, Object> questionnaireAnswers, String appId, String appName) {
        this(commandId, tenantId, userId, sessionId, conversationId, channel, message, attachments, metadata,
                targetType, targetId, runMode, parentMessageId, editedMessageId, regeneratedMessageId,
                routeTrigger, interactionId, approved, scope, questionnaireAnswers, appId, appName, null, null);
    }

    /** 兼容尚未携带 App Tag 的完整命令构造器。 */
    public ChatCommand(
            String commandId, String tenantId, String userId, String sessionId, String conversationId,
            String channel, String message, List<AttachmentRef> attachments, Map<String, Object> metadata,
            String targetType, String targetId, ChatRunMode runMode, String parentMessageId,
            String editedMessageId, String regeneratedMessageId, String routeTrigger, String interactionId,
            Boolean approved, String scope, Map<String, Object> questionnaireAnswers) {
        this(commandId, tenantId, userId, sessionId, conversationId, channel, message, attachments, metadata,
                targetType, targetId, runMode, parentMessageId, editedMessageId, regeneratedMessageId,
                routeTrigger, interactionId, approved, scope, questionnaireAnswers, null, null, null, null);
    }

    /**
     * 兼容普通继续提问的便捷构造器。
     */
    public ChatCommand(String commandId, String tenantId, String userId, String sessionId, String conversationId,
                       String channel, String message, List<AttachmentRef> attachments, Map<String, Object> metadata) {
        this(commandId, tenantId, userId, sessionId, conversationId, channel, message, attachments, metadata,
                null, null,
                ChatRunMode.NEXT, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 兼容已有消息树写入模式调用点；显式直连目标仅由接口层新字段传入。
     */
    public ChatCommand(String commandId, String tenantId, String userId, String sessionId, String conversationId,
                       String channel, String message, List<AttachmentRef> attachments, Map<String, Object> metadata,
                       ChatRunMode runMode, String parentMessageId, String editedMessageId, String regeneratedMessageId) {
        this(commandId, tenantId, userId, sessionId, conversationId, channel, message, attachments, metadata,
                null, null, runMode, parentMessageId, editedMessageId, regeneratedMessageId,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * 兼容显式直连目标和消息树写入模式的内部调用点。
     */
    public ChatCommand(String commandId, String tenantId, String userId, String sessionId, String conversationId,
                       String channel, String message, List<AttachmentRef> attachments, Map<String, Object> metadata,
                       String targetType, String targetId, ChatRunMode runMode, String parentMessageId,
                       String editedMessageId, String regeneratedMessageId) {
        this(commandId, tenantId, userId, sessionId, conversationId, channel, message, attachments, metadata,
                targetType, targetId, runMode, parentMessageId, editedMessageId, regeneratedMessageId,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * 兼容显式路由触发原因的内部调用点。
     */
    public ChatCommand(String commandId, String tenantId, String userId, String sessionId, String conversationId,
                       String channel, String message, List<AttachmentRef> attachments, Map<String, Object> metadata,
                       String targetType, String targetId, ChatRunMode runMode, String parentMessageId,
                       String editedMessageId, String regeneratedMessageId, String routeTrigger) {
        this(commandId, tenantId, userId, sessionId, conversationId, channel, message, attachments, metadata,
                targetType, targetId, runMode, parentMessageId, editedMessageId, regeneratedMessageId,
                routeTrigger, null, null, null, null, null, null, null, null);
    }

    public ChatCommand {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        targetType = targetType == null || targetType.isBlank() ? null : targetType.trim();
        targetId = targetId == null || targetId.isBlank() ? null : targetId.trim();
        runMode = runMode == null ? ChatRunMode.NEXT : runMode;
        routeTrigger = routeTrigger == null || routeTrigger.isBlank() ? null : routeTrigger.trim();
        interactionId = interactionId == null || interactionId.isBlank() ? null : interactionId.trim();
        interactionAction = interactionAction == null || interactionAction.isBlank()
                ? null
                : interactionAction.trim();
        scope = scope == null || scope.isBlank() ? null : scope.trim();
        questionnaireAnswers = questionnaireAnswers == null ? Map.of() : Map.copyOf(questionnaireAnswers);
        appId = normalizeTag(appId);
        appName = normalizeTag(appName);
        if (appId == null && appName != null) {
            throw new IllegalArgumentException("appName 不能脱离 appId 单独使用");
        }
        if (appId != null && appId.length() > 128) {
            throw new IllegalArgumentException("appId 长度不能超过 128");
        }
        if (appName != null && appName.length() > 256) {
            throw new IllegalArgumentException("appName 长度不能超过 256");
        }
    }

    private static String normalizeTag(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
