/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

/**
 * 单轮聊天 run 的生命周期状态。
 *
 * <p>run 是用户一次提问对应的服务端执行单元。浏览器连接断开不会改变 run 状态；
 * 只有执行完成、失败、显式 stop 或协议级等待用户输入才会进入终态。</p>
 */
public enum ChatRunStatus {
    /** run 正在执行，仍可被 stop 接口取消。 */
    RUNNING,
    /** stop 已被接受，正在终止本地订阅并尽力通知下游。 */
    CANCELLING,
    /** run 已被用户取消，事件流以 run.cancelled 结束。 */
    CANCELLED,
    /** run 已正常完成，事件流以 run.completed 结束。 */
    COMPLETED,
    /** run 已等待用户澄清/审批输入，事件流以 run.waiting_user 结束。 */
    WAITING_USER,
    /** run 执行失败，事件流以 run.failed 结束。 */
    FAILED;

    /**
     * @return 当前状态是否已经是不可再写业务事件的终态。
     */
    public boolean terminal() {
        return this == CANCELLED || this == COMPLETED || this == WAITING_USER || this == FAILED;
    }

    /**
     * @return 当前状态是否允许接收新的 stop 请求。
     */
    public boolean cancellable() {
        return this == RUNNING;
    }

    /**
     * @return stop 终态提交是否可以首次执行或在失败后重试。
     */
    public boolean stopRetryable() {
        return this == RUNNING || this == CANCELLING;
    }
}
