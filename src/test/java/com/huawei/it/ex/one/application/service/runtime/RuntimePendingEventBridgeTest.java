package com.huawei.it.ex.one.application.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.RuntimeStreamLimitsProperties;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.util.Map;

class RuntimePendingEventBridgeTest {

    @Test
    void drainsAcceptedEventsBeforePropagatingQueueOverflow() {
        RuntimeStreamLimitsProperties properties = properties(3);
        RuntimePendingBudgetRegistry registry = new RuntimePendingBudgetRegistry(properties);
        RuntimePendingEventGuard guard = new RuntimePendingEventGuard(
                registry, new RuntimeEventSizeEstimator(new ObjectMapper()));
        RuntimePendingEventBridge bridge = new RuntimePendingEventBridge("run1", 2, guard);
        ChatEvent first = event("one");
        ChatEvent second = event("two");

        StepVerifier.create(bridge.flux().map(guard::releaseAndUnwrap), 0)
                .then(() -> {
                    bridge.emit(first);
                    bridge.emit(second);
                    assertThatThrownBy(() -> bridge.emit(event("overflow")))
                            .isInstanceOf(RuntimeStreamLimitExceededException.class);
                })
                .thenRequest(2)
                .expectNext(first, second)
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(RuntimeStreamLimitExceededException.class))
                .verify();

        assertThat(registry.instanceEvents()).isZero();
        assertThat(registry.instanceBytes()).isZero();
    }

    @Test
    void budgetReservationFailureClosesBridgeAfterAcceptedEvents() {
        RuntimeStreamLimitsProperties properties = properties(1);
        RuntimePendingBudgetRegistry registry = new RuntimePendingBudgetRegistry(properties);
        RuntimePendingEventGuard guard = new RuntimePendingEventGuard(
                registry, new RuntimeEventSizeEstimator(new ObjectMapper()));
        RuntimePendingEventBridge bridge = new RuntimePendingEventBridge("run1", 2, guard);
        ChatEvent accepted = event("accepted");

        StepVerifier.create(bridge.flux().map(guard::releaseAndUnwrap), 0)
                .then(() -> {
                    bridge.emit(accepted);
                    assertThatThrownBy(() -> bridge.emit(event("rejected")))
                            .isInstanceOf(RuntimeStreamLimitExceededException.class);
                })
                .thenRequest(1)
                .expectNext(accepted)
                .expectError(RuntimeStreamLimitExceededException.class)
                .verify();

        assertThat(registry.instanceEvents()).isZero();
        assertThat(registry.instanceBytes()).isZero();
    }

    private RuntimeStreamLimitsProperties properties(int runEvents) {
        RuntimeStreamLimitsProperties properties = new RuntimeStreamLimitsProperties();
        properties.setPendingMaxEventsPerRun(runEvents);
        properties.setPendingMaxEventsPerInstance(10);
        properties.setPendingMaxBytesPerRun(DataSize.ofMegabytes(1));
        properties.setPendingMaxBytesPerInstance(DataSize.ofMegabytes(2));
        return properties;
    }

    private ChatEvent event(String text) {
        return RuntimeEvent.progress("run1", "session1", Map.of(
                "source", "relay",
                "sourceType", "progress",
                "text", text));
    }
}
