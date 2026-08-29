package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.util.retry.Retry;

import java.util.concurrent.atomic.AtomicReference;

/** 对 Runtime 尚未订阅的 Binding 激活执行有界条件补偿。 */
final class RuntimeBindingDispatchCompensator {
    private static final AppLogger log = AppLoggerFactory.getLogger(RuntimeBindingDispatchCompensator.class);

    private final RuntimeBindingApplicationService runtimeBindingService;
    private final Scheduler controlIoScheduler;
    private final DomainAgentProperties properties;

    RuntimeBindingDispatchCompensator(
            RuntimeBindingApplicationService runtimeBindingService,
            Scheduler controlIoScheduler,
            DomainAgentProperties properties) {
        this.runtimeBindingService = runtimeBindingService;
        this.controlIoScheduler = controlIoScheduler;
        this.properties = properties == null ? new DomainAgentProperties() : properties;
    }

    Mono<Void> cleanup(
            RuntimeBindingDispatchLifecycle lifecycle,
            String runId,
            String sessionId,
            AtomicReference<RuntimeBinding> bindingRef,
            String terminationSignal) {
        if (lifecycle == null || lifecycle.runtimeSubscribed() || lifecycle.compensated()
                || lifecycle.activation() == null) {
            return Mono.empty();
        }
        RuntimeBindingDispatchLifecycle.Activation activation = lifecycle.activation();
        Mono<Void> cleanup = Mono.<Void>fromRunnable(() -> cleanupBinding(activation, runId, bindingRef))
                .subscribeOn(controlIoScheduler);
        int maxAttempts = properties.normalizedBindingCompensationMaxAttempts();
        if (maxAttempts > 1) {
            cleanup = cleanup.retryWhen(Retry.fixedDelay(
                            maxAttempts - 1L,
                            properties.normalizedBindingCompensationRetryBackoff())
                    .filter(RuntimeException.class::isInstance)
                    .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
        }
        return cleanup.doOnSuccess(ignored -> lifecycle.markCompensated())
                .onErrorResume(ex -> {
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_WRITE_FAILED,
                                    "Unstarted Runtime binding compensation failed")
                            .runId(runId)
                            .sessionId(sessionId)
                            .operation("runtime.binding.unstarted-compensation")
                            .attribute("bindingId", activation.binding().id())
                            .attribute("compensation", activation.compensation().name())
                            .attribute("terminationSignal", terminationSignal)
                            .attribute("maxAttempts", maxAttempts)
                            .build(), ex);
                    return Mono.empty();
                })
                .then();
    }

    private void cleanupBinding(
            RuntimeBindingDispatchLifecycle.Activation activation,
            String runId,
            AtomicReference<RuntimeBinding> bindingRef) {
        boolean compensated = switch (activation.compensation()) {
            case CANCEL_NEW -> runtimeBindingService.cancelActiveForRun(activation.binding(), runId);
            case RESTORE_PREVIOUS -> runtimeBindingService.restoreUnstartedForRun(
                    activation.previousBinding(), runId);
            case RESTORE_ADMISSION -> runtimeBindingService.restoreAdmissionBindingsForUnstartedRun(
                    activation.binding(), activation.admissionCancellations(), runId);
        };
        if (!compensated || bindingRef == null) {
            return;
        }
        RuntimeBinding replacement = switch (activation.compensation()) {
            case RESTORE_PREVIOUS -> activation.previousBinding();
            case RESTORE_ADMISSION -> activation.admissionCancellations().getFirst().previous();
            case CANCEL_NEW -> activation.binding().withStatus(RuntimeBindingStatus.CANCELLED);
        };
        bindingRef.compareAndSet(activation.binding(), replacement);
        if (activation.compensation() == RuntimeBindingDispatchLifecycle.Compensation.RESTORE_ADMISSION) {
            activation.admissionCancellations().forEach(cancellation ->
                    runtimeBindingService.synchronizeCache(cancellation.previous()));
        }
    }
}
