package com.huawei.it.ex.one.application.integration.agent;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime Interaction 回答的请求内发送状态。
 *
 * <p>该状态只在当前 JVM 的调用链中传递，不进入事件、metadata 或持久化数据。Relay 在回答成功
 * 进入 WebSocket outbound 后标记为已发送；发送后的连接失败属于结果未知，不能安全重发。</p>
 */
public final class RuntimeInteractionDispatchState {
    private final AtomicReference<Phase> phase;

    private RuntimeInteractionDispatchState(Phase initialPhase) {
        this.phase = new AtomicReference<>(initialPhase);
    }

    public static RuntimeInteractionDispatchState tracked() {
        return new RuntimeInteractionDispatchState(Phase.PENDING);
    }

    public static RuntimeInteractionDispatchState untracked() {
        return new RuntimeInteractionDispatchState(Phase.UNTRACKED);
    }

    public boolean trackedInteraction() {
        return phase.get() != Phase.UNTRACKED;
    }

    public boolean responseDispatched() {
        return phase.get() == Phase.DISPATCHED;
    }

    public void markResponseDispatched() {
        phase.updateAndGet(current -> current == Phase.UNTRACKED ? current : Phase.DISPATCHED);
    }

    public void markBindingRestored() {
        phase.compareAndSet(Phase.PENDING, Phase.BINDING_RESTORED);
    }

    public void markBindingRestoreFailed() {
        phase.compareAndSet(Phase.PENDING, Phase.BINDING_RESTORE_FAILED);
    }

    public boolean cancelInteractionAfterFailure() {
        Phase current = phase.get();
        return current == Phase.DISPATCHED || current == Phase.BINDING_RESTORE_FAILED;
    }

    private enum Phase {
        UNTRACKED,
        PENDING,
        BINDING_RESTORED,
        BINDING_RESTORE_FAILED,
        DISPATCHED
    }
}
