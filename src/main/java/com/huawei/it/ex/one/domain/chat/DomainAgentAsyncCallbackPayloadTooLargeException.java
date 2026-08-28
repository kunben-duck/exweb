package com.huawei.it.ex.one.domain.chat;

/** The callback request or its normalized event projection exceeds a configured hard limit. */
public final class DomainAgentAsyncCallbackPayloadTooLargeException extends IllegalArgumentException {
    public DomainAgentAsyncCallbackPayloadTooLargeException(String message) {
        super(message);
    }
}
