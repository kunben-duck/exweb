/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.conversation;

/**
 * 聊天事件被事实源写入栅栏拒绝时抛出的异常。
 *
 * <p>该异常不是系统故障，而是 run 已经被 stop、watchdog 抢占、终态闭合或执行 owner/fencing token
 * 失效后的正常保护信号。后台流捕获后应停止继续消费下游 delta，避免迟到事件污染事实源。</p>
 */
public class ChatEventAppendRejectedException extends RuntimeException {
    public ChatEventAppendRejectedException(String message) {
        super(message);
    }

    public ChatEventAppendRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
