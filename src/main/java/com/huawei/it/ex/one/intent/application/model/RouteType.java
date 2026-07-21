package com.huawei.it.ex.one.intent.application.model;

/**
 * SuperAgent 对用户请求的处理路径类型。
 */
public enum RouteType {
    /** 路由到财经领域 DomainAgent。 */
    DOMAIN_AGENT,
    /** 服务直接返回可控系统响应。 */
    SYSTEM_RESPONSE,
    /** 复杂任务路由到 AgentRuntime。 */
    AGENT_RUNTIME
}
