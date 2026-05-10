package com.huawei.finance.front.one.domain.routing;

/**
 * 聊天请求路由结果。
 *
 * <p>这里只描述处理路径，不绑定某个 AgentRuntime 或 SubAgent 的具体实现。</p>
 *
 * @param type 路由类型。
 * @param selectedAgentCode 选中的 SubAgent 编码，仅 SUB_AGENT 路由有效。
 * @param routeSource 路由信号来源，例如 use-case-library、intent-service、runtime-binding。
 * @param score 路由置信分数。
 * @param reason 路由原因或系统回复文本。
 */
public record RouteTarget(
        RouteType type,
        String selectedAgentCode,
        String routeSource,
        double score,
        String reason
) {
    public static RouteTarget subAgent(String agentCode, String routeSource, double score, String reason) {
        return new RouteTarget(RouteType.SUB_AGENT, agentCode, routeSource, score, reason);
    }

    public static RouteTarget systemResponse(String reason) {
        return new RouteTarget(RouteType.SYSTEM_RESPONSE, null, "system", 1.0, reason);
    }

    public static RouteTarget agentRuntime(String reason) {
        return new RouteTarget(RouteType.AGENT_RUNTIME, null, "agent-runtime", 0.0, reason);
    }

    public static RouteTarget agentRuntime(String routeSource, double score, String reason) {
        return new RouteTarget(RouteType.AGENT_RUNTIME, null, routeSource, score, reason);
    }
}
