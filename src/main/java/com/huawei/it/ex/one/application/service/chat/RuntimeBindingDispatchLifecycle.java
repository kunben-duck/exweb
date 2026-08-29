package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService.AdmissionCancellation;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 记录本轮 Runtime 订阅前需要补偿的 Binding 激活状态。 */
final class RuntimeBindingDispatchLifecycle {
    private final AtomicReference<Activation> activationRef = new AtomicReference<>();
    private final AtomicReference<List<AdmissionCancellation>> admissionCancellationsRef =
            new AtomicReference<>(List.of());
    private final AtomicBoolean runtimeSubscribed = new AtomicBoolean();
    private final AtomicBoolean compensated = new AtomicBoolean();

    void trackCreated(RuntimeBinding binding) {
        if (binding != null) {
            List<AdmissionCancellation> admissionCancellations = admissionCancellationsRef.get();
            activationRef.set(admissionCancellations.isEmpty()
                    ? new Activation(binding, null, List.of(), Compensation.CANCEL_NEW)
                    : new Activation(binding, null, admissionCancellations, Compensation.RESTORE_ADMISSION));
        }
    }

    void trackReused(RuntimeBinding binding, RuntimeBinding previousBinding) {
        if (binding != null && previousBinding != null) {
            activationRef.set(new Activation(
                    binding, previousBinding, List.of(), Compensation.RESTORE_PREVIOUS));
        }
    }

    void trackAdmissionCancellations(List<AdmissionCancellation> cancellations) {
        admissionCancellationsRef.set(cancellations == null ? List.of() : List.copyOf(cancellations));
    }

    void markRuntimeSubscribed() {
        runtimeSubscribed.set(true);
    }

    boolean runtimeSubscribed() {
        return runtimeSubscribed.get();
    }

    void markCompensated() {
        compensated.set(true);
    }

    boolean compensated() {
        return compensated.get();
    }

    Activation activation() {
        return activationRef.get();
    }

    enum Compensation {
        CANCEL_NEW,
        RESTORE_PREVIOUS,
        RESTORE_ADMISSION
    }

    record Activation(
            RuntimeBinding binding,
            RuntimeBinding previousBinding,
            List<AdmissionCancellation> admissionCancellations,
            Compensation compensation
    ) {
        Activation {
            admissionCancellations = admissionCancellations == null
                    ? List.of()
                    : List.copyOf(admissionCancellations);
        }
    }
}
