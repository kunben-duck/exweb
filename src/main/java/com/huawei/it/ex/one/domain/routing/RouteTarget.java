/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

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
 * @param relayOutputMode 本轮Relay事件输出模式；普通路径默认完整流式输出。
 * @param invocationSkillId 本次实际Runtime调用对应的消息技能标识；没有可记录标识时为空。
 */
public record RouteTarget(
        RouteType type,
        String selectedAgentCode,
        String routeSource,
        double score,
        String reason,
        RuntimeProfile runtimeProfile,
        String runtimeRoleName,
        RelayOutputMode relayOutputMode,
        String invocationSkillId
) {
    public RouteTarget {
        runtimeProfile = runtimeProfile == null ? RuntimeProfile.DELEGATE : runtimeProfile;
        relayOutputMode = relayOutputMode == null ? RelayOutputMode.FULL_STREAM : relayOutputMode;
        runtimeRoleName = normalize(runtimeRoleName);
        invocationSkillId = normalize(invocationSkillId);
        if (runtimeProfile == RuntimeProfile.DOMAIN_EXPERT && runtimeRoleName == null) {
            throw new IllegalArgumentException("Domain expert runtimeRoleName must not be blank");
        }
        if (runtimeProfile != RuntimeProfile.DOMAIN_EXPERT) {
            runtimeRoleName = null;
        }
        if (type != RouteType.AGENT_RUNTIME || runtimeProfile != RuntimeProfile.DELEGATE) {
            relayOutputMode = RelayOutputMode.FULL_STREAM;
        }
    }

    public RouteTarget(RouteType type, String selectedAgentCode, String routeSource,
                       double score, String reason, RuntimeProfile runtimeProfile,
                       String runtimeRoleName, RelayOutputMode relayOutputMode) {
        this(type, selectedAgentCode, routeSource, score, reason, runtimeProfile, runtimeRoleName,
                relayOutputMode, null);
    }

    public RouteTarget(RouteType type, String selectedAgentCode, String routeSource,
                       double score, String reason, RuntimeProfile runtimeProfile,
                       String runtimeRoleName) {
        this(type, selectedAgentCode, routeSource, score, reason, runtimeProfile, runtimeRoleName,
                RelayOutputMode.FULL_STREAM, null);
    }

    public RouteTarget(RouteType type, String selectedAgentCode, String routeSource,
                       double score, String reason, RuntimeProfile runtimeProfile) {
        this(type, selectedAgentCode, routeSource, score, reason, runtimeProfile, null,
                RelayOutputMode.FULL_STREAM, null);
    }

    public static RouteTarget domainAgent(String domainAgentId, String reason) {
        return new RouteTarget(RouteType.DOMAIN_AGENT, domainAgentId, "domain-agent", 1.0, reason,
                RuntimeProfile.DELEGATE, null, RelayOutputMode.FULL_STREAM, domainAgentId);
    }

    public static RouteTarget domainAgent(String domainAgentId, String routeSource, double score, String reason) {
        return new RouteTarget(RouteType.DOMAIN_AGENT, domainAgentId, routeSource, score, reason,
                RuntimeProfile.DELEGATE, null, RelayOutputMode.FULL_STREAM, domainAgentId);
    }

    public static RouteTarget systemResponse(String reason) {
        return new RouteTarget(RouteType.SYSTEM_RESPONSE, null, "system", 1.0, reason,
                RuntimeProfile.DELEGATE, null, RelayOutputMode.FULL_STREAM, null);
    }

    public static RouteTarget agentRuntime(String reason) {
        return new RouteTarget(RouteType.AGENT_RUNTIME, null, "agent-runtime", 0.0, reason,
                RuntimeProfile.DELEGATE, null, RelayOutputMode.FULL_STREAM, null);
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
                runtimeProfile, runtimeRoleName, RelayOutputMode.FULL_STREAM, null);
    }

    public static RouteTarget agentRuntimeWithInvocationSkill(
            String routeSource,
            double score,
            String reason,
            String invocationSkillId) {
        return new RouteTarget(RouteType.AGENT_RUNTIME, null, routeSource, score, reason,
                RuntimeProfile.DELEGATE, null, RelayOutputMode.FULL_STREAM, invocationSkillId);
    }

    public static RouteTarget domainExpertRuntime(
            String routeSource,
            double score,
            String reason,
            String runtimeRoleName,
            String invocationSkillId) {
        return new RouteTarget(RouteType.AGENT_RUNTIME, null, routeSource, score, reason,
                RuntimeProfile.DOMAIN_EXPERT, runtimeRoleName, RelayOutputMode.FULL_STREAM,
                invocationSkillId);
    }

    public static RouteTarget agentRuntimeAnswerStreamOnly(
            String routeSource,
            double score,
            String reason) {
        return agentRuntimeAnswerStreamOnly(routeSource, score, reason, null);
    }

    public static RouteTarget agentRuntimeAnswerStreamOnly(
            String routeSource,
            double score,
            String reason,
            String invocationSkillId) {
        return new RouteTarget(RouteType.AGENT_RUNTIME, null, routeSource, score, reason,
                RuntimeProfile.DELEGATE, null, RelayOutputMode.ANSWER_STREAM_ONLY, invocationSkillId);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
