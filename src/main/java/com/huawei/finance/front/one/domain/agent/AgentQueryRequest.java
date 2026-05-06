package com.huawei.finance.front.one.domain.agent;

import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatResponseMode;
import com.huawei.finance.front.one.domain.chat.ImMessageType;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.task.TaskCard;
import java.util.List;
import java.util.Map;

/**
 * SuperAgent 调用下游 SubAgent 的统一请求对象。
 *
 * <p>该对象是应用层与第三方 Agent 服务之间的防腐层：前端 DTO、openGauss 行对象和 Redis
 * 缓存结构都不会直接传给 SubAgent。自然语言契约模式下，ConfiguredSubAgentClient 会基于其中的
 * TaskCard 进一步生成 SubAgentTaskRequest 和契约 Prompt。</p>
 *
 * @param tenantId 租户标识，来自服务端身份上下文。
 * @param userId 用户标识，来自服务端身份上下文。
 * @param sessionId 前端聊天会话标识。
 * @param runId 本轮 SuperAgent 运行标识。
 * @param agentCode 目标 SubAgent 编码。
 * @param agentSessionId 下游 SubAgent 会话标识，首次调用可为空。
 * @param runtimeSessionId 预留的运行时会话标识，SubAgent 调用通常为空。
 * @param message 本轮用户输入。
 * @param messageType 本轮消息类型。
 * @param responseMode 前端期望的响应模式。
 * @param attachments 本轮用户关联附件。
 * @param memoryContext SuperAgent 装配的上下文快照。
 * @param routeTarget 本轮路由决策结果。
 * @param taskCard 当前 SubAgent 任务卡片；首轮创建后传入。
 * @param metadata 前端或上游传入的扩展元数据。
 */
public record AgentQueryRequest(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String agentCode,
        String agentSessionId,
        String runtimeSessionId,
        String message,
        ImMessageType messageType,
        ChatResponseMode responseMode,
        List<AttachmentRef> attachments,
        MemoryContext memoryContext,
        RouteTarget routeTarget,
        TaskCard taskCard,
        Map<String, Object> metadata
) {
    public AgentQueryRequest {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
