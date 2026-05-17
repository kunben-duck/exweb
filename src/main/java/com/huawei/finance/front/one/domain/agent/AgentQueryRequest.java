package com.huawei.finance.front.one.domain.agent;

import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import java.util.List;
import java.util.Map;

/**
 * SuperAgent 调用下游 SubAgent 的统一请求对象。
 *
 * <p>该对象是应用层与第三方 Agent 服务之间的防腐层：前端 DTO、openGauss 行对象和 Redis
 * 缓存结构都不会直接传给 SubAgent。SubAgent 在当前正式版本中只执行一轮简单任务，不参与会话保持。</p>
 *
 * @param tenantId 租户标识，来自服务端身份上下文。
 * @param userId 用户标识，来自服务端身份上下文。
 * @param sessionId 前端聊天会话标识。
 * @param runId 本轮 SuperAgent 运行标识。
 * @param agentCode 目标 SubAgent 编码。
 * @param message 本轮用户输入。
 * @param attachments 本轮用户关联附件。
 * @param memoryContext SuperAgent 可选记忆上下文；长短期记忆关闭时为空上下文。
 * @param routeTarget 本轮路由决策结果。
 * @param metadata 前端或上游传入的扩展元数据。
 */
public record AgentQueryRequest(
        String tenantId,
        String userId,
        String sessionId,
        String runId,
        String agentCode,
        String message,
        List<AttachmentRef> attachments,
        MemoryContext memoryContext,
        RouteTarget routeTarget,
        Map<String, Object> metadata
) {
    public AgentQueryRequest {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
