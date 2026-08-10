package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.RuntimeStreamLimitsProperties;
import com.huawei.it.ex.one.application.service.runtime.RuntimeStreamLimitType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** 管理所有在途 run 的 assistant 历史投影内存预算。 */
@Component
final class AssistantAssemblyBudgetRegistry {
    private final RuntimeStreamLimitsProperties properties;
    private final ObjectMapper objectMapper;
    private final Object monitor = new Object();
    private long activeParts;
    private long activeBytes;
    private long activeProcessParts;
    private long activeProcessBytes;

    AssistantAssemblyBudgetRegistry(RuntimeStreamLimitsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    Budget open(String runId) {
        return new Budget(this, runId == null ? "" : runId);
    }

    long serializedBytes(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Assistant历史投影序列化大小计算失败", ex);
        }
    }

    long textBytes(String value) {
        return Utf8Text.bytes(value);
    }

    long activeParts() {
        synchronized (monitor) {
            return activeParts;
        }
    }

    long activeBytes() {
        synchronized (monitor) {
            return activeBytes;
        }
    }

    private ReserveResult tryReserve(Budget budget, long parts, long bytes, boolean process) {
        synchronized (monitor) {
            if (budget.closed.get()) {
                return ReserveResult.rejected(RuntimeStreamLimitType.ASSISTANT_BYTES, false);
            }
            long normalizedParts = Math.max(0L, parts);
            long normalizedBytes = Math.max(0L, bytes);
            RuntimeStreamLimitType totalLimit = totalLimit(budget, normalizedParts, normalizedBytes);
            if (totalLimit != null) {
                return ReserveResult.rejected(totalLimit, false);
            }
            if (process && processLimit(budget, normalizedParts, normalizedBytes)) {
                return ReserveResult.rejected(processLimitType(budget, normalizedParts, normalizedBytes), true);
            }
            reserveNow(budget, normalizedParts, normalizedBytes, process);
            return ReserveResult.granted();
        }
    }

    private ByteReservation reserveEssentialBytesUpTo(Budget budget, long requestedBytes) {
        synchronized (monitor) {
            if (budget.closed.get()) {
                return new ByteReservation(0L, RuntimeStreamLimitType.ASSISTANT_BYTES);
            }
            long requested = Math.max(0L, requestedBytes);
            long runRemaining = Math.max(0L, properties.assistantMaxBytesPerRun() - budget.bytes);
            long instanceRemaining = Math.max(0L,
                    properties.assistantMaxActiveBytesPerInstance() - activeBytes);
            long accepted = Math.min(requested, Math.min(runRemaining, instanceRemaining));
            if (accepted > 0L) {
                reserveNow(budget, 0L, accepted, false);
            }
            if (accepted == requested) {
                return new ByteReservation(accepted, null);
            }
            RuntimeStreamLimitType type = runRemaining <= instanceRemaining
                    ? RuntimeStreamLimitType.ASSISTANT_BYTES
                    : RuntimeStreamLimitType.ASSISTANT_INSTANCE_BYTES;
            return new ByteReservation(accepted, type);
        }
    }

    private boolean canReserve(Budget budget, long parts, long bytes) {
        synchronized (monitor) {
            return !budget.closed.get() && totalLimit(budget, Math.max(0L, parts), Math.max(0L, bytes)) == null;
        }
    }

    private RuntimeStreamLimitType totalLimit(Budget budget, long parts, long bytes) {
        if (saturatedAdd(budget.parts, parts) > properties.getAssistantMaxPartsPerRun()) {
            return RuntimeStreamLimitType.ASSISTANT_PARTS;
        }
        if (saturatedAdd(budget.bytes, bytes) > properties.assistantMaxBytesPerRun()) {
            return RuntimeStreamLimitType.ASSISTANT_BYTES;
        }
        if (saturatedAdd(activeParts, parts) > properties.getAssistantMaxActivePartsPerInstance()) {
            return RuntimeStreamLimitType.ASSISTANT_INSTANCE_PARTS;
        }
        if (saturatedAdd(activeBytes, bytes) > properties.assistantMaxActiveBytesPerInstance()) {
            return RuntimeStreamLimitType.ASSISTANT_INSTANCE_BYTES;
        }
        return null;
    }

    private boolean processLimit(Budget budget, long parts, long bytes) {
        return saturatedAdd(budget.processParts, parts) > properties.assistantProcessMaxPartsPerRun()
                || saturatedAdd(budget.processBytes, bytes) > properties.assistantProcessMaxBytesPerRun()
                || saturatedAdd(activeProcessParts, parts) > properties.assistantProcessMaxPartsPerInstance()
                || saturatedAdd(activeProcessBytes, bytes) > properties.assistantProcessMaxBytesPerInstance();
    }

    private RuntimeStreamLimitType processLimitType(Budget budget, long parts, long bytes) {
        if (saturatedAdd(budget.processParts, parts) > properties.assistantProcessMaxPartsPerRun()
                || saturatedAdd(activeProcessParts, parts) > properties.assistantProcessMaxPartsPerInstance()) {
            return RuntimeStreamLimitType.ASSISTANT_PARTS;
        }
        return RuntimeStreamLimitType.ASSISTANT_BYTES;
    }

    private void reserveNow(Budget budget, long parts, long bytes, boolean process) {
        budget.parts = saturatedAdd(budget.parts, parts);
        budget.bytes = saturatedAdd(budget.bytes, bytes);
        activeParts = saturatedAdd(activeParts, parts);
        activeBytes = saturatedAdd(activeBytes, bytes);
        if (process) {
            budget.processParts = saturatedAdd(budget.processParts, parts);
            budget.processBytes = saturatedAdd(budget.processBytes, bytes);
            activeProcessParts = saturatedAdd(activeProcessParts, parts);
            activeProcessBytes = saturatedAdd(activeProcessBytes, bytes);
        }
    }

    private void release(Budget budget, long parts, long bytes, boolean process) {
        synchronized (monitor) {
            long normalizedParts = Math.max(0L, parts);
            long normalizedBytes = Math.max(0L, bytes);
            budget.parts = Math.max(0L, budget.parts - normalizedParts);
            budget.bytes = Math.max(0L, budget.bytes - normalizedBytes);
            activeParts = Math.max(0L, activeParts - normalizedParts);
            activeBytes = Math.max(0L, activeBytes - normalizedBytes);
            if (process) {
                budget.processParts = Math.max(0L, budget.processParts - normalizedParts);
                budget.processBytes = Math.max(0L, budget.processBytes - normalizedBytes);
                activeProcessParts = Math.max(0L, activeProcessParts - normalizedParts);
                activeProcessBytes = Math.max(0L, activeProcessBytes - normalizedBytes);
            }
        }
    }

    private void close(Budget budget) {
        synchronized (monitor) {
            if (!budget.closed.compareAndSet(false, true)) {
                return;
            }
            activeParts = Math.max(0L, activeParts - budget.parts);
            activeBytes = Math.max(0L, activeBytes - budget.bytes);
            activeProcessParts = Math.max(0L, activeProcessParts - budget.processParts);
            activeProcessBytes = Math.max(0L, activeProcessBytes - budget.processBytes);
            budget.parts = 0L;
            budget.bytes = 0L;
            budget.processParts = 0L;
            budget.processBytes = 0L;
        }
    }

    private long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    static final class Budget implements AutoCloseable {
        private final AssistantAssemblyBudgetRegistry owner;
        private final String runId;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private long parts;
        private long bytes;
        private long processParts;
        private long processBytes;

        private Budget(AssistantAssemblyBudgetRegistry owner, String runId) {
            this.owner = owner;
            this.runId = runId;
        }

        ReserveResult reservePart(long bytes, boolean process) {
            return owner.tryReserve(this, 1L, bytes, process);
        }

        boolean canReserveEssentialPart(long bytes) {
            return owner.canReserve(this, 1L, bytes);
        }

        ByteReservation reserveEssentialBytesUpTo(long requestedBytes) {
            return owner.reserveEssentialBytesUpTo(this, requestedBytes);
        }

        boolean canReserveEssentialBytes(long bytes) {
            return owner.canReserve(this, 0L, bytes);
        }

        void releasePart(long bytes, boolean process) {
            owner.release(this, 1L, bytes, process);
        }

        void releaseBytes(long bytes) {
            owner.release(this, 0L, bytes, false);
        }

        String runId() {
            return runId;
        }

        @Override
        public void close() {
            owner.close(this);
        }
    }

    record ReserveResult(boolean accepted, RuntimeStreamLimitType limitType, boolean processLimit) {
        private static ReserveResult granted() {
            return new ReserveResult(true, null, false);
        }

        private static ReserveResult rejected(RuntimeStreamLimitType type, boolean processLimit) {
            return new ReserveResult(false, type, processLimit);
        }
    }

    record ByteReservation(long bytes, RuntimeStreamLimitType overflowType) {
        boolean complete(long requested) {
            return bytes >= Math.max(0L, requested) && overflowType == null;
        }
    }
}
