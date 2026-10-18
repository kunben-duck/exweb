/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static com.huawei.it.ex.one.application.service.observation.ObservationTestFixture.await;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.huawei.it.ex.one.application.config.ChatInteractionProperties;
import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.config.RunAdmissionProperties;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.service.observation.ObservationTestFixture;
import com.huawei.it.ex.one.application.service.observation.TurnTraceCoordinator;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

class InteractionContinuationObservationLifecycleTest extends ChatFlowTestSupport {
    @ParameterizedTest(name = "{0}, legacy={1}, timeout={2}")
    @MethodSource("cancelledClaims")
    void lateClaimAfterCancellationCannotRecreateObservation(String kind, boolean legacy, boolean timeout)
            throws Exception {
        assertCancelledContinuation(kind, legacy, timeout, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationAfterPrecheckStillBlocksInheritanceAndExecution(boolean legacy) throws Exception {
        assertCancelledContinuation("domain-questionnaire", legacy, false, true);
    }

    private static Stream<Arguments> cancelledClaims() {
        return Stream.of("clarification", "ambiguous-other", "domain-questionnaire", "relay-questionnaire",
                        "route-switch")
                .flatMap(kind -> Stream.of(false, true).flatMap(legacy -> Stream.of(false, true)
                        .map(timeout -> Arguments.of(kind, legacy, timeout))));
    }

    private void assertCancelledContinuation(String kind, boolean legacy, boolean timeout, boolean blockInheritance)
            throws Exception {
        try (var observation = new ObservationTestFixture()) {
            var turns = spy(observation.turns);
            var payload = new LinkedHashMap<String, Object>();
            if (!legacy) {
                observation.start("run-a");
                payload.putAll(turns.interactionMetadata("run-a", Map.of()));
                turns.finishStart("run-a");
                await(() -> observation.traces.size() == 2);
            }
            ChatInteractionType type = populatePayload(kind, payload);
            var waiting = new ChatInteractionRequest("interaction", "t", "u", "session", "run-a", null,
                    "user-1", "assistant", kind.startsWith("relay") ? "relay" : "domain-agent",
                    "binding", "real-downstream-session", "approval", type, ChatInteractionStatus.WAITING,
                    payload, Map.of(), null, null, null, null, null);
            var user = new UserContext("t", "u", "u");
            var command = new ChatInteractionResponseCommand(user, waiting.id(), true, "once",
                    kind.endsWith("questionnaire") ? Map.of("label", Map.of("range", "month"))
                            : Map.of("range", "month"), Map.of(), "session", null, null, List.of(),
                    null, null, null, null);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch releasedClaim = new CountDownLatch(1);
            var interactions = new InMemoryInteractionRequestRepository() {
                @Override public boolean claimInteractionResponse(ChatInteractionClaimCommand claim) {
                    boolean result = super.claimInteractionResponse(claim);
                    if (!blockInheritance) {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                    }
                    return result;
                }
            };
            interactions.insert(waiting);
            var ids = new FixedIdGenerator();
            var service = spy(new ChatInteractionApplicationService(interactions, ids,
                    new PermissionChecker(), new ChatInteractionProperties()));
            doAnswer(invocation -> {
                try { return invocation.callRealMethod(); }
                finally { releasedClaim.countDown(); }
            }).when(service).markWaiting(any());
            AtomicInteger cleanupCalls = new AtomicInteger();
            doAnswer(invocation -> {
                try { return invocation.callRealMethod(); }
                finally { cleanupCalls.incrementAndGet(); }
            }).when(turns).finishStart("run_1");
            if (blockInheritance) {
                doAnswer(invocation -> {
                    entered.countDown();
                    awaitIgnoringInterrupt(release);
                    return invocation.callRealMethod();
                }).when(turns).inherit(any(), eq("run_1"));
            }
            var registry = new LocalChatRunExecutionRegistry();
            var compensator = new FirstEventTimeoutCompensator(service, null, null, null, registry,
                    Schedulers.immediate());
            var properties = new ChatRunOperationalProperties();
            properties.setFirstEventTimeout(timeout ? Duration.ofSeconds(1) : Duration.ofSeconds(30));
            var start = new ChatRunStartCoordinator(ids, new RunAdmissionControlService(new RunAdmissionProperties()),
                    registry, properties, compensator);
            start.setObservationSupport(turns);
            var continuation = new InteractionContinuationCoordinator(start, service, null, documentFacade(),
                    new IntentClarificationContextAssembler());
            continuation.setObservationSupport(turns);
            AtomicInteger executionCalls = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<>();
            Disposable subscription = null;
            try {
                subscription = continuation.start(user, null, command, RuntimeForwardHeaders.empty(), request -> {
                    executionCalls.incrementAndGet();
                    return Flux.empty();
                }).subscribe(ignored -> { }, error::set);
                assertThat(entered.await(5, TimeUnit.SECONDS)).as("claim reached; error=%s", error.get()).isTrue();
                if (timeout) {
                    await(() -> error.get() != null);
                    assertThat(error.get()).hasMessageContaining("RUN_FIRST_EVENT_TIMEOUT");
                } else {
                    subscription.dispose();
                }
                await(() -> cleanupCalls.get() == 1);
                assertNoObservationState(turns);
                release.countDown();
                assertThat(releasedClaim.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(executionCalls).hasValue(0);
                assertThat(cleanupCalls).hasValue(1);
                assertThat(interactions.claimCalls).hasValue(1);
                assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.WAITING);
                assertNoObservationState(turns);
                assertThat(observation.traces).noneMatch(trace -> "run_1".equals(trace.currentRunId()));
                if (!blockInheritance) { verify(turns, never()).inherit(any(), eq("run_1")); }
            } finally {
                release.countDown();
                if (subscription != null) { subscription.dispose(); }
            }
        }
    }

    private ChatInteractionType populatePayload(String kind, Map<String, Object> payload) {
        if (kind.endsWith("questionnaire")) {
            payload.put("sourceType", "approval-request");
            payload.put("operation_type", "questionnaire");
            payload.put("questions", List.of(Map.of("question", "range", "multi_select", false)));
            return ChatInteractionType.AGENT_CLARIFICATION;
        }
        if ("route-switch".equals(kind)) { return ChatInteractionType.ROUTE_SWITCH_CONFIRMATION; }
        payload.put("originalQuery", "original question");
        payload.put("clarifyQuestion", "range");
        if ("ambiguous-other".equals(kind)) {
            payload.put("clarificationType", "AMBIGUOUS_ROUTE");
        }
        return ChatInteractionType.INTENT_CLARIFICATION;
    }

    private void assertNoObservationState(TurnTraceCoordinator turns) throws Exception {
        for (String name : new String[]{"states", "pendingContinuations"}) {
            var field = TurnTraceCoordinator.class.getDeclaredField(name);
            field.setAccessible(true);
            assertThat((Map<?, ?>) field.get(turns)).as(name).isEmpty();
        }
    }

    /** 模拟同步驱动不立即响应订阅取消，仍在清理后返回认领结果。 */
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        try {
            while (latch.getCount() != 0) {
                try {
                    assertThat(latch.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)).isTrue();
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
