/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 保证 Intent 最终结果成为事件事实后，编排流程才能执行路由和下游副作用。
 */
final class IntentResultPersistenceBarrier {
    private static final String INTENT_AGENT_SOURCE = "intent-agent";
    private static final String INTENT_RESULT_SOURCE_TYPE = "intent-result";

    private final AtomicReference<Sinks.One<Void>> pendingAcknowledgement = new AtomicReference<>();

    ChatEvent guard(ChatEvent event) {
        if (!intentResult(event)) {
            return event;
        }
        Sinks.One<Void> acknowledgement = Sinks.one();
        if (!pendingAcknowledgement.compareAndSet(null, acknowledgement)) {
            throw new IllegalStateException("同一路由流存在尚未处理的 Intent Result 持久化确认");
        }
        return new PersistenceAcknowledgedEvent(event, acknowledgement);
    }

    Mono<Void> awaitBeforeRoute() {
        Sinks.One<Void> acknowledgement = pendingAcknowledgement.getAndSet(null);
        return acknowledgement == null ? Mono.empty() : acknowledgement.asMono();
    }

    private boolean intentResult(ChatEvent event) {
        return event != null
                && event.payload() != null
                && INTENT_AGENT_SOURCE.equals(event.payload().get("source"))
                && INTENT_RESULT_SOURCE_TYPE.equals(event.payload().get("sourceType"));
    }
}
