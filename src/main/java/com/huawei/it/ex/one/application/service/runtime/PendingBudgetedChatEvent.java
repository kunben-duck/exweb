package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 携带Runtime待消费预算的内部事件包装，不得进入数据库或公开协议。 */
public final class PendingBudgetedChatEvent implements ChatEvent, AutoCloseable {
    private final ChatEvent delegate;
    private final RuntimePendingBudgetRegistry.Reservation reservation;
    private final AtomicBoolean released = new AtomicBoolean(false);

    PendingBudgetedChatEvent(ChatEvent delegate, RuntimePendingBudgetRegistry.Reservation reservation) {
        this.delegate = delegate;
        this.reservation = reservation;
    }

    public ChatEvent delegate() {
        return delegate;
    }

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

    @Override
    public void close() {
        if (released.compareAndSet(false, true)) {
            reservation.close();
        }
    }
}
