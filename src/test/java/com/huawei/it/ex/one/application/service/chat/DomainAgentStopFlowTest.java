/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static com.huawei.it.ex.one.application.service.observation.ObservationTestFixture.await;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatInteractionProperties;
import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.config.ResourceIsolationProperties;
import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.service.observation.ObservationTestFixture;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.CandidateDomainAgentSwitchCommand;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunCancelledEvent;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.ConfiguredDomainAgentClient;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentChatRequestMapper;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentRuntime;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class DomainAgentStopFlowTest extends ChatFlowTestSupport {
    @ParameterizedTest
    @CsvSource({"false,200", "false,302", "false,500", "true,200", "true,500"})
    void waitsForHttpBeforeLocalCancellationAndCommitsOnlyOnce(boolean async, int status) {
        Sinks.One<ClientResponse> response = Sinks.one();
        Fixture fixture = new Fixture(async);
        var coordinator = fixture.coordinator(request -> response.asMono());

        StepVerifier.create(coordinator.stopRun(fixture.user, "run1", "USER_STOP", RuntimeForwardHeaders.empty()))
                .then(fixture::assertPending)
                .expectNoEvent(Duration.ofMillis(30))
                .then(() -> response.tryEmitValue(ClientResponse.create(HttpStatus.valueOf(status)).build()))
                .assertNext(result -> assertThat(result.status()).isEqualTo(ChatRunStatus.CANCELLED))
                .verifyComplete();

        fixture.assertCancelled();
        coordinator.stopRun(fixture.user, "run1", "USER_STOP", RuntimeForwardHeaders.empty())
                .block(Duration.ofSeconds(5));
        assertThat(fixture.requests).hasValue(1);
        assertThat(fixture.events.events).filteredOn(event -> "run.cancelled".equals(event.type())).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void httpTimeoutReleasesRequestAndFinalizesNormalOrAsyncRun(boolean async) {
        Fixture fixture = new Fixture(async);
        AtomicBoolean httpCancelled = new AtomicBoolean();
        var coordinator = fixture.coordinator(request -> Mono.<ClientResponse>never()
                .doOnCancel(() -> httpCancelled.set(true)));

        StepVerifier.withVirtualTime(() -> coordinator.stopRun(
                        fixture.user, "run1", "USER_STOP", RuntimeForwardHeaders.empty()))
                .then(fixture::assertPending)
                .expectNoEvent(Duration.ofSeconds(1))
                .then(fixture::assertPending)
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(result -> assertThat(result.status()).isEqualTo(ChatRunStatus.CANCELLED))
                .verifyComplete();
        assertThat(httpCancelled).isTrue();
        fixture.assertCancelled();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelledStopRequestFinalizesWithoutWaitingForTimeoutOrAnotherStop(boolean async) throws Exception {
        Fixture fixture = new Fixture(async);
        fixture.properties.setStopTimeout("120s");
        AtomicBoolean httpCancelled = new AtomicBoolean();
        var coordinator = fixture.coordinator(request -> Mono.<ClientResponse>never()
                .doOnCancel(() -> httpCancelled.set(true)));

        StepVerifier.create(coordinator.stopRun(fixture.user, "run1", "USER_STOP", RuntimeForwardHeaders.empty()))
                .then(fixture::assertPending).thenCancel().verify();
        assertThat(httpCancelled).isTrue();
        assertThat(fixture.stopCommitted.await(2, TimeUnit.SECONDS)).as("automatic local finalization").isTrue();
        fixture.assertCancelled();
        coordinator.stopRun(fixture.user, "run1", "USER_STOP", RuntimeForwardHeaders.empty())
                .block(Duration.ofSeconds(5));
        assertThat(fixture.requests).hasValue(1);
        assertThat(fixture.events.events).filteredOn(event -> "run.cancelled".equals(event.type())).hasSize(1);
    }

    @Test
    void cancelledHttpStopPreservesTraceUntilCommitWithoutRecordingDownstreamAcceptance() {
        try (var observation = new ObservationTestFixture()) {
            Fixture fixture = new Fixture(true);
            observation.turns.begin(fixture.user, "run1", "NEXT", "session1");
            var run = observation.turns.decorate(fixture.runs.findById("run1").orElseThrow());
            fixture.runs.save(run);
            observation.turns.committed(run, null);
            var expected = observation.turns.context("run1");
            var coordinator = fixture.coordinator(request -> {
                assertThat(request.headers().getFirst("X-Trace-Id")).isEqualTo(expected.traceId());
                assertThat(request.headers().getFirst("X-Observation-Id")).startsWith("obs_");
                return Mono.never();
            });
            observation.wire(coordinator);
            fixture.registry.register("run1", () -> {
                fixture.localCancelled.set(true);
                observation.turns.finishStart("run1");
                assertThat(observation.turns.context("run1")).isNotNull();
            });

            StepVerifier.create(coordinator.stopRun(
                            fixture.user, "run1", "USER_STOP", RuntimeForwardHeaders.empty()))
                    .then(fixture::assertPending).thenCancel().verify();

            await(() -> observation.turns.context("run1") == null);
            await(() -> observation.calls.stream().anyMatch(call -> "FINISHED".equals(call.status())));
            assertThat(observation.calls).filteredOn(call -> "FINISHED".equals(call.status()))
                    .singleElement().satisfies(call -> {
                        assertThat(call.outcome()).isEqualTo("CANCELLED");
                        assertThat(call.traceId()).isEqualTo(expected.traceId());
                    });
            await(() -> observation.traces.stream().anyMatch(trace -> "CANCELLED".equals(trace.status())));
            fixture.assertCancelled();
        }
    }

    @Test
    void cancelledCandidateSwitchStopsOldRunWithoutStartingReplacement() throws Exception {
        Fixture fixture = new Fixture(false);
        var coordinator = fixture.coordinator(request -> Mono.never());
        var start = mock(ChatRunStartCoordinator.class);
        var switches = new CandidateDomainAgentSwitchApplicationService(
                new ChatRunApplicationService(fixture.runs, new NeverCancelRunCache(), fixture.events,
                        new PermissionChecker(), fixture.sessions),
                fixture.sessionService, fixture.messages, mock(DocumentFacade.class), coordinator,
                start, mock(ChatRunExecutionCoordinator.class), mock(CandidateSwitchRouteTraceService.class),
                new InMemoryInteractionRequestRepository());
        StepVerifier.create(switches.switchDomainAgent(fixture.user, TraceContext.empty(),
                        new CandidateDomainAgentSwitchCommand("run1", "msg-user", "skill-b", Map.of(), null, null),
                        RuntimeForwardHeaders.empty()))
                .then(fixture::assertPending).thenCancel().verify();

        assertThat(fixture.stopCommitted.await(2, TimeUnit.SECONDS)).isTrue();
        fixture.assertCancelled();
        verifyNoInteractions(start);
        assertThat(fixture.requests).hasValue(1);
    }

    @Test
    void stoppingWaitingSourceAlsoProtectsItsActiveContinuation() throws Exception {
        Fixture fixture = new Fixture(false);
        var coordinator = fixture.coordinator(request -> Mono.never());
        Instant now = Instant.now();
        ChatRun source = new ChatRun("run-a", "tenant1", "user1", "session1", ChatRunStatus.WAITING_USER,
                "AGENT_RUNTIME", "skill-a", "domain-agent", "downstream-session", 1L, 9L, null,
                now, now, Map.of(), now, now);
        fixture.runs.save(source);
        var waiting = mock(ChatWaitingStopCommitService.class);
        when(waiting.cancelWaiting(fixture.user, source, "USER_STOP"))
                .thenReturn(new ChatWaitingStopCommitService.WaitingStopCommitResult(
                        source, null, fixture.runs.findById("run1").orElseThrow(), false, null, null, null));
        coordinator.setWaitingStopCommitService(waiting);

        StepVerifier.create(coordinator.stopRun(fixture.user, "run-a", "USER_STOP", RuntimeForwardHeaders.empty()))
                .then(fixture::assertPending).thenCancel().verify();

        assertThat(fixture.stopCommitted.await(2, TimeUnit.SECONDS)).isTrue();
        fixture.assertCancelled();
        assertThat(fixture.runs.findById("run-a").orElseThrow().status()).isEqualTo(ChatRunStatus.WAITING_USER);
    }

    @Test
    void concurrentResponseAndCallerCancellationStillFinalizeOnce() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            Fixture fixture = new Fixture(true);
            Sinks.One<ClientResponse> response = Sinks.one();
            var coordinator = fixture.coordinator(request -> response.asMono());
            var subscription = coordinator.stopRun(fixture.user, "run1", "USER_STOP", RuntimeForwardHeaders.empty())
                    .subscribe();
            CountDownLatch start = new CountDownLatch(1);
            var sender = Thread.startVirtualThread(() -> {
                try { start.await(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
                response.tryEmitValue(ClientResponse.create(HttpStatus.OK).build());
            });
            try {
                start.countDown();
                subscription.dispose();
                assertThat(fixture.stopCommitted.await(2, TimeUnit.SECONDS)).isTrue();
                fixture.assertCancelled();
                assertThat(fixture.events.events).filteredOn(event -> "run.cancelled".equals(event.type())).hasSize(1);
            } finally {
                subscription.dispose();
                assertThat(sender.join(Duration.ofSeconds(2))).isTrue();
            }
        }
    }

    @Test
    void sessionDeletionDoesNotStarveTwoThreadSchedulerInIsolatedJvm() throws Exception {
        Path output = Files.createTempFile("stop-delete-isolation-", ".log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dreactor.schedulers.defaultBoundedElasticSize=2",
                "-Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=false", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                DeleteIsolationProbe.class.getName())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("delete probe finished").isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(output)).isZero();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(output);
        }
    }

    public static final class DeleteIsolationProbe {
        public static void main(String[] args) throws Exception {
            assertThat(Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE).isEqualTo(2);
            var test = new DomainAgentStopFlowTest();
            CountDownLatch requests = new CountDownLatch(2);
            Sinks.One<ClientResponse> firstResponse = Sinks.one();
            Sinks.One<ClientResponse> secondResponse = Sinks.one();
            Fixture first = test.new Fixture(true);
            Fixture second = test.new Fixture(true);
            first.coordinator(request -> { requests.countDown(); return firstResponse.asMono(); });
            second.coordinator(request -> { requests.countDown(); return secondResponse.asMono(); });
            try {
                for (Fixture fixture : List.of(first, second)) {
                    TransactionSynchronizationManager.initSynchronization();
                    try {
                        fixture.sessionService.deleteSession(fixture.user, "session1");
                        assertThat(fixture.requests).hasValue(0);
                        TransactionSynchronizationManager.getSynchronizations()
                                .forEach(TransactionSynchronization::afterCommit);
                    } finally {
                        TransactionSynchronizationManager.clearSynchronization();
                    }
                }
                assertThat(requests.await(5, TimeUnit.SECONDS)).as("both HTTP requests started").isTrue();
                assertThat(Mono.fromCallable(() -> "unrelated")
                        .subscribeOn(Schedulers.boundedElastic()).toFuture().get(2, TimeUnit.SECONDS))
                        .isEqualTo("unrelated");
                firstResponse.tryEmitValue(ClientResponse.create(HttpStatus.OK).build());
                secondResponse.tryEmitValue(ClientResponse.create(HttpStatus.OK).build());
                assertThat(first.stopCommitted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(second.stopCommitted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(first.runs.findById("run1").orElseThrow().status()).isEqualTo(ChatRunStatus.CANCELLED);
                assertThat(second.runs.findById("run1").orElseThrow().status()).isEqualTo(ChatRunStatus.CANCELLED);
                verifyRejectedScheduling(test);
            } finally {
                firstResponse.tryEmitEmpty();
                secondResponse.tryEmitEmpty();
                Schedulers.shutdownNow();
            }
        }

        private static void verifyRejectedScheduling(DomainAgentStopFlowTest test) {
            Logger logger = (Logger) LoggerFactory.getLogger(SessionApplicationService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.list = new CopyOnWriteArrayList<>();
            appender.start();
            logger.addAppender(appender);
            Fixture fixture = test.new Fixture(true);
            fixture.coordinator(request -> Mono.never());
            Schedulers.onScheduleHook("delete-rejected", task -> {
                throw new RejectedExecutionException("controlled rejection");
            });
            try {
                assertThat(fixture.sessionService.deleteSession(fixture.user, "session1").status()).isEqualTo("DELETED");
                assertThat(fixture.requests).hasValue(0);
                assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("chat-session.delete.stop-run.schedule"));
            } finally {
                Schedulers.resetOnScheduleHook("delete-rejected");
                logger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    @Test
    void competingTerminalDuringHttpWaitRemainsTheOnlyWinner() {
        Fixture fixture = new Fixture(true);
        Sinks.One<ClientResponse> response = Sinks.one();
        var coordinator = fixture.coordinator(request -> response.asMono());
        StepVerifier.create(coordinator.stopRun(fixture.user, "run1", "USER_STOP", RuntimeForwardHeaders.empty()))
                .then(fixture::assertPending)
                .then(() -> {
                    var run = fixture.runs.findById("run1").orElseThrow();
                    var event = RunCancelledEvent.of("run1", "session1", "USER_STOP", true, "msg-assistant");
                    var committed = fixture.terminal.commitExternalTerminal(
                            ChatRunTerminalCommitService.ExternalTerminalCommitCommand.stop(event, run, null));
                    assertThat(committed.committed()).isTrue();
                    response.tryEmitValue(ClientResponse.create(HttpStatus.OK).build());
                })
                .assertNext(result -> assertThat(result.status()).isEqualTo(ChatRunStatus.CANCELLED))
                .verifyComplete();
        fixture.assertCancelled();
        assertThat(fixture.events.events).filteredOn(event -> "run.cancelled".equals(event.type())).hasSize(1);
    }

    @Test
    void deletedAsyncSessionStillWaitsThenClosesWithoutUpdatingHistory() {
        Fixture fixture = new Fixture(true);
        var session = fixture.sessions.findById("session1").orElseThrow();
        fixture.sessions.save(new ChatSession(session.id(), session.tenantId(), session.userId(), session.title(),
                "DELETED", session.channel(), session.createdAt(), session.updatedAt()));
        var originalMessages = java.util.List.copyOf(fixture.messages.messages);
        Sinks.One<ClientResponse> response = Sinks.one();
        var coordinator = fixture.coordinator(request -> response.asMono());
        StepVerifier.create(coordinator.stopRun(fixture.user, "run1", "SESSION_DELETED", RuntimeForwardHeaders.empty()))
                .then(fixture::assertPending)
                .then(() -> response.tryEmitValue(ClientResponse.create(HttpStatus.OK).build()))
                .assertNext(result -> assertThat(result.status()).isEqualTo(ChatRunStatus.CANCELLED))
                .verifyComplete();
        assertThat(fixture.messages.messages).containsExactlyElementsOf(originalMessages);
        assertThat(fixture.sessions.findById("session1").orElseThrow().status()).isEqualTo("DELETED");
        assertThat(fixture.executions.findByRunId("run1").orElseThrow().executionStatus())
                .isEqualTo(ChatRunExecutionStatus.CANCELLED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledOrUnconfiguredStopSkipsHttp(boolean enabled) {
        Fixture fixture = new Fixture(false);
        fixture.properties.setEnabled(enabled);
        if (enabled) {
            fixture.properties.setStopPath(" ");
        }
        var coordinator = fixture.coordinator(request -> Mono.error(new AssertionError("Unexpected HTTP")));
        coordinator.stopRun(fixture.user, "run1", "USER_STOP", RuntimeForwardHeaders.empty())
                .block(Duration.ofSeconds(5));
        fixture.assertCancelled();
        assertThat(fixture.requests).hasValue(0);
    }

    private final class Fixture {
        private final UserContext user = new UserContext("tenant1", "user1", "User One");
        private final InMemorySessionRepository sessions = new InMemorySessionRepository();
        private final InMemoryMessageRepository messages = new InMemoryMessageRepository();
        private final InMemoryRunRepository runs = new InMemoryRunRepository() {
            @Override
            public Optional<com.huawei.it.ex.one.domain.chat.ChatRun> findActiveBySession(
                    String tenantId, String userId, String sessionId) {
                return findById("run1").filter(run -> tenantId.equals(run.tenantId())
                        && userId.equals(run.userId()) && sessionId.equals(run.sessionId()) && run.stopRetryable());
            }
        };
        private final InMemoryEventStore events = new InMemoryEventStore();
        private final CountDownLatch stopCommitted = new CountDownLatch(1);
        private final InMemoryExecutionRepository executions = new InMemoryExecutionRepository() {
            @Override
            public boolean markTerminal(String runId, ChatRunExecutionStatus status) {
                boolean updated = super.markTerminal(runId, status);
                if (updated && status == ChatRunExecutionStatus.CANCELLED) {
                    stopCommitted.countDown();
                }
                return updated;
            }
        };
        private final LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        private final DomainAgentProperties properties = new DomainAgentProperties();
        private final AtomicBoolean localCancelled = new AtomicBoolean();
        private final AtomicInteger requests = new AtomicInteger();
        private final boolean async;
        private ChatRunTerminalCommitService terminal;
        private SessionApplicationService sessionService;

        private Fixture(boolean async) {
            this.async = async;
            seedRunningRun(sessions, messages, runs, user, "run1", "session1", "msg-user");
            var run = runs.findById("run1").orElseThrow()
                    .withResolvedRoute("AGENT_RUNTIME", "skill-a", "domain-agent", "downstream-session");
            if (async) {
                run = run.withAssistantMessageId("msg-assistant").withMetadata(
                        DomainAgentAsyncTaskMetadata.runningOverlay("msg-assistant", Instant.now().plusSeconds(3600)));
                messages.save(new ChatMessage("msg-assistant", "tenant1", "user1", "session1",
                        "msg-user", 2L, 1, 0, "assistant", "partial async answer", null, "run1", "NORMAL",
                        false, null, null, null, null,
                        "{\"skillId\":\"skill-a\",\"domainAgentAsyncTask\":{\"status\":\"ASYNC_RUNNING\"}}",
                        Instant.now()));
            }
            runs.save(run);
            executions.createForRun(run, "exec1", "instance-test", Duration.ofMinutes(1));
            if (async) {
                executions.markTerminal("run1", ChatRunExecutionStatus.ASYNC_WAITING);
            }
            registry.register("run1", () -> localCancelled.set(true));
            properties.setEnabled(true);
            properties.setBaseUrl("http://domain.test");
            properties.setStopPath("/stop");
            properties.setStopTimeout("2s");
        }

        private ChatRunStopCoordinator coordinator(ExchangeFunction exchange) {
            var ids = new FixedIdGenerator();
            var permissions = new PermissionChecker();
            var streams = new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(),
                    liveEventBus(), runs, permissions, sessions, new ChatWebSocketProperties());
            var runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(), events, permissions, sessions);
            var beans = new DefaultListableBeanFactory();
            sessionService = new SessionApplicationService(sessions, messages, ids, permissions, runService,
                    null, null, beans.getBeanProvider(ChatRunStopCoordinator.class));
            var lease = new ChatRunLeaseApplicationService(executions, () -> "instance-test",
                    new ChatRunOperationalProperties(), ids, registry);
            var interactions = new ChatInteractionApplicationService(new InMemoryInteractionRequestRepository(),
                    ids, permissions, new ChatInteractionProperties());
            terminal = new ChatRunTerminalCommitService(streams, sessionService, runs, lease,
                    runtimeBindingRepository(), interactions, Duration.ofDays(3));
            var client = new ConfiguredDomainAgentClient(WebClient.builder().exchangeFunction(request -> {
                requests.incrementAndGet();
                assertThat(runs.findById("run1").orElseThrow().status()).isEqualTo(ChatRunStatus.CANCELLING);
                return exchange.exchange(request);
            }), properties, new DomainAgentChatRequestMapper(properties),
                    new DomainAgentResponseNormalizer(new ObjectMapper(), properties));
            var runtime = new AgentRuntimeExecutor(new DomainAgentRuntime(client),
                    new WorkloadConcurrencyLimiter(new ResourceIsolationProperties()));
            var coordinator = new ChatRunStopCoordinator(sessionService, streams, runService, lease, registry, runtime,
                    interactions, terminal, ids);
            beans.registerSingleton("stopCoordinator", coordinator);
            return coordinator;
        }

        private void assertPending() {
            assertThat(runs.findById("run1").orElseThrow().status()).isEqualTo(ChatRunStatus.CANCELLING);
            assertThat(localCancelled).isFalse();
            assertThat(events.events).noneMatch(event -> "run.cancelled".equals(event.type()));
            assertThat(executions.findByRunId("run1").orElseThrow().executionStatus())
                    .isEqualTo(async ? ChatRunExecutionStatus.ASYNC_WAITING : ChatRunExecutionStatus.RUNNING);
        }

        private void assertCancelled() {
            assertThat(localCancelled).isTrue();
            assertThat(runs.findById("run1").orElseThrow().status()).isEqualTo(ChatRunStatus.CANCELLED);
            assertThat(executions.findByRunId("run1").orElseThrow().executionStatus())
                    .isEqualTo(ChatRunExecutionStatus.CANCELLED);
            if (async) {
                var assistant = messages.messages.stream().filter(message -> "msg-assistant".equals(message.id()))
                        .findFirst().orElseThrow();
                assertThat(assistant.content()).isEqualTo("partial async answer");
                assertThat(assistant.metadataJson()).contains("\"status\":\"CANCELLED\"");
                assertThat(runs.findById("run1").orElseThrow().metadata())
                        .doesNotContainKey(DomainAgentAsyncTaskMetadata.RUN_METADATA_KEY);
            }
        }
    }
}
