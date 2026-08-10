package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

import reactor.core.publisher.Flux;

import org.springframework.stereotype.Component;

/** 在Runtime事件完成Event管线处理前预留并释放待消费预算。 */
@Component
public class RuntimePendingEventGuard {
    private final RuntimePendingBudgetRegistry budgetRegistry;
    private final RuntimeEventSizeEstimator sizeEstimator;

    public RuntimePendingEventGuard(RuntimePendingBudgetRegistry budgetRegistry,
                                    RuntimeEventSizeEstimator sizeEstimator) {
        this.budgetRegistry = budgetRegistry;
        this.sizeEstimator = sizeEstimator;
    }

    public Flux<ChatEvent> guard(String runId, Flux<ChatEvent> source) {
        if (source == null) {
            return Flux.empty();
        }
        return source
                .map(event -> reserve(runId, event))
                .doOnDiscard(PendingBudgetedChatEvent.class, PendingBudgetedChatEvent::close);
    }

    public ChatEvent reserve(String runId, ChatEvent event) {
        if (event instanceof PendingBudgetedChatEvent) {
            return event;
        }
        RuntimePendingBudgetRegistry.Reservation reservation =
                budgetRegistry.reserve(runId, sizeEstimator.bytes(event));
        return new PendingBudgetedChatEvent(event, reservation);
    }

    /** 读取内部Event但继续持有reservation，供Event管线完成后统一释放。 */
    public ChatEvent unwrap(ChatEvent event) {
        ChatEvent current = event;
        while (current instanceof PendingBudgetedChatEvent pending) {
            current = pending.delegate();
        }
        return current;
    }

    /** 释放Event携带的全部reservation。重复释放是幂等的。 */
    public void release(ChatEvent event) {
        ChatEvent current = event;
        while (current instanceof PendingBudgetedChatEvent pending) {
            pending.close();
            current = pending.delegate();
        }
    }

    /** 兼容旧调用：释放reservation并返回内部Event。 */
    public ChatEvent releaseAndUnwrap(ChatEvent event) {
        ChatEvent unwrapped = unwrap(event);
        release(event);
        return unwrapped;
    }

    public void releaseDiscarded(Object value) {
        if (value instanceof PendingBudgetedChatEvent pending) {
            pending.close();
        }
    }

    public void releaseRun(String runId) {
        budgetRegistry.releaseRun(runId);
    }
}
