package com.huawei.finance.front.one.application.integration.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import java.util.List;
import java.util.Map;

/**
 * SuperAgent 调用 AgentRuntime 的统一请求。
 *
 * <p>AgentRuntime 是独立复杂任务 Agent，拥有自己的 session、上下文、压缩和规划机制。
 * SuperAgent 只传递当前轮次必要输入和可见上下文，不暴露前端 DTO 或存储实现。</p>
 *
 * @param tenantId 租户标识，来自应用身份上下文。
 * @param userId 用户标识，来自应用身份上下文。
 * @param userAccount 用户账号，来自应用身份上下文。
 * @param globalUserId 全局用户 ID，来自应用身份上下文。
 * @param sessionId 前端聊天会话标识。
 * @param runId 本轮 SuperAgent 执行追踪标识。
 * @param runtimeSessionId AgentRuntime 实际会话标识；Relay 首次调用前可为空或等于 ChatService sessionId。
 * @param runtimeSessionMode 本轮 Runtime 会话协议模式，由应用层显式给出，adapter 不自行猜测。
 * @param message 本轮用户输入文本。
 * @param attachments 本轮关联附件引用。
 * @param documents 已完成归属和状态校验的文档快照；不需要文档元数据的 provider 可忽略。
 * @param memoryContext SuperAgent 可选记忆上下文；长短期记忆关闭时为空上下文。
 * @param intentDecision 意图服务识别结果，可能为空。
 * @param routeTarget 本轮路由决策结果。
 * @param metadata 前端或上游传入的扩展元数据。
 * @param forwardHeaders 仅在内存中传递给 Runtime adapter 的入口请求头快照；必须被 JSON 序列化忽略。
 */
public record AgentRuntimeRequest(
        String tenantId,
        String userId,
        String userAccount,
        Long globalUserId,
        String sessionId,
        String runId,
        String runtimeSessionId,
        RuntimeSessionMode runtimeSessionMode,
        String message,
        List<AttachmentRef> attachments,
        List<UploadedDocument> documents,
        MemoryContext memoryContext,
        IntentDecision intentDecision,
        RouteTarget routeTarget,
        Map<String, Object> metadata,
        @JsonIgnore RuntimeForwardHeaders forwardHeaders
) {
    public AgentRuntimeRequest {
        runtimeSessionMode = runtimeSessionMode == null ? RuntimeSessionMode.RESUME : runtimeSessionMode;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        documents = documents == null ? List.of() : List.copyOf(documents);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }

    public AgentRuntimeRequest(String tenantId, String userId, String userAccount, Long globalUserId,
                               String sessionId, String runId, String runtimeSessionId,
                               RuntimeSessionMode runtimeSessionMode, String message,
                               List<AttachmentRef> attachments, MemoryContext memoryContext,
                               IntentDecision intentDecision, RouteTarget routeTarget,
                               Map<String, Object> metadata, RuntimeForwardHeaders forwardHeaders) {
        this(tenantId, userId, userAccount, globalUserId, sessionId, runId, runtimeSessionId,
                runtimeSessionMode, message, attachments, List.of(), memoryContext, intentDecision, routeTarget,
                metadata, forwardHeaders);
    }
}
