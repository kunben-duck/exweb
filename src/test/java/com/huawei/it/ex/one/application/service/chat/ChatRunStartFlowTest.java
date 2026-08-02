package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.config.RouteSignalProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalFrame;
import com.huawei.it.ex.one.application.service.routing.RouteSignalRequest;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecution;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.RunStartedEvent;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.usecase.UseCaseMatchResult;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
class ChatRunStartFlowTest extends ChatFlowTestSupport {
    @Test
    void guardedRunStartedRejectionDoesNotSubscribeRouteOrRuntime() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        RejectingRunStartedEventStore events = new RejectingRunStartedEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicInteger runtimeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = countingRuntimeRouteService(routeCalls);
        AgentRuntime runtime = new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                runtimeCalls.incrementAndGet();
                return Flux.empty();
            }
            @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
        };
        FinanceEXChatService service = defaultFinanceService(
                sessions, messages, runs, events, routeService, runtime, true, new NeverCancelRunCache());

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())))
                .verifyComplete();

        assertThat(events.guardedAttempts.get()).isEqualTo(1);
        assertThat(routeCalls.get()).isZero();
        assertThat(runtimeCalls.get()).isZero();
        assertThat(events.findLatestSeqByOwnerAndSession("tenant1", "user1", "session_1")).isZero();
    }

    @Test
    void cancelledBeforeRunStartedDoesNotSubscribeRouteOrRuntime() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicInteger runtimeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = countingRuntimeRouteService(routeCalls);
        AgentRuntime runtime = new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                runtimeCalls.incrementAndGet();
                return Flux.empty();
            }
            @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
        };
        FinanceEXChatService service = defaultFinanceService(
                sessions, messages, runs, events, routeService, runtime, true, new AlwaysCancelledRunCache());

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())))
                .verifyComplete();

        assertThat(routeCalls.get()).isZero();
        assertThat(runtimeCalls.get()).isZero();
        assertThat(events.events).isEmpty();
    }

    @Test
    void ownerLostAfterRouteDoesNotInvokeRuntimeOrAppendFallbackTerminal() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicInteger runtimeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                return Flux.defer(() -> {
                    routeCalls.incrementAndGet();
                    executions.rejectOwnerRunningChecks = true;
                    return Flux.just(RouteSignalFrame.result(
                            RouteSignalResult.of(RouteTarget.agentRuntime("test-runtime"))));
                });
            }
        };
        AgentRuntime runtime = new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                runtimeCalls.incrementAndGet();
                return Flux.empty();
            }
            @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
        };
        FinanceEXChatService service = defaultFinanceService(
                sessions, messages, runs, events, routeService, runtime, true,
                new NeverCancelRunCache(), executions);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                                null, null, "web", "hello", List.of(), Map.of()))
                        .collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .containsExactly("run.started"))
                .verifyComplete();

        assertThat(routeCalls).hasValue(1);
        assertThat(runtimeCalls).hasValue(0);
        assertThat(events.events).extracting(ChatEvent::type)
                .containsExactly("run.started")
                .doesNotContain("run.completed", "run.failed");
    }

    @Test
    void ownerTerminalFenceRejectionStopsWithoutFallbackFailureOrAssistant() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        runs.rejectOwnerTerminalFences = true;
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return Flux.just(MessageDeltaEvent.of(request.runId(), request.sessionId(), "partial answer"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, runtimeRouteService(), runtime);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                                null, null, "web", "hello", List.of(), Map.of()))
                        .collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .containsExactly("run.started", "message.delta"))
                .verifyComplete();

        assertThat(runs.ownerTerminalFenceAttempts).hasValue(1);
        assertThat(events.events).extracting(ChatEvent::type)
                .containsExactly("run.started", "message.delta")
                .doesNotContain("run.completed", "run.failed", "run.cancelled");
        assertThat(messages.messages).noneMatch(message -> "assistant".equals(message.role()));
        assertThat(runs.runs.values()).singleElement()
                .extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.CANCELLING);
    }

    @Test
    void ordinaryOwnerFailureUsesTerminalFence() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return Flux.error(new IllegalStateException("runtime failed"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, runtimeRouteService(), runtime);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                                null, null, "web", "hello", List.of(), Map.of()))
                        .collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .containsExactly("run.started", "run.failed"))
                .verifyComplete();

        assertThat(runs.ownerTerminalFenceAttempts).hasValue(1);
        assertThat(events.events).extracting(ChatEvent::type)
                .containsExactly("run.started", "run.failed");
        assertThat(runs.runs.values()).singleElement()
                .extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.FAILED);
    }

    @Test
    void executionInitializationFailureCommitsFailedBeforeRouteOrRuntime() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicInteger runtimeCalls = new AtomicInteger();
        AgentRuntime runtime = new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                runtimeCalls.incrementAndGet();
                return Flux.empty();
            }
            @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
        };
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, countingRuntimeRouteService(routeCalls), runtime,
                new FailingExecutionRepository());

        StepVerifier.create(service.executeRun(new UserContext("tenant1", "user1", "User One"),
                                new ChatCommand("cmd1", null, null, null, null, "web", "hello", List.of(), Map.of()))
                        .collectList())
                .assertNext(stream -> {
                    assertThat(stream).extracting(ChatEvent::type).containsExactly("run.failed");
                    assertThat(stream.getFirst().payload()).containsEntry("code", "RUN_EXECUTION_INIT_FAILED");
                })
                .verifyComplete();

        assertThat(routeCalls).hasValue(0);
        assertThat(runtimeCalls).hasValue(0);
        assertThat(runs.runs.values()).singleElement().extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.FAILED);
    }

    @Test
    void completedWithoutAssistantStillUsesTerminalFence() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, runtimeRouteService(), noopRuntime());

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                                null, null, "web", "hello", List.of(), Map.of()))
                        .collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .containsExactly("run.started", "run.completed"))
                .verifyComplete();

        assertThat(runs.ownerTerminalFenceAttempts).hasValue(1);
        assertThat(messages.messages).noneMatch(message -> "assistant".equals(message.role()));
        assertThat(sessions.sessions.values()).singleElement()
                .satisfies(session -> {
                    assertThat(session.latestMessageSeq()).isZero();
                    assertThat(session.hasUnread()).isFalse();
                });
        assertThat(runs.runs.values()).singleElement()
                .extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.COMPLETED);
    }

    @Test
    void runStartedIsEmittedBeforeExternalRouteSignalsAreRead() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public RouteSignalResult routeInitial(UserContext routeUser, ChatSession session, ChatCommand command,
                                                  List<AttachmentRef> attachments,
                                                  com.huawei.it.ex.one.domain.memory.MemoryContext memory) {
                routeCalls.incrementAndGet();
                assertThat(events.events).extracting(ChatEvent::type).contains("run.started");
                return RouteSignalResult.of(RouteTarget.agentRuntime("test-runtime"));
            }

            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                return Flux.defer(() -> Flux.just(RouteSignalFrame.result(
                        routeInitial(request.user(), request.session(), request.command(),
                                request.attachments(), request.memory()))));
            }
        };
        FinanceEXChatService service = defaultFinanceService(sessions, messages, runs, events, routeService);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                                null, null, "web", "hello", List.of(), Map.of())),
                        1)
                .expectNextMatches(event -> "run.started".equals(event.type()))
                .thenCancel()
                .verify();
        assertThat(routeCalls.get()).isLessThanOrEqualTo(1);
    }

    @Test
    void firstEventTimeoutAbortsBackgroundHandoffOnlyOnce() {
        AtomicInteger abortCalls = new AtomicInteger();

        StepVerifier.withVirtualTime(() -> FinanceEXChatService.withFirstEventTimeout(
                        Mono.<ChatEvent>never(), Duration.ofSeconds(30), abortCalls::incrementAndGet))
                .thenAwait(Duration.ofSeconds(30))
                .expectErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("RUN_FIRST_EVENT_TIMEOUT"))
                .verify();

        assertThat(abortCalls).hasValue(1);
    }

    @Test
    void firstEventTimeoutDoesNotLimitRunAfterSuccessfulHandoff() {
        AtomicInteger abortCalls = new AtomicInteger();
        ChatEvent first = RunStartedEvent.of("run1", "session1");

        StepVerifier.create(FinanceEXChatService.withFirstEventTimeout(
                        Mono.just(first), Duration.ofMillis(1), abortCalls::incrementAndGet))
                .expectNext(first)
                .verifyComplete();

        assertThat(abortCalls).hasValue(0);
    }

    @Test
    void firstEventTimeoutFailsPersistedRunAndDoesNotInvokeRouteOrRuntime() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        BlockingRunStartedEventStore events = new BlockingRunStartedEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        AtomicInteger routeCalls = new AtomicInteger();
        ChatRunOperationalProperties properties = new ChatRunOperationalProperties();
        properties.setFirstEventTimeout(Duration.ofMillis(50));
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, countingRuntimeRouteService(routeCalls), noopRuntime(),
                executions, properties);
        UserContext user = new UserContext("tenant1", "user1", "User One");

        assertThatThrownBy(() -> service.startRun(user, new ChatCommand(
                        "cmd1", null, null, null, null, "web", "hello", List.of(), Map.of()),
                        RuntimeForwardHeaders.empty()).block())
                .hasMessageContaining("RUN_FIRST_EVENT_TIMEOUT");

        awaitEvent(events, "run.failed");
        events.releaseRunStarted();
        assertThat(runs.findById("run_1")).get().extracting(ChatRun::status).isEqualTo(ChatRunStatus.FAILED);
        assertThat(executions.findByRunId("run_1")).get()
                .extracting(ChatRunExecution::executionStatus).isEqualTo(ChatRunExecutionStatus.FAILED);
        assertThat(routeCalls).hasValue(0);
        assertThat(((InMemoryEventStore) events).events).extracting(ChatEvent::type).containsExactly("run.failed");
    }

    @Test
    void firstEventTimeoutAfterAdmissionDuringCacheSyncDoesNotLeaveRunningOrphan() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        BlockingPutRunCache runCache = new BlockingPutRunCache();
        AtomicInteger routeCalls = new AtomicInteger();
        ChatRunOperationalProperties properties = new ChatRunOperationalProperties();
        properties.setFirstEventTimeout(Duration.ofMillis(500));
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, countingRuntimeRouteService(routeCalls), noopRuntime(),
                executions, properties, runCache);
        UserContext user = new UserContext("tenant1", "user1", "User One");
        java.util.concurrent.CompletableFuture<ChatRunStartResult> start = service.startRun(user, new ChatCommand(
                        "cmd1", null, null, null, null, "web", "hello", List.of(), Map.of()),
                        RuntimeForwardHeaders.empty())
                .toFuture();

        try {
            assertThat(runCache.awaitPut()).isTrue();
            assertThatThrownBy(start::join)
                    .rootCause()
                    .hasMessageContaining("RUN_FIRST_EVENT_TIMEOUT");
        } finally {
            runCache.releasePut();
        }

        awaitEvent(events, "run.failed");
        assertThat(runs.findById("run_1")).get().extracting(ChatRun::status).isEqualTo(ChatRunStatus.FAILED);
        assertThat(executions.findByRunId("run_1")).isEmpty();
        assertThat(routeCalls).hasValue(0);
        assertThat(events.events).extracting(ChatEvent::type).containsExactly("run.failed");
    }
}
