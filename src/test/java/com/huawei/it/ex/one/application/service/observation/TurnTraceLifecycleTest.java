/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.observation;

import static com.huawei.it.ex.one.application.service.observation.ObservationTestFixture.await;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.auth.UserContext;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class TurnTraceLifecycleTest {
    @Test
    void remoteCancellationReleasesBothInstancesWithoutInventingLocalTerminal() {
        try (var owner = new ObservationTestFixture(); var remote = new ObservationTestFixture()) {
            var run = owner.start("remote-stop");
            remote.turns.committed(run.cancelled(10), null);
            owner.turns.finishStart(run.id());
            assertThat(owner.turns.context(run.id())).isNull();
            assertThat(remote.turns.context(run.id())).isNull();
            assertThat(owner.traces).noneMatch(trace -> "CANCELLED".equals(trace.status())
                    || "FAILED".equals(trace.status()));
        }
    }

    @Test
    void thousandOwnershipLossesAndRolledBackTerminalsLeaveNoLocalState() {
        try (var f = new ObservationTestFixture()) {
            for (int index = 0; index < 1000; index++) {
                var run = f.start("lost-" + index);
                if (index % 2 == 0) {
                    new TransactionTemplate(f.transactions).executeWithoutResult(tx -> {
                        f.turns.committed(run.failed(3), "TEST_FAILURE");
                        tx.setRollbackOnly();
                    });
                }
                f.turns.finishStart(run.id());
            }
            assertEmpty(f.turns, "states");
            assertEmpty(f.turns, "pendingContinuations");
        }
    }

    @Test
    void failureWhileSamplingUnadmittedRootStillReleasesExecutionAndPendingStart() {
        try (var f = new ObservationTestFixture()) {
            AtomicBoolean fail = new AtomicBoolean();
            var turns = new TurnTraceCoordinator(true, f.writer, new DefaultFirstBusinessFrameClassifier(),
                    f.diagnostics, f.clock, () -> {
                        if (fail.get()) { throw new IllegalStateException("sampling failed"); }
                        return f.nanos.get();
                    });
            turns.prepareContinuation("new-run");
            turns.begin(new UserContext("t", "u", "u"), "new-run", "NEXT", "session");
            fail.set(true);
            turns.finishStart("new-run");
            turns.finishStart("new-run");
            assertEmpty(turns, "states");
            assertEmpty(turns, "pendingContinuations");
        }
    }

    @Test
    void readingDecoratingAndLateCommitsDoNotRegisterPassiveState() {
        try (var f = new ObservationTestFixture()) {
            var run = f.start("ended");
            var original = f.turns.context(run.id());
            f.turns.finishStart(run.id());
            assertThat(f.turns.restore(run, false)).isEqualTo(original);
            assertThat(f.turns.decorate(run).metadata()).isEqualTo(run.metadata());
            f.turns.committed(run, null);
            f.turns.committed(run.cancelling("USER_STOP"), null);
            f.turns.committed(run.cancelled(5), null);
            assertEmpty(f.turns, "states");
        }
    }

    @Test
    void concurrentStopScopesPreserveLatestSamplesUntilAllActualOperationsFinish() {
        try (var f = new ObservationTestFixture()) {
            var run = f.start("local-stop");
            f.advance(Duration.ofMillis(25));
            f.turns.sample(run.id(), false, null);
            f.turns.sample(run.id(), true, "last-observation");
            var original = f.turns.context(run.id());
            var first = f.turns.openScope(run, true);
            var second = f.turns.openScope(run, true);
            var processing = first.retain();
            f.turns.finishStart(run.id());
            first.close();
            first.close();
            second.close();
            f.turns.forget(run.id());
            assertThat(f.turns.context(run.id())).isEqualTo(original);
            new TransactionTemplate(f.transactions).executeWithoutResult(tx -> {
                f.turns.committed(run.cancelled(8), null);
                processing.close();
                assertEmpty(f.turns, "states");
            });
            await(() -> f.traces.stream().anyMatch(trace -> "CANCELLED".equals(trace.status())));
            var ended = f.traces.getLast();
            assertThat(ended.traceId()).isEqualTo(original.traceId());
            assertThat(ended.firstBusinessFrameLatencyMs()).isEqualTo(25);
            assertThat(ended.isRefusal()).isTrue();
            assertEmpty(f.turns, "states");
        }
    }

    @Test
    void scopeCloseOnRollbackAndWriterFailureDoesNotRetainRestoredState() {
        try (var f = new ObservationTestFixture()) {
            var run = f.start("rollback-stop");
            f.turns.finishStart(run.id());
            await(() -> f.traces.size() == 2);
            new TransactionTemplate(f.transactions).executeWithoutResult(tx -> {
                try (var scope = f.turns.openScope(run, true)) {
                    f.turns.committed(run.cancelled(5), null);
                }
                tx.setRollbackOnly();
            });
            assertEmpty(f.turns, "states");
            assertThat(f.traces).hasSize(2);
            f.writer.close();
            try (var scope = f.turns.openScope(run, true)) {
                f.turns.committed(run.cancelled(5), null);
            }
            assertEmpty(f.turns, "states");
        }
    }

    @Test
    void operationStartingAfterSubscriberCancellationUsesCapturedSnapshotOnlyWhileRunning() {
        try (var f = new ObservationTestFixture()) {
            var run = f.start("cancel-race");
            f.turns.sample(run.id(), true, null);
            var scope = f.turns.openScope(run, true);
            f.turns.finishStart(run.id());
            scope.close();
            assertEmpty(f.turns, "states");
            try (var actualOperation = scope.retain()) {
                assertThat(f.turns.context(run.id()).refusal()).isTrue();
            }
            assertEmpty(f.turns, "states");
        }
    }

    @Test
    void oldScopeCannotRemoveReplacementState() {
        try (var f = new ObservationTestFixture()) {
            var run = f.start("same-key");
            var old = f.turns.openScope(run, true);
            f.turns.finishStart(run.id());
            f.turns.begin(new UserContext("t", "u", "u"), run.id(), "NEXT", "session");
            var replacement = f.turns.context(run.id());
            old.close();
            old.close();
            assertThat(f.turns.context(run.id())).isEqualTo(replacement);
            f.turns.finishStart(run.id());
            assertEmpty(f.turns, "states");
        }
    }

    @Test
    void concurrentAcquireAndLastReleaseLeaveNoOrphanState() throws Exception {
        try (var f = new ObservationTestFixture(); var workers = Executors.newFixedThreadPool(4)) {
            var run = f.start("contended");
            f.turns.finishStart(run.id());
            CountDownLatch start = new CountDownLatch(1);
            var results = new ArrayList<Future<?>>();
            for (int index = 0; index < 4; index++) {
                results.add(workers.submit(() -> {
                    try {
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        for (int attempt = 0; attempt < 250; attempt++) {
                            try (var scope = f.turns.openScope(run, false)) {
                                assertThat(f.turns.context(run.id())).isNotNull();
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                    }
                }));
            }
            start.countDown();
            for (var result : results) { result.get(10, TimeUnit.SECONDS); }
            assertEmpty(f.turns, "states");
        }
    }

    @Test
    void delayedCommitDoesNotResurrectFinishedExecutionAndWaitsRestoreFromMetadata() {
        try (var f = new ObservationTestFixture()) {
            var run = f.start("delayed");
            new TransactionTemplate(f.transactions).executeWithoutResult(tx -> {
                f.turns.committed(run.completed(5), null);
                f.turns.finishStart(run.id());
                assertEmpty(f.turns, "states");
            });
            await(() -> f.traces.stream().anyMatch(trace -> "COMPLETED".equals(trace.status())));
            assertEmpty(f.turns, "states");
            var waiting = f.start("waiting");
            f.turns.committed(waiting.waitingUser(8), null);
            f.turns.finishStart(waiting.id());
            assertThat(f.turns.restore(waiting, false)).isNotNull();
            var async = f.start("async");
            f.turns.committed(async.withMetadata(Map.of("_domainAgentAsyncTask", Map.of())), null);
            f.turns.finishStart(async.id());
            assertEmpty(f.turns, "states");
        }
    }

    @Test
    void disabledScopesDoNotCreateLegacyIdsOrState() {
        try (var f = new ObservationTestFixture(false, event -> { throw new AssertionError("classifier"); })) {
            var run = f.run("legacy", "user", Map.of());
            try (var scope = f.turns.openScope(run, true); var actualOperation = scope.retain()) {
                assertThat(f.turns.restore(run, true)).isNull();
            }
            assertEmpty(f.turns, "states");
            assertThat(f.traces).isEmpty();
        }
    }

    private static void assertEmpty(TurnTraceCoordinator turns, String field) {
        assertThat(((Map<?, ?>) ReflectionTestUtils.getField(turns, field)).size()).as(field).isZero();
    }
}
