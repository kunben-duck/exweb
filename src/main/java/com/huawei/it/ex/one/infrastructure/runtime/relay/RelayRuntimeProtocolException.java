/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.runtime.relay;

/**
 * Relay Runtime 协议错误。
 *
 * <p>Relay 协议 adapter 遇到下游明确错误帧或无法兼容的协议数据时抛出该异常。
 * 上层 FinanceEXChatService 会把异常统一转换成 run.failed 事件并落库。</p>
 */
public class RelayRuntimeProtocolException extends RuntimeException {
    public RelayRuntimeProtocolException(String message) {
        super(message);
    }
}
