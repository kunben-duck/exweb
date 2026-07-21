package com.huawei.it.ex.one.chat.application.model;

import com.huawei.it.ex.one.common.event.ChatEvent;
import java.time.Instant;
import java.util.Map;
import reactor.core.publisher.Sinks;

/** Chat event carrying the existing one-shot persistence acknowledgement signal. */
public record PersistenceAcknowledgedEvent(
        ChatEvent delegate,
        Sinks.One<Void> persisted
) implements ChatEvent {
    @Override
    public String runId() {
        return delegate.runId();
    }

    @Override
    public String sessionId() {
        return delegate.sessionId();
    }

    @Override
    public long sequence() {
        return delegate.sequence();
    }

    @Override
    public String type() {
        return delegate.type();
    }

    @Override
    public Instant createdAt() {
        return delegate.createdAt();
    }

    @Override
    public Map<String, Object> payload() {
        return delegate.payload();
    }
}
