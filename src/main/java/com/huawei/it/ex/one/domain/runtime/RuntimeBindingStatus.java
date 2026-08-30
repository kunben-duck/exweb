/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.runtime;

/**
 * AgentRuntime 绑定状态。
 */
public enum RuntimeBindingStatus {
    /** Runtime 会话仍可继续接收本聊天会话的后续输入。 */
    ACTIVE,
    /** 不参与自动路由，但路由再次选中相同 Runtime 时可以恢复原会话。 */
    RESUMABLE,
    /** 用户或系统显式取消了当前 Runtime 会话绑定。 */
    CANCELLED;

    /**
     * @return true 表示该状态可继续作为 active runtime route。
     */
    public boolean routable() {
        return this == ACTIVE;
    }
}
