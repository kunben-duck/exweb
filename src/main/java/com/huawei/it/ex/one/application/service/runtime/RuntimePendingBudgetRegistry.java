package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.application.config.RuntimeStreamLimitsProperties;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 管理Runtime桥接中尚未交给Event管线的本机内存预算。 */
@Component
public class RuntimePendingBudgetRegistry {
    private final RuntimeStreamLimitsProperties properties;
    private final Object monitor = new Object();
    private final Map<String, Usage> usages = new HashMap<>();
    private long instanceEvents;
    private long instanceBytes;

    public RuntimePendingBudgetRegistry(RuntimeStreamLimitsProperties properties) {
        this.properties = properties;
    }

    public Reservation reserve(String runId, long bytes) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Runtime pending预算缺少runId");
        }
        long normalizedBytes = Math.max(0L, bytes);
        synchronized (monitor) {
            Usage usage = usages.computeIfAbsent(runId, ignored -> new Usage());
            RuntimeStreamLimitType rejected = rejectedType(usage, normalizedBytes);
            if (rejected != null) {
                if (usage.events == 0 && usage.bytes == 0) {
                    usages.remove(runId);
                }
                throw new RuntimeStreamLimitExceededException(rejected,
                        "Runtime待消费队列超过硬上限: runId=" + runId + ", limitType=" + rejected);
            }
            usage.events++;
            usage.bytes = saturatedAdd(usage.bytes, normalizedBytes);
            instanceEvents++;
            instanceBytes = saturatedAdd(instanceBytes, normalizedBytes);
            return new Reservation(this, runId, normalizedBytes);
        }
    }

    public void releaseRun(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        synchronized (monitor) {
            Usage removed = usages.remove(runId);
            if (removed == null) {
                return;
            }
            instanceEvents = Math.max(0L, instanceEvents - removed.events);
            instanceBytes = Math.max(0L, instanceBytes - removed.bytes);
        }
    }

    long instanceEvents() {
        synchronized (monitor) {
            return instanceEvents;
        }
    }

    long instanceBytes() {
        synchronized (monitor) {
            return instanceBytes;
        }
    }

    private RuntimeStreamLimitType rejectedType(Usage usage, long bytes) {
        if (usage.events + 1L > properties.getPendingMaxEventsPerRun()) {
            return RuntimeStreamLimitType.PENDING_EVENTS;
        }
        if (saturatedAdd(usage.bytes, bytes) > properties.pendingMaxBytesPerRun()) {
            return RuntimeStreamLimitType.PENDING_BYTES;
        }
        if (instanceEvents + 1L > properties.getPendingMaxEventsPerInstance()) {
            return RuntimeStreamLimitType.PENDING_INSTANCE_EVENTS;
        }
        if (saturatedAdd(instanceBytes, bytes) > properties.pendingMaxBytesPerInstance()) {
            return RuntimeStreamLimitType.PENDING_INSTANCE_BYTES;
        }
        return null;
    }

    private void release(String runId, long bytes) {
        synchronized (monitor) {
            Usage usage = usages.get(runId);
            if (usage == null || usage.events <= 0) {
                return;
            }
            usage.events--;
            usage.bytes = Math.max(0L, usage.bytes - bytes);
            instanceEvents = Math.max(0L, instanceEvents - 1L);
            instanceBytes = Math.max(0L, instanceBytes - bytes);
            if (usage.events == 0 && usage.bytes == 0) {
                usages.remove(runId);
            }
        }
    }

    private long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static final class Usage {
        private long events;
        private long bytes;
    }

    public static final class Reservation implements AutoCloseable {
        private final RuntimePendingBudgetRegistry owner;
        private final String runId;
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Reservation(RuntimePendingBudgetRegistry owner, String runId, long bytes) {
            this.owner = owner;
            this.runId = runId;
            this.bytes = bytes;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                owner.release(runId, bytes);
            }
        }
    }
}
