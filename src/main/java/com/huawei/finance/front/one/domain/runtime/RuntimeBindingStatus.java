package com.huawei.finance.front.one.domain.runtime;

/**
 * Relay Runtime 绑定状态。
 */
public enum RuntimeBindingStatus {
    /** Runtime 会话仍可继续接收本聊天会话的后续输入。 */
    ACTIVE,
    /** 用户或系统显式取消了当前 Runtime 会话绑定。 */
    CANCELLED;

    /**
     * @return true 表示该状态可继续作为 active runtime route。
     */
    public boolean routable() {
        return this == ACTIVE;
    }
}
