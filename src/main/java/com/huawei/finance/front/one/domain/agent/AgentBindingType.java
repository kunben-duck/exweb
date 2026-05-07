package com.huawei.finance.front.one.domain.agent;

/**
 * AgentBinding 指向的下游执行主体类型。
 */
public enum AgentBindingType {
    /** 简单任务路由到第三方 SubAgent。 */
    SUB_AGENT,
    /** 复杂任务路由到 SuperAgent 的 AgentRuntime。 */
    AGENT_RUNTIME
}
