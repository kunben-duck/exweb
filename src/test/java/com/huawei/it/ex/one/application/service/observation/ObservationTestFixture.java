/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.observation;

import com.huawei.it.ex.one.application.config.ObservabilityProperties;
import com.huawei.it.ex.one.application.integration.observation.FirstBusinessFrameClassifier;
import com.huawei.it.ex.one.application.integration.observation.ObservationRepository;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.observation.AgentObservationSnapshot;
import com.huawei.it.ex.one.domain.observation.TurnTraceSnapshot;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** 使用真实有界writer及事务同步，存储替身仅收集快照，不模拟真实数据库锁。 */
public final class ObservationTestFixture implements AutoCloseable {
    public final List<TurnTraceSnapshot> traces = new CopyOnWriteArrayList<>();
    public final List<AgentObservationSnapshot> calls = new CopyOnWriteArrayList<>();
    public final LocalTransactions transactions = new LocalTransactions();
    public final TestClock clock = new TestClock();
    public final AtomicLong nanos = new AtomicLong(1_000_000);
    public final ObservationDiagnostics diagnostics = new ObservationDiagnostics(null);
    public final ObservationWriter writer;
    public final TurnTraceCoordinator turns;
    public final AgentObservationRecorder recorder;

    public ObservationTestFixture() {
        this(true, new DefaultFirstBusinessFrameClassifier());
    }

    public ObservationTestFixture(boolean enabled, FirstBusinessFrameClassifier classifier) {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setEnabled(enabled);
        writer = new ObservationWriter(properties, new ObservationRepository() {
            @Override public void saveTrace(TurnTraceSnapshot snapshot) { traces.add(snapshot); }
            @Override public void saveObservation(AgentObservationSnapshot snapshot) { calls.add(snapshot); }
        }, transactions, diagnostics);
        turns = new TurnTraceCoordinator(enabled, writer, classifier, diagnostics, clock, nanos::get);
        recorder = new AgentObservationRecorder(turns, writer, classifier, diagnostics, clock, nanos::get);
    }

    /** 既有内存编排fixture没有Spring容器；仅给图中已有组件安装可选观测依赖。 */
    public void wire(Object root) {
        wire(root, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private void wire(Object value, java.util.Set<Object> seen) {
        if (value == null || value instanceof Enum<?> || !seen.add(value)) { return; }
        if (value instanceof Map<?, ?> map) { map.values().forEach(item -> wire(item, seen)); return; }
        if (value instanceof Iterable<?> items) { items.forEach(item -> wire(item, seen)); return; }
        String name = value.getClass().getName();
        if ((!name.startsWith("com.huawei.it.ex.one.application")
                && !name.startsWith("com.huawei.it.ex.one.infrastructure"))
                || name.contains(".observation.")) { return; }
        for (Class<?> type = value.getClass(); type != null && type.getName().startsWith("com.huawei."); type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) { continue; }
                try {
                    field.setAccessible(true);
                    if (field.getType() == TurnTraceCoordinator.class) { field.set(value, turns); }
                    else if (field.getType() == AgentObservationRecorder.class) { field.set(value, recorder); }
                    else { wire(field.get(value), seen); }
                } catch (IllegalAccessException ex) { throw new AssertionError(ex); }
            }
        }
    }

    public ChatRun start(String runId) {
        turns.begin(new UserContext("t", "u", "u"), runId, "NEXT", "session");
        ChatRun run = turns.decorate(run(runId, "user-1", Map.of()));
        turns.committed(run, null);
        return run;
    }

    public ChatRun run(String runId, String userMessageId, Map<String, Object> metadata) {
        return new ChatRun(runId, "t", "u", "session", ChatRunStatus.RUNNING, "DOMAIN_AGENT", "skill-a",
                "domain-agent", "real-downstream-session", ChatRunMode.NEXT, null, userMessageId, null,
                null, null, null, clock.instant(), null, metadata, clock.instant(), clock.instant());
    }

    public void advance(Duration duration) {
        clock.value = clock.value.plus(duration);
        nanos.addAndGet(duration.toNanos());
    }

    public static void await(BooleanSupplier ready) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() > deadline) { throw new AssertionError("Observation did not arrive"); }
            try { Thread.sleep(5); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
        }
    }

    @Override public void close() { writer.close(); }

    public static final class LocalTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }

    public static final class TestClock extends Clock {
        public volatile Instant value = Instant.parse("2026-10-18T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
