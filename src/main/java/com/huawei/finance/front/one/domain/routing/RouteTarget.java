package com.huawei.finance.front.one.domain.routing;

/**
 * 聊天请求路由结果。
 *
 * <p>这里只描述处理路径，不绑定某个 AgentRuntime 或 SubAgent 的具体实现。</p>
 */
public record RouteTarget(RouteType type, String selectedAgentCode, String routeSource, double score, String reason) {
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
