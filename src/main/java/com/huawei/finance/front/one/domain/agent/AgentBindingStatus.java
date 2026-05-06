package com.huawei.finance.front.one.domain.agent;

import com.huawei.finance.front.one.domain.task.TaskStatus;

/**
 * SuperAgent 路由绑定状态。
 *
 * <p>AgentBinding 只表达“当前会话存在可续接的下游 Agent 索引”，不表达完整任务事实。
 * 完整任务生命周期由 TaskCard 维护；这里的状态用于判断是否还能从 Redis/openGauss 热路径
 * 找到该下游 Agent。</p>
 */
public enum AgentBindingStatus {
    /** 下游 Agent 仍可继续处理本任务。 */
    ACTIVE,
    /** 下游 Agent 正在等待用户补充参数或材料。 */
    REQUIRES_USER_INPUT,
    /** 下游 Agent 已提交外部系统，任务仍处于可查询或可追问状态。 */
    WAITING_EXTERNAL_SYSTEM,
    /** SuperAgent 无法可靠确认下一步，需要用户先确认继续旧任务还是开启新任务。 */
    WAITING_USER_CONFIRMATION,
    /** 任务被挂起，binding 保留审计事实但不再作为 active route。 */
    SUSPENDED,
    /** 下游任务已完成。 */
    COMPLETED,
    /** 下游任务失败。 */
    FAILED,
    /** 用户或系统取消了任务。 */
    CANCELLED,
    /** binding 超过可续接窗口。 */
    EXPIRED,
    /** 内部诊断状态；不会直接作为 active binding 对外路由。 */
    UNKNOWN;

    /**
     * 判断 binding 是否可作为当前会话的 active route。
     *
     * @return true 表示可进入多轮续接判断。
     */
    public boolean routable() {
        return this == ACTIVE
                || this == REQUIRES_USER_INPUT
                || this == WAITING_EXTERNAL_SYSTEM
                || this == WAITING_USER_CONFIRMATION;
    }

    /**
     * 判断 binding 是否已经结束。
     *
     * @return true 表示不应再参与路由。
     */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }

    /**
     * 从任务状态映射到路由绑定状态。
     *
     * @param value 外部事件中的 taskStatus 字符串。
     * @param fallback 无法解析时使用的状态。
     * @return 可用于 binding 持久化的状态。
     */
    public static AgentBindingStatus fromTaskStatus(String value, AgentBindingStatus fallback) {
        TaskStatus taskStatus = TaskStatus.from(value, null);
        if (taskStatus == null) {
            return fallback;
        }
        if (taskStatus == TaskStatus.UNKNOWN) {
            return WAITING_USER_CONFIRMATION;
        }
        try {
            return AgentBindingStatus.valueOf(taskStatus.name());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
