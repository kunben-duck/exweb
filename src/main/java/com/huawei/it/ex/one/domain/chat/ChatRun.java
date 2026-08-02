package com.huawei.it.ex.one.domain.chat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单轮聊天 run 的事实源快照。
 *
 * <p>ChatRun 描述一次用户提问从开始、流式输出、取消/完成/失败到结束的生命周期。
 * 它不是多轮会话；多轮上下文仍由 ChatSession 与 RuntimeBinding 管理。</p>
 *
 * @param id 本轮 run 标识。
 * @param tenantId 租户标识，来自服务端身份上下文。
 * @param userId 用户标识，来自服务端身份上下文。
 * @param sessionId 前端聊天会话标识。
 * @param status run 生命周期状态。
 * @param routeType 本轮路由类型，例如 DOMAIN_AGENT、AGENT_RUNTIME、SYSTEM_RESPONSE。
 * @param agentCode 本轮命中的 DomainAgent 编码，可为空。
 * @param runtimeProvider 本轮使用的 AgentRuntime provider，可为空。
 * @param runtimeSessionId AgentRuntime 实际会话标识，可为空；Relay 会在 session-ready 后回填真实值。
 * @param runMode 本轮消息树写入模式。
 * @param parentMessageId 本轮 run 挂接的消息树父节点。
 * @param userMessageId 本轮输入对应的用户消息；重新生成时指向原用户消息。
 * @param assistantMessageId run.completed、run.waiting_user 或用户主动 stop 后生成的 assistant 消息。
 * @param firstSeq run.started 持久化后的事件序号。
 * @param lastSeq 该 run 当前最后一个已持久化事件序号。
 * @param cancelReason stop 接口传入或系统生成的取消原因。
 * @param startedAt run 开始执行时间。
 * @param finishedAt run 进入终态时间。
 * @param metadata run 扩展诊断元数据。
 * @param createdAt 记录创建时间。
 * @param updatedAt 记录最后更新时间。
 */
public record ChatRun(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        ChatRunStatus status,
        String routeType,
        String agentCode,
        String runtimeProvider,
        String runtimeSessionId,
        ChatRunMode runMode,
        String parentMessageId,
        String userMessageId,
        String assistantMessageId,
        Long firstSeq,
        Long lastSeq,
        String cancelReason,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * 创建普通 NEXT run。
     *
     * <p>该构造器适用于不需要编辑/重新生成语义的普通提问；消息树增强场景使用完整构造器。</p>
     */
    public ChatRun(String id, String tenantId, String userId, String sessionId, ChatRunStatus status,
                   String routeType, String agentCode, String runtimeProvider, String runtimeSessionId,
                   Long firstSeq, Long lastSeq, String cancelReason, Instant startedAt, Instant finishedAt,
                   Map<String, Object> metadata, Instant createdAt, Instant updatedAt) {
        this(id, tenantId, userId, sessionId, status, routeType, agentCode, runtimeProvider, runtimeSessionId,
                ChatRunMode.NEXT, null, null, null, firstSeq, lastSeq, cancelReason, startedAt, finishedAt,
                metadata, createdAt, updatedAt);
    }

    public ChatRun {
        status = status == null ? ChatRunStatus.RUNNING : status;
        runMode = runMode == null ? ChatRunMode.NEXT : runMode;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * @return 当前 run 是否仍可被 stop 接口取消。
     */
    public boolean cancellable() {
        return status.cancellable();
    }

    /**
     * @return 当前 run 是否允许首次 stop 或重试尚未完成的 stop。
     */
    public boolean stopRetryable() {
        return status.stopRetryable();
    }

    /**
     * 记录 run.started 的持久化序号。
     */
    public ChatRun withFirstSeq(long sequence) {
        Long persistedSequence = Long.valueOf(sequence);
        return new ChatRun(id, tenantId, userId, sessionId, status, routeType, agentCode, runtimeProvider,
                runtimeSessionId, runMode, parentMessageId, userMessageId, assistantMessageId,
                firstSeq == null ? persistedSequence : firstSeq, persistedSequence,
                cancelReason, startedAt, finishedAt,
                metadata, createdAt, Instant.now());
    }

    /**
     * 记录需要写回 run 表的关键事件最后序号。
     *
     * <p>高并发流式输出期间，运行中 latest seq 以 {@code fin_ex_chat_event_t} 为事实源；
     * run 表主要在 run.started 和终态事件路径更新，避免每个 delta 都写放大。</p>
     */
    public ChatRun withLastSeq(long sequence) {
        return new ChatRun(id, tenantId, userId, sessionId, status, routeType, agentCode, runtimeProvider,
                runtimeSessionId, runMode, parentMessageId, userMessageId, assistantMessageId,
                firstSeq, sequence, cancelReason, startedAt, finishedAt, metadata, createdAt, Instant.now());
    }

    /**
     * 保存 Runtime 返回的内部会话标识。
     */
    public ChatRun withRuntimeSessionId(String nextRuntimeSessionId) {
        return new ChatRun(id, tenantId, userId, sessionId, status, routeType, agentCode, runtimeProvider,
                nextRuntimeSessionId, runMode, parentMessageId, userMessageId, assistantMessageId,
                firstSeq, lastSeq, cancelReason, startedAt, finishedAt, metadata, createdAt, Instant.now());
    }

    /**
     * 外部路由信号在 run.started 后才执行时，用最终路由和 RuntimeBinding 回填诊断字段。
     */
    public ChatRun withResolvedRoute(String nextRouteType, String nextAgentCode,
                                     String nextRuntimeProvider, String nextRuntimeSessionId) {
        return new ChatRun(id, tenantId, userId, sessionId, status, nextRouteType, nextAgentCode,
                nextRuntimeProvider, nextRuntimeSessionId, runMode, parentMessageId, userMessageId,
                assistantMessageId, firstSeq, lastSeq, cancelReason, startedAt, finishedAt,
                metadata, createdAt, Instant.now());
    }

    /**
     * 合并服务端内部 run metadata；调用方必须只传入可信字段。
     */
    public ChatRun withMetadata(Map<String, Object> metadataOverlay) {
        if (metadataOverlay == null || metadataOverlay.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(metadata);
        merged.putAll(metadataOverlay);
        return new ChatRun(id, tenantId, userId, sessionId, status, routeType, agentCode, runtimeProvider,
                runtimeSessionId, runMode, parentMessageId, userMessageId, assistantMessageId,
                firstSeq, lastSeq, cancelReason, startedAt, finishedAt, merged, createdAt, Instant.now());
    }

    /**
     * 回填 run 终态后生成的 assistant 消息 ID。
     */
    public ChatRun withAssistantMessageId(String nextAssistantMessageId) {
        return new ChatRun(id, tenantId, userId, sessionId, status, routeType, agentCode, runtimeProvider,
                runtimeSessionId, runMode, parentMessageId, userMessageId, nextAssistantMessageId,
                firstSeq, lastSeq, cancelReason, startedAt, finishedAt, metadata, createdAt, Instant.now());
    }

    /**
     * 标记 stop 已被接受。
     */
    public ChatRun cancelling(String reason) {
        return new ChatRun(id, tenantId, userId, sessionId, ChatRunStatus.CANCELLING, routeType, agentCode,
                runtimeProvider, runtimeSessionId, runMode, parentMessageId, userMessageId, assistantMessageId,
                firstSeq, lastSeq, reason, startedAt, finishedAt, metadata, createdAt, Instant.now());
    }

    /**
     * 标记 run 已取消。
     */
    public ChatRun cancelled(long sequence) {
        Instant now = Instant.now();
        return new ChatRun(id, tenantId, userId, sessionId, ChatRunStatus.CANCELLED, routeType, agentCode,
                runtimeProvider, runtimeSessionId, runMode, parentMessageId, userMessageId, assistantMessageId,
                firstSeq, sequence, cancelReason, startedAt, now, metadata, createdAt, now);
    }

    /**
     * 标记 run 已完成。
     */
    public ChatRun completed(long sequence) {
        Instant now = Instant.now();
        return new ChatRun(id, tenantId, userId, sessionId, ChatRunStatus.COMPLETED, routeType, agentCode,
                runtimeProvider, runtimeSessionId, runMode, parentMessageId, userMessageId, assistantMessageId,
                firstSeq, sequence, cancelReason, startedAt, now, metadata, createdAt, now);
    }

    /**
     * 标记 run 已进入等待用户输入状态。
     */
    public ChatRun waitingUser(long sequence) {
        Instant now = Instant.now();
        return new ChatRun(id, tenantId, userId, sessionId, ChatRunStatus.WAITING_USER, routeType, agentCode,
                runtimeProvider, runtimeSessionId, runMode, parentMessageId, userMessageId, assistantMessageId,
                firstSeq, sequence, cancelReason, startedAt, now, metadata, createdAt, now);
    }

    /**
     * 标记 run 已失败。
     */
    public ChatRun failed(long sequence) {
        Instant now = Instant.now();
        return new ChatRun(id, tenantId, userId, sessionId, ChatRunStatus.FAILED, routeType, agentCode,
                runtimeProvider, runtimeSessionId, runMode, parentMessageId, userMessageId, assistantMessageId,
                firstSeq, sequence, cancelReason, startedAt, now, metadata, createdAt, now);
    }
}
