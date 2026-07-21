package com.huawei.it.ex.one.runtime.infrastructure.relay;

import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeSessionUnavailable;

/** Relay 明确确认目标 session 不存在或已损坏。 */
public class RelayRuntimeSessionUnavailableException extends RelayRuntimeProtocolException
        implements AgentRuntimeSessionUnavailable {
    public RelayRuntimeSessionUnavailableException(String message) {
        super(message);
    }
}
