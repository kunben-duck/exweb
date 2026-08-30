/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.Map;

/** Runtime event wrapper whose acknowledgement completes only after guarded persistence succeeds. */
record PersistenceAcknowledgedEvent(
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
