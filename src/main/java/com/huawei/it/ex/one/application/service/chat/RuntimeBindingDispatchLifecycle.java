package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 记录本轮 Runtime 订阅前需要补偿的 Binding 激活状态。 */
final class RuntimeBindingDispatchLifecycle {
    private final AtomicReference<Activation> activationRef = new AtomicReference<>();
    private final AtomicBoolean runtimeSubscribed = new AtomicBoolean();

    void trackCreated(RuntimeBinding binding) {
        if (binding != null) {
            activationRef.set(new Activation(binding, null, Compensation.CANCEL_NEW));
        }
    }

    void trackReused(RuntimeBinding binding, RuntimeBinding previousBinding) {
        if (binding != null && previousBinding != null) {
            activationRef.set(new Activation(binding, previousBinding, Compensation.RESTORE_PREVIOUS));
        }
    }

    void markRuntimeSubscribed() {
        runtimeSubscribed.set(true);
    }

    boolean runtimeSubscribed() {
        return runtimeSubscribed.get();
    }

    Activation activation() {
        return activationRef.get();
    }

    enum Compensation {
        CANCEL_NEW,
        RESTORE_PREVIOUS
    }

    record Activation(
            RuntimeBinding binding,
            RuntimeBinding previousBinding,
            Compensation compensation
    ) {
    }
}
