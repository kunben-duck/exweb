package com.huawei.it.ex.one.domain.chat;

/** The local async callback bulkhead has no immediately available permit. */
public final class DomainAgentAsyncCallbackBusyException extends IllegalStateException {
    public DomainAgentAsyncCallbackBusyException() {
        super("DomainAgent async callback capacity is exhausted");
    }
}
