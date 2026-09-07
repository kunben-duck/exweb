/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.routing;

import com.huawei.it.ex.one.application.config.IntentFeedbackProperties;
import com.huawei.it.ex.one.application.integration.intent.IntentFeedbackException;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Bounds queue residence without timing out a transaction that has already started. */
@Component
public class IntentFeedbackTaskDispatcher {
    private final ThreadPoolExecutor executor;
    private final long waitNanos;
    private final Scheduler timer;

    @Autowired
    public IntentFeedbackTaskDispatcher(
            @Qualifier("intentFeedbackExecutor") ThreadPoolTaskExecutor executor,
            IntentFeedbackProperties properties) {
        this(executor.getThreadPoolExecutor(), properties.getQueueWaitTimeout(), Schedulers.parallel());
    }

    IntentFeedbackTaskDispatcher(ThreadPoolExecutor executor, Duration timeout, Scheduler timer) {
        this.executor = executor;
        this.waitNanos = timeout.toNanos();
        this.timer = timer;
    }

    public <T> Mono<T> submit(Callable<T> action) {
        return Mono.create(sink -> {
            QueuedTask<T> task = new QueuedTask<>(action, sink);
            sink.onCancel(task::cancel);
            task.submit();
        });
    }

    private enum State { WAITING, RUNNING, EXPIRED, CANCELLED, REJECTED }

    private final class QueuedTask<T> implements Runnable {
        private final Callable<T> action;
        private final MonoSink<T> sink;
        private final long submittedAt = System.nanoTime();
        private final AtomicReference<State> state = new AtomicReference<>(State.WAITING);
        private final Disposable.Swap timeout = Disposables.swap();

        private QueuedTask(Callable<T> action, MonoSink<T> sink) {
            this.action = action;
            this.sink = sink;
        }

        private void submit() {
            try {
                timeout.update(timer.schedule(this::expire, waitNanos, TimeUnit.NANOSECONDS));
                if (state.get() == State.WAITING) {
                    executor.execute(this);
                    // Expiration/cancellation can win between the check and enqueue.
                    if (state.get() != State.WAITING) {
                        executor.remove(this);
                    }
                }
            } catch (RuntimeException rejection) {
                if (state.compareAndSet(State.WAITING, State.REJECTED)) {
                    timeout.dispose();
                    sink.error(IntentFeedbackException.unavailable(rejection));
                }
            }
        }

        @Override
        public void run() {
            if (!state.compareAndSet(State.WAITING, State.RUNNING)) {
                return;
            }
            timeout.dispose();
            // A delayed timer must not allow work to start past its queue deadline.
            if (System.nanoTime() - submittedAt >= waitNanos) {
                state.set(State.EXPIRED);
                sink.error(queueTimeout());
                return;
            }
            try {
                sink.success(action.call());
            } catch (Throwable failure) {
                Exceptions.throwIfFatal(failure);
                sink.error(failure);
            }
        }

        private void expire() {
            if (state.compareAndSet(State.WAITING, State.EXPIRED)) {
                timeout.dispose();
                executor.remove(this);
                sink.error(queueTimeout());
            }
        }

        private void cancel() {
            if (state.compareAndSet(State.WAITING, State.CANCELLED)) {
                executor.remove(this);
            }
            timeout.dispose();
        }

        private IntentFeedbackException queueTimeout() {
            return IntentFeedbackException.unavailable(new TimeoutException("Intent feedback queue wait expired"));
        }
    }
}
