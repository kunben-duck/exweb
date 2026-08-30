/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

/** The callback arrived before the async-waiting transaction became visible. */
public final class DomainAgentAsyncCallbackNotReadyException extends IllegalStateException {
    public DomainAgentAsyncCallbackNotReadyException() {
        super("DomainAgent async callback state is not ready; retry the same callback later");
    }
}
