package com.huawei.it.ex.one.intent.application.service;

import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.intent.application.config.RouteMemoryProperties;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Package-local execution and circuit-breaker policy for RouteMemory IO. */
final class RouteMemoryIoExecutor {
    private final RouteMemoryProperties properties;
    private final Executor readExecutor;
    private final Executor writeExecutor;
    private final AppLogger log;
    private final AtomicInteger consecutiveReadFailures = new AtomicInteger();
    private final AtomicReference<Instant> readCircuitOpenUntil = new AtomicReference<>(Instant.EPOCH);

    RouteMemoryIoExecutor(
            RouteMemoryProperties properties,
            Executor readExecutor,
            Executor writeExecutor,
            AppLogger log) {
        this.properties = properties;
        this.readExecutor = readExecutor;
        this.writeExecutor = writeExecutor;
        this.log = log;
    }

    <T> T readSafely(
            String operation,
            UserContext user,
            String sessionId,
            T fallback,
            Supplier<T> supplier) {
        Instant openUntil = readCircuitOpenUntil.get();
        if (openUntil != null && Instant.now().isBefore(openUntil)) {
            log.debug("RouteMemory read circuit is open, fallback to empty context. operation={}, tenantId={}, userId={}, sessionId={}, openUntil={}",
                    operation, user.tenantId(), user.ownerUserId(), sessionId, openUntil);
            return fallback;
        }
        CompletableFuture<T> future = null;
        try {
            future = CompletableFuture.supplyAsync(supplier, readExecutor);
            T result = future.get(properties.normalizedReadTimeout().toMillis(), TimeUnit.MILLISECONDS);
            recordReadSuccess();
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            cancelFuture(future);
            recordReadFailure();
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_READ_FAILED,
                            "RouteMemory read was interrupted; falling back to an empty context")
                    .sessionId(sessionId)
                    .operation(operation)
                    .build(), ex);
            return fallback;
        } catch (TimeoutException ex) {
            cancelFuture(future);
            recordReadFailure();
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_QUERY_TIMEOUT,
                            "RouteMemory read timed out; falling back to an empty context")
                    .sessionId(sessionId)
                    .operation(operation)
                    .attribute("timeout", properties.normalizedReadTimeout())
                    .build(), ex);
            return fallback;
        } catch (RuntimeException ex) {
            cancelFuture(future);
            recordReadFailure();
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_READ_FAILED,
                            "RouteMemory read failed; falling back to an empty context")
                    .sessionId(sessionId)
                    .operation(operation)
                    .build(), ex);
            return fallback;
        } catch (Exception ex) {
            cancelFuture(future);
            recordReadFailure();
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_READ_FAILED,
                            "RouteMemory read failed; falling back to an empty context")
                    .sessionId(sessionId)
                    .operation(operation)
                    .build(), ex);
            return fallback;
        }
    }

    void writeSafely(String operation, String sessionId, Runnable task) {
        try {
            writeExecutor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException ex) {
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_WRITE_FAILED,
                                    "RouteMemory write failed and was ignored")
                            .sessionId(sessionId)
                            .operation(operation)
                            .build(), ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.TASK_REJECTED,
                            "RouteMemory write queue rejected a task")
                    .sessionId(sessionId)
                    .operation(operation)
                    .build(), ex);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.TASK_REJECTED,
                            "RouteMemory write scheduling failed")
                    .sessionId(sessionId)
                    .operation(operation)
                    .build(), ex);
        }
    }

    private void cancelFuture(CompletableFuture<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private void recordReadSuccess() {
        consecutiveReadFailures.set(0);
        readCircuitOpenUntil.set(Instant.EPOCH);
    }

    private void recordReadFailure() {
        int failures = consecutiveReadFailures.incrementAndGet();
        if (failures < properties.normalizedCircuitBreakerFailureThreshold()) {
            return;
        }
        Instant openUntil = Instant.now().plus(properties.normalizedCircuitBreakerOpenDuration());
        readCircuitOpenUntil.set(openUntil);
        consecutiveReadFailures.set(0);
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_UNAVAILABLE,
                        "RouteMemory read circuit opened after repeated failures")
                .operation("route-memory.read-circuit.open")
                .attribute("failureThreshold", properties.normalizedCircuitBreakerFailureThreshold())
                .attribute("openDuration", properties.normalizedCircuitBreakerOpenDuration())
                .attribute("openUntil", openUntil)
                .build());
    }
}
