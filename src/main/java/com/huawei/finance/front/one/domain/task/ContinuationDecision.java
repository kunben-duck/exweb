package com.huawei.finance.front.one.domain.task;

/**
 * active task 存在时，SuperAgent 对本轮用户输入的续接决策。
 */
public enum ContinuationDecision {
    /** 本轮用户输入属于当前任务，继续调用当前 SubAgent。 */
    CONTINUE_CURRENT,
    /** 本轮用户输入是新任务，挂起当前任务并重新路由。 */
    SUSPEND_AND_ROUTE_NEW,
    /** 用户明确取消当前任务。 */
    CANCEL_CURRENT,
    /** 无法可靠判断，需要先向用户澄清。 */
    ASK_USER_CONFIRMATION,
    /** 当前没有可续接任务，直接按新任务路由。 */
    ROUTE_NEW,
    /** 用户明确恢复之前挂起的任务。 */
    RESUME_SUSPENDED
}
