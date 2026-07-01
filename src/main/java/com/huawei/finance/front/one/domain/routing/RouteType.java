package com.huawei.finance.front.one.domain.routing;

/**
 * SuperAgent 对用户请求的处理路径类型。
 */
public enum RouteType {
    /** 简单任务路由到第三方 SubAgent。 */
    SUB_AGENT,
    /** 前端显式选择 DomainAgent 后，调用对应财经领域 Agent。 */
    DOMAIN_AGENT,
    /** 服务直接返回可控系统响应。 */
    SYSTEM_RESPONSE,
    /** 复杂任务路由到 AgentRuntime。 */
    AGENT_RUNTIME
}
