/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService.AdmissionCancellation;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class RuntimeBindingDispatchCompensatorTest {
    @Test
    void cancelsNewBindingWhenRuntimeWasNotSubscribed() {
        RuntimeBindingApplicationService service = org.mockito.Mockito.mock(
                RuntimeBindingApplicationService.class);
        RuntimeBinding active = binding(RuntimeBindingStatus.ACTIVE, "run-new");
        when(service.cancelActiveForRun(active, "run-new")).thenReturn(true);
        RuntimeBindingDispatchLifecycle lifecycle = new RuntimeBindingDispatchLifecycle();
        lifecycle.trackCreated(active);
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>(active);

        StepVerifier.create(compensator(service).cleanup(
                        lifecycle, "run-new", "session1", bindingRef, "error"))
                .verifyComplete();

        verify(service).cancelActiveForRun(active, "run-new");
        assertThat(bindingRef.get().status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
    }

    @Test
    void restoresResumableSnapshotWhenReusedBindingWasNotSubscribed() {
        RuntimeBindingApplicationService service = org.mockito.Mockito.mock(
                RuntimeBindingApplicationService.class);
        RuntimeBinding previous = binding(RuntimeBindingStatus.RESUMABLE, "run-old");
        RuntimeBinding active = previous.withRun("run-new", null);
        when(service.restoreUnstartedForRun(previous, "run-new")).thenReturn(true);
        RuntimeBindingDispatchLifecycle lifecycle = new RuntimeBindingDispatchLifecycle();
        lifecycle.trackReused(active, previous);
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>(active);

        StepVerifier.create(compensator(service).cleanup(
                        lifecycle, "run-new", "session1", bindingRef, "cancel"))
                .verifyComplete();

        verify(service).restoreUnstartedForRun(previous, "run-new");
        verify(service, never()).cancelActiveForRun(active, "run-new");
        assertThat(bindingRef).hasValue(previous);
        assertThat(bindingRef.get().runtimeSessionId()).isEqualTo("runtime-1");
    }

    @Test
    void doesNotCompensateAfterRuntimeSubscription() {
        RuntimeBindingApplicationService service = org.mockito.Mockito.mock(
                RuntimeBindingApplicationService.class);
        RuntimeBinding active = binding(RuntimeBindingStatus.ACTIVE, "run-new");
        RuntimeBindingDispatchLifecycle lifecycle = new RuntimeBindingDispatchLifecycle();
        lifecycle.trackCreated(active);
        lifecycle.markRuntimeSubscribed();

        StepVerifier.create(compensator(service).cleanup(
                        lifecycle, "run-new", "session1", new AtomicReference<>(active), "error"))
                .verifyComplete();

        verify(service, never()).cancelActiveForRun(active, "run-new");
    }

    @Test
    void conditionalRestoreMissDoesNotOverwriteNewerBindingReference() {
        RuntimeBindingApplicationService service = org.mockito.Mockito.mock(
                RuntimeBindingApplicationService.class);
        RuntimeBinding previous = binding(RuntimeBindingStatus.RESUMABLE, "run-old");
        RuntimeBinding active = previous.withRun("run-new", null);
        RuntimeBindingDispatchLifecycle lifecycle = new RuntimeBindingDispatchLifecycle();
        lifecycle.trackReused(active, previous);
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>(active);

        StepVerifier.create(compensator(service).cleanup(
                        lifecycle, "run-new", "session1", bindingRef, "error"))
                .verifyComplete();

        assertThat(bindingRef).hasValue(active);
    }

    @Test
    void restoresAdmissionCancelledBindingBeforeRuntimeSubscription() {
        RuntimeBindingApplicationService service = org.mockito.Mockito.mock(
                RuntimeBindingApplicationService.class);
        RuntimeBinding previous = binding(RuntimeBindingStatus.ACTIVE, "run-old");
        RuntimeBinding cancelled = previous.withStatus(RuntimeBindingStatus.CANCELLED);
        AdmissionCancellation cancellation = new AdmissionCancellation(previous, cancelled);
        RuntimeBinding current = new RuntimeBinding(
                "binding-new", previous.tenantId(), previous.userId(), previous.chatSessionId(),
                "domain-agent", previous.leafMessageId(), previous.runtimeSessionId(),
                RuntimeBindingStatus.ACTIVE, "run-new", null,
                previous.createdAt(), previous.updatedAt(), Map.of());
        when(service.restoreAdmissionBindingsForUnstartedRun(
                current, List.of(cancellation), "run-new")).thenReturn(true);
        RuntimeBindingDispatchLifecycle lifecycle = new RuntimeBindingDispatchLifecycle();
        lifecycle.trackAdmissionCancellations(List.of(cancellation));
        lifecycle.trackCreated(current);
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>(current);

        RuntimeBindingDispatchCompensator compensator = compensator(service);
        StepVerifier.create(compensator.cleanup(
                        lifecycle, "run-new", "session1", bindingRef, "attachment-validation"))
                .verifyComplete();
        StepVerifier.create(compensator.cleanup(
                        lifecycle, "run-new", "session1", bindingRef, "complete"))
                .verifyComplete();

        verify(service).restoreAdmissionBindingsForUnstartedRun(
                current, List.of(cancellation), "run-new");
        verify(service).synchronizeCache(previous);
        assertThat(bindingRef).hasValue(previous);
        assertThat(lifecycle.compensated()).isTrue();
    }

    private RuntimeBindingDispatchCompensator compensator(RuntimeBindingApplicationService service) {
        DomainAgentProperties properties = new DomainAgentProperties();
        properties.setBindingCompensationMaxAttempts(1);
        return new RuntimeBindingDispatchCompensator(service, Schedulers.immediate(), properties);
    }

    private RuntimeBinding binding(RuntimeBindingStatus status, String runId) {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        return new RuntimeBinding(
                "binding1",
                "tenant1",
                "user1",
                "session1",
                "relay",
                "leaf1",
                "runtime-1",
                status,
                runId,
                null,
                now,
                now,
                Map.of("runtimeSessionEstablished", true));
    }
}
