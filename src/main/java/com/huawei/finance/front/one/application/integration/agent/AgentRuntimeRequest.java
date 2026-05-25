package com.huawei.finance.front.one.application.integration.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
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
 * @param sessionId 前端聊天会话标识。
 * @param runId 本轮 SuperAgent 执行追踪标识。
 * @param runtimeSessionId AgentRuntime 自己的会话标识，首次调用可为空。
 * @param message 本轮用户输入文本。
 * @param attachments 本轮关联附件引用。
 * @param memoryContext SuperAgent 可选记忆上下文；长短期记忆关闭时为空上下文。
 * @param intentDecision 意图服务识别结果，可能为空。
 * @param routeTarget 本轮路由决策结果。
 * @param metadata 前端或上游传入的扩展元数据。
 * @param forwardHeaders 仅在内存中传递给 Runtime adapter 的入口请求头快照；必须被 JSON 序列化忽略。
 */
public record AgentRuntimeRequest(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String runtimeSessionId,
        String message,
        List<AttachmentRef> attachments,
        MemoryContext memoryContext,
        IntentDecision intentDecision,
        RouteTarget routeTarget,
        Map<String, Object> metadata,
        @JsonIgnore RuntimeForwardHeaders forwardHeaders
) {
    public AgentRuntimeRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }
}
