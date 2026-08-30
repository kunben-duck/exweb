/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.runtime.relay;

import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeSessionUnavailable;

/** Relay 明确确认目标 session 不存在或已损坏。 */
public class RelayRuntimeSessionUnavailableException extends RelayRuntimeProtocolException
        implements AgentRuntimeSessionUnavailable {
    public RelayRuntimeSessionUnavailableException(String message) {
        super(message);
    }
}
