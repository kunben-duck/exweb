package com.huawei.it.ex.one.domain.intent;

/**
 * 意图服务识别出的任务复杂度。
 */
public enum TaskComplexity {
    /** routeAction=ROUTE_SINGLE，可直接路由到 DomainAgent。 */
    SIMPLE,
    /** 复杂任务，需要进入 AgentRuntime 进行规划和多步处理。 */
    COMPLEX,
    /** 请求信息不足，需要先向用户澄清。 */
    NEED_CLARIFICATION,
    /** 当前系统不支持的任务。 */
    UNSUPPORTED
}
