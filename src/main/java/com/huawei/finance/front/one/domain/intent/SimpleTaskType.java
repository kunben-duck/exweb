package com.huawei.finance.front.one.domain.intent;

/**
 * 简单任务的直接处理类型。
 *
 * <p>该枚举只表达 fast path 的建议，不代表最终执行结果；RoutingPolicy 仍会结合置信度和策略做裁决。</p>
 */
public enum SimpleTaskType {
    DIRECT_MODEL,
    DIRECT_TOOL,
    NONE
}
