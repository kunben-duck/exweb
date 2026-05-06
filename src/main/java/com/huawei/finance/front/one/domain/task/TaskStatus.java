package com.huawei.finance.front.one.domain.task;

/**
 * SuperAgent 侧任务状态。
 *
 * <p>该状态描述 SuperAgent 对任务生命周期的事实认知，不要求第三方 SubAgent 原生支持同名状态。
 * 当 SubAgent 响应不规范时，状态由响应标准化器推断或转入用户澄清。</p>
 */
public enum TaskStatus {
    /** 任务正在执行或可继续推进。 */
    ACTIVE,
    /** 任务等待用户补充明确材料或参数。 */
    REQUIRES_USER_INPUT,
    /** 任务已提交外部系统，等待外部系统异步处理。 */
    WAITING_EXTERNAL_SYSTEM,
    /** 任务状态不明，需要用户确认是继续当前任务还是开始新任务。 */
    WAITING_USER_CONFIRMATION,
    /** 任务被临时挂起，可在后续用户明确恢复时继续。 */
    SUSPENDED,
    /** 任务已完成。 */
    COMPLETED,
    /** 任务执行失败。 */
    FAILED,
    /** 用户或系统取消了任务。 */
    CANCELLED,
    /** 任务超过可续接时间窗口。 */
    EXPIRED,
    /** 内部诊断状态：SubAgent 原始响应无法被可靠识别。 */
    UNKNOWN;

    /**
     * 判断该状态是否仍可作为当前会话的 active task。
     *
     * @return true 表示可以进入 ContinuationGuard，false 表示不参与 active 续接。
     */
    public boolean activeRoutable() {
        return this == ACTIVE
                || this == REQUIRES_USER_INPUT
                || this == WAITING_EXTERNAL_SYSTEM
                || this == WAITING_USER_CONFIRMATION;
    }

    /**
     * 判断该状态是否为终态。
     *
     * @return true 表示任务已经不应继续路由。
     */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }

    /**
     * 从外部字符串安全解析状态。
     *
     * @param value 外部状态字符串。
     * @param fallback 无法解析时使用的状态。
     * @return 解析后的任务状态。
     */
    public static TaskStatus from(String value, TaskStatus fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return TaskStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
