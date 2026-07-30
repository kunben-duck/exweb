package com.huawei.it.ex.one.domain.chat;

import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;

import java.time.Instant;

/**
 * 前端页面初始化时读取的会话流状态。
 *
 * @param sessionId 前端聊天会话标识。
 * @param latestSeq 当前会话最大事件序号。
 * @param activeRunId 当前仍在运行或取消中的 run 标识。
 * @param activeRunStatus 当前 active run 状态。
 * @param activeStreamTopicId 当前 active run 对应的 WebSocket stream topic；无 active run 时为空。
 * @param activeRunFirstSeq 当前 active run 的首个事件序号；无 active run 或尚未落 run.started 时为空。
 * @param activeRunLastSeq 当前 active run 最近一个已持久化事件序号；无 active run 或尚无事件时为空。
 * @param cancellable 当前 active run 是否可通过 stop 接口取消。
 * @param waitingUserInput 当前会话是否存在等待用户输入请求。
 * @param interactionId 当前等待请求 ID。
 * @param interactionType 等待类型。
 * @param assistantMessageId 承载等待卡片的 assistant 消息 ID。
 * @param expiresAt 等待请求过期时间。
 * @param autoSelectAt AMBIGUOUS_ROUTE 前端提交代选的截止时间；其他等待类型为空。
 * @param autoSelectTimeoutMs AMBIGUOUS_ROUTE 前端建议等待毫秒数；其他等待类型为空。
 * @param bindingProvider 当前会话 active binding provider。
 * @param bindingTargetType 当前绑定目标类型。
 * @param bindingTargetId 当前绑定目标 ID。
 * @param bindingIntentCode 当前绑定来源意图编码。
 * @param bindingIntentName 当前绑定来源意图名称。
 * @param bindingRouteSource 当前绑定来源。
 * @param bindingUpdatedAt 当前绑定更新时间。
 * @param bindingAgentMode 当前 active DomainAgent binding 记录的模式快照；其他情况为空。
 */
public record ChatStreamStatus(
        String sessionId,
        long latestSeq,
        String activeRunId,
        ChatRunStatus activeRunStatus,
        String activeStreamTopicId,
        Long activeRunFirstSeq,
        Long activeRunLastSeq,
        boolean cancellable,
        boolean waitingUserInput,
        String interactionId,
        String interactionType,
        String assistantMessageId,
        Instant expiresAt,
        Instant autoSelectAt,
        Long autoSelectTimeoutMs,
        String bindingProvider,
        String bindingTargetType,
        String bindingTargetId,
        String bindingIntentCode,
        String bindingIntentName,
        String bindingRouteSource,
        Instant bindingUpdatedAt,
        AgentModeProfile bindingAgentMode
) {
    /** 兼容尚未返回 Agent 模式的内部构造调用。 */
    public ChatStreamStatus(
            String sessionId, long latestSeq, String activeRunId, ChatRunStatus activeRunStatus,
            String activeStreamTopicId, Long activeRunFirstSeq, Long activeRunLastSeq, boolean cancellable,
            boolean waitingUserInput, String interactionId, String interactionType, String assistantMessageId,
            Instant expiresAt, String bindingProvider, String bindingTargetType, String bindingTargetId,
            String bindingIntentCode, String bindingIntentName, String bindingRouteSource, Instant bindingUpdatedAt) {
        this(sessionId, latestSeq, activeRunId, activeRunStatus, activeStreamTopicId, activeRunFirstSeq,
                activeRunLastSeq, cancellable, waitingUserInput, interactionId, interactionType,
                assistantMessageId, expiresAt, null, null, bindingProvider, bindingTargetType, bindingTargetId,
                bindingIntentCode, bindingIntentName, bindingRouteSource, bindingUpdatedAt, null);
    }

    /** 兼容尚未返回自动选择字段但已经返回 Agent 模式的内部构造调用。 */
    public ChatStreamStatus(
            String sessionId, long latestSeq, String activeRunId, ChatRunStatus activeRunStatus,
            String activeStreamTopicId, Long activeRunFirstSeq, Long activeRunLastSeq, boolean cancellable,
            boolean waitingUserInput, String interactionId, String interactionType, String assistantMessageId,
            Instant expiresAt, String bindingProvider, String bindingTargetType, String bindingTargetId,
            String bindingIntentCode, String bindingIntentName, String bindingRouteSource, Instant bindingUpdatedAt,
            AgentModeProfile bindingAgentMode) {
        this(sessionId, latestSeq, activeRunId, activeRunStatus, activeStreamTopicId, activeRunFirstSeq,
                activeRunLastSeq, cancellable, waitingUserInput, interactionId, interactionType,
                assistantMessageId, expiresAt, null, null, bindingProvider, bindingTargetType, bindingTargetId,
                bindingIntentCode, bindingIntentName, bindingRouteSource, bindingUpdatedAt, bindingAgentMode);
    }
}
