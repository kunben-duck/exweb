/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.observation;

import com.huawei.it.ex.one.application.config.ObservabilityProperties;
import com.huawei.it.ex.one.application.integration.observation.ObservationRepository;
import com.huawei.it.ex.one.domain.observation.AgentObservationSnapshot;
import com.huawei.it.ex.one.domain.observation.TurnTraceSnapshot;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 有界、失败开放的独立写入器；关闭时不创建线程池或事务模板。 */
public final class ObservationWriter implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final TransactionTemplate transaction;
    private final ObservationRepository repository;
    private final ObservationDiagnostics diagnostics;
    private final long queueDeadlineNanos;

    public ObservationWriter(ObservabilityProperties properties, ObservationRepository repository,
                             PlatformTransactionManager manager, ObservationDiagnostics diagnostics) {
        this.repository = repository;
        this.diagnostics = diagnostics;
        if (!properties.isEnabled()) {
            executor = null;
            transaction = null;
            queueDeadlineNanos = 0;
            return;
        }
        var config = properties.getWriter();
        queueDeadlineNanos = config.getQueueWaitTimeout().toNanos();
        AtomicInteger counter = new AtomicInteger();
        executor = new ThreadPoolExecutor(config.getWorkerCount(), config.getWorkerCount(),
                0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(config.getQueueCapacity()), task -> {
                    Thread thread = new Thread(task, "observation-writer-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        diagnostics.queue(executor);
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout((int) Math.max(1, config.getTransactionTimeout().toSeconds()
                + (config.getTransactionTimeout().getNano() == 0 ? 0 : 1)));
    }

    public void trace(TurnTraceSnapshot snapshot) { submit(() -> repository.saveTrace(snapshot)); }
    public void observation(AgentObservationSnapshot snapshot) { submit(() -> repository.saveObservation(snapshot)); }

    /** 业务事实必须在提交后入队，回滚时不发布成功快照。 */
    public void afterCommit(Runnable operation) {
        if (executor == null) { return; }
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()
                    && TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { isolated(operation); }
                });
            } else { isolated(operation); }
        } catch (RuntimeException ignored) { diagnostics.dropped("after_commit"); }
    }

    private void isolated(Runnable operation) {
        try { operation.run(); } catch (RuntimeException ignored) { diagnostics.dropped("snapshot"); }
    }

    private void submit(Runnable operation) {
        if (executor == null) { return; }
        long submittedAt = System.nanoTime();
        try {
            executor.execute(() -> {
                if (System.nanoTime() - submittedAt >= queueDeadlineNanos) {
                    diagnostics.dropped("queue_expired");
                    return;
                }
                try { transaction.executeWithoutResult(status -> operation.run()); }
                catch (RuntimeException ignored) { diagnostics.dropped("database"); }
            });
        } catch (RuntimeException ignored) { diagnostics.dropped("queue_rejected"); }
    }

    public int queueSize() { return executor == null ? 0 : executor.getQueue().size(); }
    @Override public void close() { if (executor != null) { executor.shutdownNow(); } }
}
