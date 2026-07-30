package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.RunWaitingUserEvent;

import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

class AmbiguousRouteTimeoutSchedulerTest {
    @Test
    @SuppressWarnings("unchecked")
    void scheduledTaskForwardsEntryContextToTimeoutContinuation() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ObjectProvider<FinanceChatOrchestrator> orchestratorProvider =
                mock(ObjectProvider.class);
        FinanceChatOrchestrator orchestrator = mock(FinanceChatOrchestrator.class);
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        AtomicReference<Runnable> scheduledTask = new AtomicReference<>();
        AtomicReference<Instant> scheduledAt = new AtomicReference<>();
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> {
                    scheduledTask.set(invocation.getArgument(0));
                    scheduledAt.set(invocation.getArgument(1));
                    return future;
                });
        when(orchestratorProvider.getIfAvailable()).thenReturn(orchestrator);
        when(orchestrator.startAmbiguousRouteTimeout(
                any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new ChatRunStartResult(
                        "run-b",
                        "session-1",
                        11L,
                        Instant.parse("2026-07-30T10:00:30Z"),
                        "chat-run-run-b")));
        AmbiguousRouteTimeoutScheduler scheduler =
                new AmbiguousRouteTimeoutScheduler(
                        taskScheduler,
                        orchestratorProvider);
        UserContext user =
                new UserContext("tenant-1", "user-1", "User One");
        TraceContext trace = new TraceContext("trace-1");
        RuntimeForwardHeaders headers =
                RuntimeForwardHeaders.fromCookieHeader("SESSION=timeout", 128);
        Map<String, Object> metadata = Map.of(
                "language", "zh_CN",
                "sceneParam", Map.of("region", "CN"));
        Instant autoSelectAt = Instant.parse("2026-07-30T10:00:30Z");

        scheduler.observe(
                RunWaitingUserEvent.of(
                        "run-a",
                        "session-1",
                        Map.of(
                                "interactionId", "interaction-1",
                                "interactionType", "INTENT_CLARIFICATION",
                                "clarificationType", "AMBIGUOUS_ROUTE",
                                "autoSelectAt", autoSelectAt.toString(),
                                "autoSelectTimeoutMs", 30_000L)),
                new AmbiguousRouteTimeoutScheduler.InvocationContext(
                        user,
                        trace,
                        headers,
                        metadata));

        assertThat(scheduledAt).hasValue(autoSelectAt);
        assertThat(scheduledTask.get()).isNotNull();
        scheduledTask.get().run();
        verify(orchestrator).startAmbiguousRouteTimeout(
                same(user),
                same(trace),
                eq("interaction-1"),
                eq(metadata),
                same(headers));
    }
}
