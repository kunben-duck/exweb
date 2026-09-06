/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ErrorEvent;
import com.huawei.it.ex.one.domain.chat.MessageCompletedEvent;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;

import reactor.core.Scannable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

class StandardRunRuntimeCoordinatorTest {
    private static final String RUN_ID = "run_b";
    private static final String SESSION_ID = "session1";
    private static final String MARKER = "candidate-skill-switch";
    private static final RunExecutionClaim CLAIM = new RunExecutionClaim(RUN_ID, "instance1", 3L);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void waitsForWholePrefixAndMarkerPostProcessingBeforeRoute(boolean batching, boolean placeholder) throws Exception {
        try (Fixture fixture = new Fixture(batching, placeholder)) {
            Pause prefixWrite = fixture.pause();
            Pause markerWrite = fixture.pause();
            Pause markerPostProcessing = fixture.pause();
            Pause routeWrite = fixture.pause();
            fixture.beforePersist = event -> {
                if ("1".equals(event.payload().get("step"))) {
                    prefixWrite.block();
                }
                if (MARKER.equals(sourceType(event))) {
                    markerWrite.block();
                }
            };
            fixture.afterPersist = event -> {
                if (MARKER.equals(sourceType(event))) {
                    markerPostProcessing.block();
                }
            };
            fixture.routeWrite = routeWrite::block;
            CompletableFuture<List<ChatEvent>> result = fixture.execute(trace()).collectList().toFuture();

            prefixWrite.awaitEntered();
            fixture.assertNoRoute();
            prefixWrite.release();
            markerWrite.awaitEntered();
            fixture.assertNoRoute();
            markerWrite.release();
            markerPostProcessing.awaitEntered();
            fixture.assertNoRoute();
            assertThat(fixture.persisted).anyMatch(event -> MARKER.equals(sourceType(event)));
            markerPostProcessing.release();

            routeWrite.awaitEntered();
            assertThat(fixture.published).extracting(StandardRunRuntimeCoordinatorTest::sourceType)
                    .containsSubsequence("intent-start", "intent-progress", "intent-progress", "selectedDomainAgent", MARKER);
            assertThat(fixture.persisted).hasSize(6); // run.started plus the complete replay prefix
            routeWrite.release();

            List<ChatEvent> events = result.get(5, TimeUnit.SECONDS);
            assertSuccessfulOutput(events);
            assertThat(events).extracting(ChatEvent::sequence).isSorted().doesNotHaveDuplicates();
            assertThat(fixture.lockConflicts).isFalse();
            verify(fixture.routeResolution).prepareInitial(any());
            verify(fixture.dispatch).execute(any(), any(Runnable.class));
            assertThat(fixture.acknowledgedEvents).hasSize(1);
            assertThat(fixture.acknowledgedEvents.getFirst().persisted().scan(Scannable.Attr.TERMINATED)).isTrue();
            if (batching) {
                verify(fixture.streamService).appendBatchWithExecutionGuard(anyList(), eq(CLAIM));
            } else {
                verify(fixture.streamService, never()).appendBatchWithExecutionGuard(anyList(), any());
            }
            if (placeholder) {
                assertThat(fixture.persisted).noneMatch(event -> "message.delta".equals(event.type()));
                verify(fixture.streamService).publishLiveOnly(argThat(event -> "message.delta".equals(event.type())));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"intent-start,true", "candidate-skill-switch,true", "intent-start,false", "candidate-skill-switch,false"})
    void failedOrRejectedReplayNeverStartsRouteAndTerminates(String failureStage, boolean rejected) throws Exception {
        try (Fixture fixture = new Fixture(true, false)) {
            fixture.beforePersist = event -> {
                if (failureStage.equals(sourceType(event))) {
                    throw rejected ? new ChatEventAppendRejectedException("Simulated guarded insert rejection")
                            : new IllegalStateException("Simulated database failure");
                }
            };
            List<ChatEvent> events = fixture.execute(trace()).collectList().toFuture().get(5, TimeUnit.SECONDS);

            fixture.assertNoRoute();
            assertThat(events).noneMatch(event -> "message.delta".equals(event.type())
                    || "message.completed".equals(event.type()) || "run.completed".equals(event.type()));
            assertThat(events.stream().filter(event -> "run.failed".equals(event.type())).count())
                    .isEqualTo(rejected ? 0 : 1);
            verify(fixture.registry, timeout(1000)).complete(CLAIM);
        }
    }

    @Test
    void markerPostProcessingFailureDoesNotAcknowledgeReplay() throws Exception {
        try (Fixture fixture = new Fixture(true, false)) {
            fixture.afterPersist = event -> {
                if (MARKER.equals(sourceType(event))) {
                    throw new IllegalStateException("Simulated post-commit failure");
                }
            };
            List<ChatEvent> events = fixture.execute(trace()).collectList().toFuture().get(5, TimeUnit.SECONDS);

            fixture.assertNoRoute();
            assertThat(events).extracting(ChatEvent::type).endsWith("run.failed");
            assertThat(fixture.acknowledgedEvents).hasSize(1);
            assertThat(fixture.acknowledgedEvents.getFirst().persisted().scan(Scannable.Attr.ERROR))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopOrOwnerLossBeforeAcknowledgementNeverStartsRuntime(boolean afterCommit) throws Exception {
        try (Fixture fixture = new Fixture(true, false)) {
            Consumer<ChatEvent> loseOwner = event -> {
                if (MARKER.equals(sourceType(event))) {
                    fixture.ownerRunning.set(false);
                }
            };
            if (afterCommit) {
                fixture.afterPersist = loseOwner;
            } else {
                fixture.beforePersist = loseOwner;
            }

            List<ChatEvent> events = fixture.execute(trace()).collectList().toFuture().get(5, TimeUnit.SECONDS);

            verify(fixture.routeResolution, never()).prepareInitial(any());
            assertThat(fixture.runtimeSubscribed).isFalse();
            assertThat(events).noneMatch(event -> "message.delta".equals(event.type())
                    || "run.completed".equals(event.type()));
            verify(fixture.registry, timeout(1000)).complete(CLAIM);
        }
    }

    @Test
    void cancellationDisposesWaitingSubscriptionEvenIfMarkerCommitFinishesLater() throws Exception {
        try (Fixture fixture = new Fixture(true, false)) {
            Pause markerWrite = fixture.pause();
            CountDownLatch markerFinished = new CountDownLatch(1);
            fixture.beforePersist = event -> {
                if (MARKER.equals(sourceType(event))) {
                    markerWrite.block();
                }
            };
            fixture.afterPersist = event -> {
                if (MARKER.equals(sourceType(event))) {
                    markerFinished.countDown();
                }
            };
            CountDownLatch disposed = new CountDownLatch(1);
            CompletableFuture<List<ChatEvent>> result = fixture.execute(trace())
                    .doFinally(signal -> disposed.countDown()).collectList().toFuture();
            markerWrite.awaitEntered();

            result.cancel(true);
            assertThat(disposed.await(5, TimeUnit.SECONDS)).isTrue();
            markerWrite.release();
            assertThat(markerFinished.await(5, TimeUnit.SECONDS)).isTrue();

            fixture.assertNoRoute();
            assertThat(result).isCancelled();
            verify(fixture.registry, timeout(1000)).complete(CLAIM);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void markerOnlyAndRepeatedSubscriptionsHaveIndependentAcknowledgements(boolean batching) {
        try (Fixture fixture = new Fixture(batching, false)) {
            Flux<ChatEvent> flow = fixture.execute(new CandidateSwitchRouteTrace(List.of(marker())));
            assertSuccessfulOutput(flow.collectList().block(TIMEOUT));
            assertSuccessfulOutput(flow.collectList().block(TIMEOUT));

            assertThat(fixture.acknowledgedEvents).hasSize(2);
            assertThat(fixture.acknowledgedEvents.get(0).persisted())
                    .isNotSameAs(fixture.acknowledgedEvents.get(1).persisted());
            verify(fixture.dispatch, times(2)).execute(any(), any(Runnable.class));
        }
    }

    @Test
    void ordinaryRunWithEmptyReplayUsesExistingPathWithoutAcknowledgement() {
        try (Fixture fixture = new Fixture(true, false)) {
            List<ChatEvent> events = fixture.coordinator.execute(fixture.plan, CLAIM).collectList().block(TIMEOUT);

            assertSuccessfulOutput(events);
            assertThat(events).noneMatch(event -> MARKER.equals(sourceType(event)));
            assertThat(fixture.acknowledgedEvents).isEmpty();
            verify(fixture.dispatch).execute(any(), any(Runnable.class));
        }
    }

    @Test
    void cumulativeSwitchMarkersOnlyAcknowledgeTheLastMarker() {
        try (Fixture fixture = new Fixture(true, false)) {
            List<CandidateSwitchRouteTrace.Entry> entries = new ArrayList<>();
            for (int index = 0; index < 31; index++) {
                entries.add(new CandidateSwitchRouteTrace.Entry("runtime.progress", Map.of(
                        "source", "chatservice", "sourceType", MARKER,
                        CandidateSwitchRouteTrace.REPLAY_METADATA_KEY,
                        Map.of("originRunId", "run_a", "originSequence", index + 1))));
            }
            entries.add(marker());

            List<ChatEvent> events = fixture.execute(new CandidateSwitchRouteTrace(entries))
                    .collectList().block(TIMEOUT);

            assertSuccessfulOutput(events);
            assertThat(events).filteredOn(event -> MARKER.equals(sourceType(event))).hasSize(32);
            assertThat(fixture.acknowledgedEvents).hasSize(1);
            assertThat(fixture.acknowledgedEvents.getFirst().payload())
                    .doesNotContainKey(CandidateSwitchRouteTrace.REPLAY_METADATA_KEY);
        }
    }

    @Test
    void malformedPrefixDoesNotTurnAnArbitraryBusinessEventIntoPersistentControl() {
        try (Fixture fixture = new Fixture(true, true)) {
            CandidateSwitchRouteTrace trace = new CandidateSwitchRouteTrace(List.of(
                    new CandidateSwitchRouteTrace.Entry("runtime.thinking", Map.of(
                            "source", "domain-agent", "content", "private thinking"))));

            List<ChatEvent> events = fixture.execute(trace).collectList().block(TIMEOUT);

            assertThat(events).extracting(ChatEvent::type).containsExactly("run.started", "run.failed");
            assertThat(fixture.acknowledgedEvents).isEmpty();
            fixture.assertNoRoute();
        }
    }

    private static void assertSuccessfulOutput(List<ChatEvent> events) {
        assertThat(events).isNotNull().extracting(ChatEvent::type)
                .startsWith("run.started")
                .endsWith("runtime.metadata", "message.delta", "message.completed", "run.completed");
        assertThat(events).filteredOn(event -> "message.delta".equals(event.type()))
                .extracting(event -> event.payload().get("delta")).containsExactly("新回答");
    }

    private static CandidateSwitchRouteTrace trace() {
        return new CandidateSwitchRouteTrace(List.of(
                new CandidateSwitchRouteTrace.Entry("runtime.progress", Map.of(
                        "source", "intent-agent", "sourceType", "intent-start")),
                new CandidateSwitchRouteTrace.Entry("runtime.progress", Map.of(
                        "source", "intent-agent", "sourceType", "intent-progress", "step", "1")),
                new CandidateSwitchRouteTrace.Entry("runtime.progress", Map.of(
                        "source", "intent-agent", "sourceType", "intent-progress", "step", "2")),
                new CandidateSwitchRouteTrace.Entry("runtime.metadata", Map.of(
                        "source", "chatservice", "sourceType", "selectedDomainAgent", "targetId", "skill_a")),
                marker()));
    }

    private static CandidateSwitchRouteTrace.Entry marker() {
        return new CandidateSwitchRouteTrace.Entry("runtime.progress", Map.of(
                "source", "chatservice", "sourceType", MARKER));
    }

    private static String sourceType(ChatEvent event) {
        return (String) event.payload().get("sourceType");
    }

    private static final class Pause {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private void block() {
            entered.countDown();
            // Model database work that can finish even after reactive cancellation.
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();
                        return;
                    } catch (InterruptedException ex) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void awaitEntered() throws InterruptedException {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        }

        private void release() {
            released.countDown();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        private final ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        private final ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        private final LocalChatRunExecutionRegistry registry = mock(LocalChatRunExecutionRegistry.class);
        private final RouteResolutionCoordinator routeResolution = mock(RouteResolutionCoordinator.class);
        private final ChatRuntimeDispatchCoordinator dispatch = mock(ChatRuntimeDispatchCoordinator.class);
        private final Scheduler eventIo = Schedulers.newBoundedElastic(4, 100, "replay-test-event");
        private final Scheduler routeIo = Schedulers.newSingle("replay-test-route");
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicBoolean ownerRunning = new AtomicBoolean(true);
        private final AtomicBoolean routeLocked = new AtomicBoolean();
        private final AtomicBoolean lockConflicts = new AtomicBoolean();
        private final AtomicBoolean runtimeSubscribed = new AtomicBoolean();
        private final List<Pause> pauses = new CopyOnWriteArrayList<>();
        private final List<ChatEvent> persisted = new CopyOnWriteArrayList<>();
        private final List<ChatEvent> published = new CopyOnWriteArrayList<>();
        private final List<PersistenceAcknowledgedEvent> acknowledgedEvents = new CopyOnWriteArrayList<>();
        private final StandardRunRuntimeCoordinator coordinator;
        private final StandardRunRuntimeCoordinator.RuntimePlan plan;
        private volatile Consumer<ChatEvent> beforePersist = event -> { };
        private volatile Consumer<ChatEvent> afterPersist = event -> { };
        private volatile Runnable routeWrite = () -> { };

        private Fixture(boolean batching, boolean placeholder) {
            ChatStreamProperties properties = new ChatStreamProperties();
            properties.setEventBatchEnabled(batching);
            properties.setDeltaCoalesceEnabled(false);
            RuntimeBindingApplicationService bindings = mock(RuntimeBindingApplicationService.class);
            ChatRunCompletionCoordinator completion = mock(ChatRunCompletionCoordinator.class);
            when(completion.prepare(any(), any())).thenAnswer(invocation -> new ChatRunCompletionCoordinator.CompletionPlan(
                    invocation.getArgument(0), new ChatRunCompletionCoordinator.CompletionMessageTarget(false, false, null), null));
            when(completion.hasTerminalCommitService()).thenReturn(true);
            when(completion.commitTerminalFailure(any(), any())).thenAnswer(invocation ->
                    persist(ErrorEvent.of(RUN_ID, SESSION_ID, "TEST_FAILURE", "Simulated persistence failure")));
            when(runService.shouldAcceptEvent(any())).thenAnswer(invocation -> ownerRunning.get());
            when(leaseService.isCurrentOwnerRunning(CLAIM)).thenAnswer(invocation -> ownerRunning.get());
            when(streamService.appendWithExecutionGuard(any(), eq(CLAIM)))
                    .thenAnswer(invocation -> persist(invocation.getArgument(0)));
            when(streamService.appendBatchWithExecutionGuard(anyList(), eq(CLAIM)))
                    .thenAnswer(invocation -> invocation.<List<ChatEvent>>getArgument(0).stream().map(this::persist).toList());
            when(streamService.sequenceLiveBatchWithExecutionGuard(anyList(), eq(CLAIM)))
                    .thenAnswer(invocation -> invocation.<List<ChatEvent>>getArgument(0).stream().map(this::sequenced).toList());
            doAnswer(invocation -> {
                afterPersist.accept(invocation.getArgument(0));
                return null;
            }).when(runService).observeEvent(any());
            doAnswer(invocation -> {
                published.add(invocation.getArgument(0));
                return null;
            }).when(streamService).publishPersisted(any());
            ChatEventPipeline pipeline = new ChatEventPipeline(
                    new ChatDeltaCoalescer(properties), eventIo, new ChatEventBatcher(properties, null),
                    runService, streamService, bindings, completion);
            ChatRunExecutionGateCoordinator gate = new ChatRunExecutionGateCoordinator(
                    mock(ChatRunStartCoordinator.class), leaseService, registry, pipeline, eventIo);
            CommittedChatEventObserver observer = new CommittedChatEventObserver(
                    mock(ChatRunExecutionTerminalMarker.class), bindings, streamService, completion);
            ChatEventCommitCoordinator commit = new ChatEventCommitCoordinator(
                    mock(SessionApplicationService.class), runService, streamService,
                    mock(ChatInteractionApplicationService.class), bindings, completion,
                    mock(DomainAgentRefusalCoordinator.class), observer);
            ChatEventPersistenceCoordinator persistence = new ChatEventPersistenceCoordinator(
                    pipeline, gate, commit, mock(DomainAgentRefusalCommitCoordinator.class));
            coordinator = new StandardRunRuntimeCoordinator(
                    mock(IntentClarificationContextAssembler.class), routeResolution, dispatch,
                    persistence, mock(ChatRunFailureCoordinator.class), registry);
            plan = runtimePlan(placeholder);
            when(dispatch.execute(any(), any(Runnable.class))).thenAnswer(invocation -> {
                Runnable prepare = invocation.getArgument(1);
                return gate.requireCurrentOwnerRunning(CLAIM, "before-route")
                        .then(Mono.fromRunnable(() -> {
                            routeLocked.set(true);
                            try {
                                prepare.run();
                                routeWrite.run();
                            } finally {
                                routeLocked.set(false);
                            }
                        }).subscribeOn(routeIo))
                        .thenMany(Flux.defer(() -> {
                            runtimeSubscribed.set(true);
                            return Flux.just(
                                    RuntimeEvent.metadata(RUN_ID, SESSION_ID, Map.of(
                                            "source", "chatservice", "sourceType", "selectedDomainAgent", "targetId", "skill_b")),
                                    MessageDeltaEvent.of(RUN_ID, SESSION_ID, "新回答"),
                                    MessageCompletedEvent.of(RUN_ID, SESSION_ID));
                        }));
            });
        }

        private ChatEvent persist(ChatEvent event) {
            if (event instanceof PersistenceAcknowledgedEvent acknowledged) {
                acknowledgedEvents.add(acknowledged);
            }
            beforePersist.accept(event);
            if (!ownerRunning.get() || routeLocked.get()) {
                lockConflicts.set(routeLocked.get());
                throw new ChatEventAppendRejectedException("Simulated run FOR SHARE NOWAIT rejection");
            }
            ChatEvent stored = sequenced(event);
            persisted.add(stored);
            return stored;
        }

        private ChatEvent sequenced(ChatEvent event) {
            return new StoredChatEvent(event.runId(), event.sessionId(), sequence.incrementAndGet(),
                    event.type(), event.createdAt(), event.payload());
        }

        private Pause pause() {
            Pause pause = new Pause();
            pauses.add(pause);
            return pause;
        }

        private Flux<ChatEvent> execute(CandidateSwitchRouteTrace trace) {
            return coordinator.executeCandidateSwitch(plan, CLAIM, trace);
        }

        private void assertNoRoute() {
            verify(dispatch, after(75).never()).execute(any(), any(Runnable.class));
            verify(routeResolution, never()).prepareInitial(any());
            assertThat(runtimeSubscribed).isFalse();
        }

        private StandardRunRuntimeCoordinator.RuntimePlan runtimePlan(boolean placeholder) {
            StandardRunInputPreparer.PreparedRun prepared = mock(StandardRunInputPreparer.PreparedRun.class);
            StandardRunAdmissionCoordinator.Admission admission = mock(StandardRunAdmissionCoordinator.Admission.class);
            ChatSession session = mock(ChatSession.class);
            ChatCommand command = mock(ChatCommand.class);
            ChatRun run = mock(ChatRun.class);
            ChatRunMessagePlan messagePlan = mock(ChatRunMessagePlan.class);
            ChatMessage userMessage = mock(ChatMessage.class);
            when(prepared.user()).thenReturn(new UserContext("tenant1", "user1", "User One"));
            when(prepared.session()).thenReturn(session);
            when(prepared.runId()).thenReturn(RUN_ID);
            when(prepared.command()).thenReturn(command);
            when(prepared.documents()).thenReturn(List.of());
            when(session.id()).thenReturn(SESSION_ID);
            when(admission.run()).thenReturn(run);
            when(admission.messagePlan()).thenReturn(messagePlan);
            when(messagePlan.userMessage()).thenReturn(userMessage);
            when(run.userMessageId()).thenReturn("msg_user");
            AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏");
            if (placeholder) {
                state.tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
            }
            return new StandardRunRuntimeCoordinator.RuntimePlan(
                    prepared, admission, command, "原始问题", "原始问题", "msg_user",
                    new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>(),
                    new AssistantAssembly(state), new RuntimeBindingDispatchLifecycle(),
                    new AtomicReference<>(), new AtomicReference<>());
        }

        @Override
        public void close() {
            pauses.forEach(Pause::release);
            eventIo.dispose();
            routeIo.dispose();
        }
    }
}
