package com.huawei.finance.front.one.domain.routing;

/**
 * 聊天请求路由结果。
 *
 * <p>这里只描述处理路径，不绑定某个 AgentRuntime 的具体实现。</p>
 */
public record RouteTarget(RouteType type, String selectedToolCode, String reason) {
    public static RouteTarget directTool(String toolCode, String reason) {
        return new RouteTarget(RouteType.DIRECT_TOOL, toolCode, reason);
    }

    public static RouteTarget directModel(String reason) {
        return new RouteTarget(RouteType.DIRECT_MODEL, null, reason);
    }

    public static RouteTarget agentRuntime(String reason) {
        return new RouteTarget(RouteType.AGENT_RUNTIME, null, reason);
    }
}
