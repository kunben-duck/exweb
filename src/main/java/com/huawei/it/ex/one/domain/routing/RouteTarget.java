package com.huawei.it.ex.one.domain.routing;

/**
 * 聊天请求路由结果。
 *
 * <p>这里只描述处理路径，不绑定某个 AgentRuntime 或 DomainAgent 的具体实现。</p>
 *
 * @param type 路由类型。
 * @param selectedAgentCode 选中的 DomainAgent ID，仅 DOMAIN_AGENT 路由有效。
 * @param routeSource 路由信号来源，例如 use-case-library、intent-agent、runtime-binding。
 * @param score 路由置信分数。
 * @param reason 路由原因或系统回复文本。
 * @param runtimeProfile AgentRuntime 内部调用档案；非 AgentRuntime 路由忽略该字段。
 * @param runtimeRoleName Relay专家模式的动态角色名；其他路由为空。
 */
public record RouteTarget(
        RouteType type,
        String selectedAgentCode,
        String routeSource,
        double score,
        String reason,
        RuntimeProfile runtimeProfile,
        String runtimeRoleName
) {
    public RouteTarget {
        runtimeProfile = runtimeProfile == null ? RuntimeProfile.DELEGATE : runtimeProfile;
        runtimeRoleName = normalize(runtimeRoleName);
        if (runtimeProfile == RuntimeProfile.DOMAIN_EXPERT && runtimeRoleName == null) {
            throw new IllegalArgumentException("Domain expert runtimeRoleName must not be blank");
        }
        if (runtimeProfile != RuntimeProfile.DOMAIN_EXPERT) {
            runtimeRoleName = null;
        }
    }

    public RouteTarget(RouteType type, String selectedAgentCode, String routeSource,
                       double score, String reason, RuntimeProfile runtimeProfile) {
        this(type, selectedAgentCode, routeSource, score, reason, runtimeProfile, null);
    }

    public static RouteTarget domainAgent(String domainAgentId, String reason) {
        return new RouteTarget(RouteType.DOMAIN_AGENT, domainAgentId, "domain-agent", 1.0, reason,
                RuntimeProfile.DELEGATE, null);
    }

    public static RouteTarget domainAgent(String domainAgentId, String routeSource, double score, String reason) {
        return new RouteTarget(RouteType.DOMAIN_AGENT, domainAgentId, routeSource, score, reason,
                RuntimeProfile.DELEGATE, null);
    }

    public static RouteTarget systemResponse(String reason) {
        return new RouteTarget(RouteType.SYSTEM_RESPONSE, null, "system", 1.0, reason,
                RuntimeProfile.DELEGATE, null);
    }

    public static RouteTarget agentRuntime(String reason) {
        return new RouteTarget(RouteType.AGENT_RUNTIME, null, "agent-runtime", 0.0, reason,
                RuntimeProfile.DELEGATE, null);
    }

    public static RouteTarget agentRuntime(String routeSource, double score, String reason) {
        return agentRuntime(routeSource, score, reason, RuntimeProfile.DELEGATE);
    }

    public static RouteTarget agentRuntime(String routeSource, double score, String reason,
                                           RuntimeProfile runtimeProfile) {
        return agentRuntime(routeSource, score, reason, runtimeProfile, null);
    }

    public static RouteTarget agentRuntime(String routeSource, double score, String reason,
                                           RuntimeProfile runtimeProfile, String runtimeRoleName) {
        return new RouteTarget(RouteType.AGENT_RUNTIME, null, routeSource, score, reason,
                runtimeProfile, runtimeRoleName);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
