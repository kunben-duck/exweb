package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Relay WebSocket callback与Reactive消费链之间的有界单订阅桥接。 */
public final class RuntimePendingEventBridge {
    private final String runId;
    private final RuntimePendingEventGuard eventGuard;
    private final Queue<ChatEvent> queue;
    private final Sinks.Many<ChatEvent> sink;
    private final AtomicReference<Disposable> upstream = new AtomicReference<>();
    private final AtomicReference<Runnable> cleanup = new AtomicReference<>(() -> { });
    private final AtomicBoolean terminated = new AtomicBoolean(false);

    RuntimePendingEventBridge(String runId, int maxEvents, RuntimePendingEventGuard eventGuard) {
        this.runId = runId;
        this.eventGuard = eventGuard;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, maxEvents));
        this.sink = Sinks.many().unicast().onBackpressureBuffer(queue);
    }

    public Flux<ChatEvent> flux() {
        return sink.asFlux()
                .doOnDiscard(PendingBudgetedChatEvent.class, PendingBudgetedChatEvent::close)
                .doFinally(ignored -> dispose());
    }

    public void emit(ChatEvent event) {
        if (terminated.get()) {
            return;
        }
        ChatEvent reserved;
        try {
            reserved = eventGuard.reserve(runId, event);
        } catch (RuntimeException ex) {
            // 预算预留失败也必须立即闭合桥接，确保已接受事件排空后向下游传播原始异常。
            fail(ex);
            throw ex;
        }
        Sinks.EmitResult result = sink.tryEmitNext(reserved);
        if (result.isSuccess()) {
            return;
        }
        eventGuard.releaseDiscarded(reserved);
        RuntimeStreamLimitExceededException overflow = new RuntimeStreamLimitExceededException(
                RuntimeStreamLimitType.PENDING_EVENTS,
                "Runtime待消费桥接队列超过硬上限: runId=" + runId + ", result=" + result);
        fail(overflow);
        throw overflow;
    }

    public void complete() {
        if (terminated.compareAndSet(false, true)) {
            sink.tryEmitComplete();
        }
    }

    public void fail(Throwable failure) {
        if (terminated.compareAndSet(false, true)) {
            sink.tryEmitError(failure == null
                    ? new IllegalStateException("Runtime流异常结束")
                    : failure);
        }
    }

    public void attach(Disposable disposable) {
        if (!upstream.compareAndSet(null, disposable) && disposable != null) {
            disposable.dispose();
        }
        if (terminated.get() && disposable != null) {
            disposable.dispose();
        }
    }

    public void cleanupWith(Runnable action) {
        cleanup.set(action == null ? () -> { } : action);
    }

    private void dispose() {
        Disposable disposable = upstream.getAndSet(null);
        if (disposable != null) {
            disposable.dispose();
        }
        ChatEvent queued;
        while ((queued = queue.poll()) != null) {
            eventGuard.releaseDiscarded(queued);
        }
        cleanup.getAndSet(() -> { }).run();
    }
}
