/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.observation;

import static com.huawei.it.ex.one.application.service.observation.ObservationTestFixture.await;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.ObservabilityProperties;
import com.huawei.it.ex.one.application.integration.observation.ObservationRepository;
import com.huawei.it.ex.one.domain.observation.AgentObservationSnapshot;
import com.huawei.it.ex.one.domain.observation.TurnTraceSnapshot;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class ObservationWriterTest {
    @Test
    void fullAndExpiredQueueNeverExecutesOnCallerOrWritesLate() throws Exception {
        var properties = properties();
        properties.getWriter().setQueueCapacity(1);
        properties.getWriter().setQueueWaitTimeout(Duration.ofMillis(30));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        List<String> threads = new CopyOnWriteArrayList<>();
        var registry = new SimpleMeterRegistry();
        var repository = new ObservationRepository() {
            @Override public void saveTrace(TurnTraceSnapshot snapshot) {
                writes.incrementAndGet();
                threads.add(Thread.currentThread().getName());
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
            @Override public void saveObservation(AgentObservationSnapshot snapshot) { throw new AssertionError(); }
        };
        try (var writer = new ObservationWriter(properties, repository,
                new ObservationTestFixture.LocalTransactions(), new ObservationDiagnostics(registry))) {
            writer.trace(snapshot());
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            writer.trace(snapshot());
            writer.trace(snapshot());
            assertThat(writer.queueSize()).isEqualTo(1);
            assertThat(registry.get("financeex.observation.dropped").tag("reason", "queue_rejected")
                    .counter().count()).isEqualTo(1);
            Thread.sleep(60);
            release.countDown();
            await(() -> registry.find("financeex.observation.dropped").tag("reason", "queue_expired")
                    .counter() != null);
            assertThat(writes).hasValue(1);
            assertThat(threads).allMatch(name -> name.startsWith("observation-writer-"));
            assertThat(writer.queueSize()).isZero();
        } finally { release.countDown(); registry.close(); }
    }

    @Test
    void databaseFailureIsIsolatedAndNextTaskCanRunWithoutRetry() {
        AtomicInteger writes = new AtomicInteger();
        var registry = new SimpleMeterRegistry();
        var repository = new ObservationRepository() {
            @Override public void saveTrace(TurnTraceSnapshot snapshot) {
                if (writes.incrementAndGet() == 1) { throw new IllegalStateException("database unavailable"); }
            }
            @Override public void saveObservation(AgentObservationSnapshot snapshot) { }
        };
        var writer = new ObservationWriter(properties(), repository,
                new ObservationTestFixture.LocalTransactions(), new ObservationDiagnostics(registry));
        try {
            writer.trace(snapshot());
            writer.trace(snapshot());
            await(() -> writes.get() == 2);
            assertThat(registry.get("financeex.observation.dropped").tag("reason", "database")
                    .counter().count()).isEqualTo(1);
        } finally { writer.close(); }
        writer.trace(snapshot());
        assertThat(registry.get("financeex.observation.dropped").tag("reason", "queue_rejected")
                .counter().count()).isEqualTo(1);
        registry.close();
    }

    private ObservabilityProperties properties() {
        var properties = new ObservabilityProperties();
        properties.setEnabled(true);
        return properties;
    }

    private TurnTraceSnapshot snapshot() {
        Instant now = Instant.now();
        return new TurnTraceSnapshot("turn_test", "t", "u", "session", "run", "message", null, null,
                "run", "NEXT", 1, 1, "RUNNING", false, now, null, null, null, null, null, now, now);
    }
}
