package com.huawei.finance.front.one.domain.intent;

/**
 * 意图服务识别出的任务复杂度。
 */
public enum TaskComplexity {
    /** 简单任务，可在高置信且有 DomainAgent 编码时直接路由到 DomainAgent。 */
    SIMPLE,
    /** 复杂任务，需要进入 AgentRuntime 进行规划和多步处理。 */
    COMPLEX,
    /** 请求信息不足，需要先向用户澄清。 */
    NEED_CLARIFICATION,
    /** 当前系统不支持的任务。 */
    UNSUPPORTED
}
