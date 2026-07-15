package com.huawei.it.ex.one.domain.chat;

/**
 * 单轮 run 的执行控制面状态。
 *
 * <p>该状态描述后台执行实例对 run 的占有、恢复和终结情况；业务生命周期状态仍保存在
 * {@link ChatRunStatus} 中。两者分离可以避免把实例 ID、心跳、租约等运维语义写入业务 run 表。</p>
 */
public enum ChatRunExecutionStatus {
    /** 执行实例正在处理 run，并应定期刷新租约。 */
    RUNNING,
    /** stop 已被接受，执行实例正在终止本轮输出。 */
    CANCELLING,
    /** 某个存活实例已抢占 stale run，正在执行恢复策略。 */
    RECOVERING,
    /** run 已正常完成，执行控制面也已闭合。 */
    COMPLETED,
    /** run 等待用户输入，执行控制面已释放，不再由 watchdog 判为超时。 */
    WAITING_USER,
    /** run 已失败，执行控制面也已闭合。 */
    FAILED,
    /** run 已被用户取消，执行控制面也已闭合。 */
    CANCELLED;

    /**
     * @return 是否为执行控制面的终态。
     */
    public boolean terminal() {
        return this == COMPLETED || this == WAITING_USER || this == FAILED || this == CANCELLED;
    }
}
