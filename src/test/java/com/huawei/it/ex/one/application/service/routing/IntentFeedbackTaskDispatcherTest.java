/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.IntentFeedbackExecutorConfiguration;
import com.huawei.it.ex.one.application.config.IntentFeedbackProperties;
import com.huawei.it.ex.one.application.config.IntentPreferenceExecutorConfiguration;
import com.huawei.it.ex.one.application.config.RouteMemoryProperties;
import com.huawei.it.ex.one.application.integration.intent.IntentFeedbackException;
import com.huawei.it.ex.one.domain.auth.UserContext;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.scheduler.VirtualTimeScheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class IntentFeedbackTaskDispatcherTest {
    private final List<CountDownLatch> releases = new ArrayList<>();
    private final List<ThreadPoolTaskExecutor> executors = new ArrayList<>();
    private ThreadPoolTaskExecutor executor;
    private IntentFeedbackTaskDispatcher dispatcher;

    @BeforeEach
    void setup() {
        executor = new IntentFeedbackExecutorConfiguration().intentFeedbackExecutor(new IntentFeedbackProperties());
        executors.add(executor);
        dispatcher = new IntentFeedbackTaskDispatcher(executor, new IntentFeedbackProperties());
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        releases.forEach(CountDownLatch::countDown);
        for (ThreadPoolTaskExecutor pool : executors) {
            pool.shutdown();
            assertThat(pool.getThreadPoolExecutor().awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void queuedGetAndPostExpireBeforeTransactionAndNeverRunLater() throws Exception {
        CountDownLatch release = block(executor);
        IntentFeedbackCommitService commits = mock(IntentFeedbackCommitService.class);
        IntentFeedbackApplicationService service = new IntentFeedbackApplicationService(commits, dispatcher);
        UserContext user = new UserContext("t", "u", "u");
        long start = System.nanoTime();
        CompletableFuture<?> get = service.find(user, "run").toFuture();
        CompletableFuture<?> post = service.record(user, "run",
                new IntentFeedbackCommand("CORRECT", null, null, null, null, null)).toFuture();
        assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(2);
        unavailable(get);
        unavailable(post);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(450), Duration.ofSeconds(2));
        assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
        verifyNoInteractions(commits);
        release.countDown();
        drain();
        verifyNoInteractions(commits);
    }

    @Test
    void fullQueueRejectsImmediatelyWithoutCallerRuns() throws Exception {
        block(executor);
        AtomicInteger calls = new AtomicInteger();
        IntentFeedbackTaskDispatcher slowDeadline = dispatcher(Duration.ofSeconds(10), Schedulers.parallel());
        List<CompletableFuture<Integer>> queued = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            queued.add(slowDeadline.submit(calls::incrementAndGet).toFuture());
        }
        CompletableFuture<Integer> rejected = slowDeadline.submit(calls::incrementAndGet).toFuture();
        assertThat(rejected).isCompletedExceptionally();
        unavailable(rejected);
        assertThat(calls).hasValue(0);
        queued.forEach(future -> future.cancel(false));
        assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
    }

    @Test
    void cancellationRemovesQueuedTasksAndTheirTimers() throws Exception {
        CountDownLatch release = block(executor);
        VirtualTimeScheduler timer = VirtualTimeScheduler.create();
        try {
            IntentFeedbackTaskDispatcher queued = dispatcher(Duration.ofSeconds(10), timer);
            AtomicInteger calls = new AtomicInteger();
            for (int i = 0; i < 64; i++) {
                CompletableFuture<Integer> future = queued.submit(calls::incrementAndGet).toFuture();
                assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(1);
                future.cancel(false);
                assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
            }
            timer.advanceTimeBy(Duration.ofSeconds(20));
            release.countDown();
            drain();
            assertThat(calls).hasValue(0);
        } finally {
            timer.dispose();
        }
    }

    @Test
    void delayedTimerCannotStartExpiredWork() throws Exception {
        CountDownLatch release = block(executor);
        VirtualTimeScheduler timer = VirtualTimeScheduler.create();
        try {
            AtomicInteger calls = new AtomicInteger();
            CompletableFuture<Integer> future = dispatcher(Duration.ofMillis(50), timer)
                    .submit(calls::incrementAndGet).toFuture();
            Thread.sleep(80);
            assertThat(future).isNotDone();
            release.countDown();
            unavailable(future);
            assertThat(calls).hasValue(0);
            assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
        } finally {
            timer.dispose();
        }
    }

    @Test
    void startedTransactionIsNotTimedOutOrInterruptedByQueueDeadline() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = releaseLatch();
        CompletableFuture<String> future = dispatcher(Duration.ofMillis(100), Schedulers.parallel()).submit(() -> {
            entered.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return "committed";
        }).toFuture();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(150);
        assertThat(future).isNotDone();
        release.countDown();
        assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo("committed");
    }

    @Test
    void cancellingStartedTaskDoesNotInterruptItsTransaction() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = releaseLatch();
        AtomicInteger commits = new AtomicInteger();
        CompletableFuture<Integer> future = dispatcher.submit(() -> {
            entered.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return commits.incrementAndGet();
        }).toFuture();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        future.cancel(true);
        release.countDown();
        drain();
        assertThat(commits).hasValue(1);
    }

    @Test
    void expirationAndWorkerStartHaveOnlyOneWinner() throws Exception {
        VirtualTimeScheduler timer = VirtualTimeScheduler.create();
        try {
            IntentFeedbackTaskDispatcher racing = dispatcher(Duration.ofSeconds(10), timer);
            for (int i = 0; i < 40; i++) {
                CountDownLatch release = block(executor);
                AtomicInteger calls = new AtomicInteger();
                CompletableFuture<Integer> future = racing.submit(calls::incrementAndGet).toFuture();
                CompletableFuture<Void> expiry = CompletableFuture.runAsync(
                        () -> timer.advanceTimeBy(Duration.ofSeconds(10)));
                release.countDown();
                expiry.get(2, TimeUnit.SECONDS);
                try {
                    assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo(1);
                    assertThat(calls).hasValue(1);
                } catch (ExecutionException expired) {
                    assertThat(expired.getCause()).isInstanceOf(IntentFeedbackException.class);
                    drain();
                    assertThat(calls).hasValue(0);
                }
                drain();
                assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
            }
        } finally {
            timer.dispose();
        }
    }

    @Test
    void expiredTaskEnqueuedAfterTimerFiredIsRemoved() throws Exception {
        CountDownLatch release = block(executor);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        VirtualTimeScheduler timer = VirtualTimeScheduler.create();
        try {
            // Expire after execute() is entered but before the real queue insertion.
            ThreadPoolExecutor delayed = mock(ThreadPoolExecutor.class);
            doAnswer(call -> {
                Runnable task = call.getArgument(0);
                submitted.set(task);
                timer.advanceTimeBy(Duration.ofSeconds(10));
                executor.getThreadPoolExecutor().execute(task);
                return null;
            }).when(delayed).execute(any());
            when(delayed.remove(any())).thenAnswer(call ->
                    executor.getThreadPoolExecutor().remove(call.getArgument(0)));
            CompletableFuture<Integer> result = new IntentFeedbackTaskDispatcher(delayed, Duration.ofSeconds(10), timer)
                    .submit(calls::incrementAndGet).toFuture();
            unavailable(result);
            assertThat(submitted).doesNotHaveValue(null);
            assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
            release.countDown();
            drain();
            assertThat(calls).hasValue(0);
        } finally {
            timer.dispose();
        }
    }

    @Test
    void independentSubscriptionsExecuteOnceEachAndFailuresDoNotBlockNextTask() {
        AtomicInteger calls = new AtomicInteger();
        Mono<Integer> work = dispatcher.submit(calls::incrementAndGet);
        assertThat(work.block(Duration.ofSeconds(2))).isEqualTo(1);
        assertThat(work.block(Duration.ofSeconds(2))).isEqualTo(2);
        assertThatThrownBy(() -> dispatcher.submit(() -> { throw new IllegalArgumentException("invalid"); }).block())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.submit(() -> { throw new AssertionError("non-fatal"); })
                .block(Duration.ofSeconds(2))).hasCauseInstanceOf(AssertionError.class);
        assertThat(work.block(Duration.ofSeconds(2))).isEqualTo(3);
    }

    @Test
    void timerRejectionCannotEnqueueDatabaseWork() {
        Scheduler timer = Schedulers.newSingle("feedback-test-timer");
        timer.dispose();
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> dispatcher(Duration.ofSeconds(1), timer).submit(calls::incrementAndGet).block())
                .isInstanceOf(IntentFeedbackException.class);
        assertThat(calls).hasValue(0);
        assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
    }

    @Test
    void taskStartCancellationAndRejectionDisposeScheduledTimers() throws Exception {
        Scheduler timer = mock(Scheduler.class);
        AtomicReference<Disposable> latestTimer = new AtomicReference<>();
        when(timer.schedule(any(), anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(call -> {
            Disposable scheduled = Disposables.single();
            latestTimer.set(scheduled);
            return scheduled;
        });
        IntentFeedbackTaskDispatcher tracked = dispatcher(Duration.ofSeconds(10), timer);
        assertThat(tracked.submit(() -> "ok").block(Duration.ofSeconds(2))).isEqualTo("ok");
        assertThat(latestTimer.get().isDisposed()).isTrue();
        block(executor);
        CompletableFuture<Integer> queued = tracked.submit(() -> 1).toFuture();
        assertThat(latestTimer.get().isDisposed()).isFalse();
        queued.cancel(false);
        assertThat(latestTimer.get().isDisposed()).isTrue();
        executor.shutdown();
        unavailable(tracked.submit(() -> 1).toFuture());
        assertThat(latestTimer.get().isDisposed()).isTrue();
    }

    @Test
    void feedbackAndExistingPreferenceWriterAreIsolatedInBothDirections() throws Exception {
        ThreadPoolTaskExecutor writer = new IntentPreferenceExecutorConfiguration()
                .intentPreferenceWriteExecutor(new RouteMemoryProperties());
        executors.add(writer);
        CountDownLatch feedbackRelease = block(executor);
        assertThat(Mono.fromCallable(() -> Thread.currentThread().getName())
                .subscribeOn(Schedulers.fromExecutor(writer)).block(Duration.ofSeconds(1)))
                .startsWith("finex-intent-preference-write-");
        feedbackRelease.countDown();
        block(writer);
        assertThat(dispatcher.submit(() -> Thread.currentThread().getName()).block(Duration.ofSeconds(1)))
                .startsWith("finex-intent-feedback-");
    }

    private IntentFeedbackTaskDispatcher dispatcher(Duration wait, Scheduler timer) {
        return new IntentFeedbackTaskDispatcher(executor.getThreadPoolExecutor(), wait, timer);
    }

    private CountDownLatch releaseLatch() {
        CountDownLatch release = new CountDownLatch(1);
        releases.add(release);
        return release;
    }

    private CountDownLatch block(ThreadPoolTaskExecutor pool) throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = releaseLatch();
        pool.execute(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        return release;
    }

    private void drain() throws Exception {
        executor.getThreadPoolExecutor().submit(() -> {}).get(2, TimeUnit.SECONDS);
    }

    private void unavailable(CompletableFuture<?> future) {
        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IntentFeedbackException.class)
                .extracting("code").isEqualTo("INTENT_FEEDBACK_UNAVAILABLE");
    }
}
