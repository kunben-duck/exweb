/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatInteractionProperties;
import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRecoveryPort;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRecoveryRequest;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventStore;
import com.huawei.it.ex.one.application.integration.conversation.ChatInteractionRequestRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunCache;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRecoverLock;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.service.recovery.FailFastRecoveryStrategy;
import com.huawei.it.ex.one.application.service.recovery.ManualConfirmationRecoveryStrategy;
import com.huawei.it.ex.one.application.service.recovery.RuntimeTakeoverRecoveryStrategy;
import com.huawei.it.ex.one.application.service.recovery.StaleRunRecoveryStrategy;
import com.huawei.it.ex.one.application.service.recovery.StaleRunRecoveryStrategyRegistry;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunCancelSignal;
import com.huawei.it.ex.one.domain.chat.ChatRunExecution;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ChatSessionPage;
import com.huawei.it.ex.one.domain.chat.ErrorEvent;
import com.huawei.it.ex.one.domain.chat.RunCancelledEvent;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;

import reactor.core.publisher.Flux;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
class ChatRunRecoveryOrchestratorTest {
    @Test
    void manualConfirmationClosesStaleRunAndWritesSingleFailedEvent() {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryRunCache cache = new InMemoryRunCache();
        FixedSessionRepository sessions = new FixedSessionRepository();
        PermissionChecker permissionChecker = new PermissionChecker();
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), new NoopLiveEventBus(), runs,
                permissionChecker, sessions, new ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, cache, events,
                permissionChecker, sessions);
        ChatRunOperationalProperties properties = new ChatRunOperationalProperties();
        properties.setWatchdogBatchSize(10);
        properties.setWatchdogMaxClaimsPerScan(10);
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(executions,
                () -> "instance-b", properties, new FixedIdGenerator(), registry);
        InMemoryInteractionRepository interactions = new InMemoryInteractionRepository();
        ChatInteractionApplicationService interactionService = interactionService(interactions);
        ChatRunTerminalCommitService terminalCommitService = terminalCommitService(
                streamService, runs, leaseService, interactionService);
        List<StaleRunRecoveryStrategy> strategies = List.of(
                new ManualConfirmationRecoveryStrategy(streamService, terminalCommitService, runService),
                new FailFastRecoveryStrategy(streamService, terminalCommitService, runService),
                new RuntimeTakeoverRecoveryStrategy(unsupportedRuntimeRecovery())
        );
        ChatRunRecoveryOrchestrator orchestrator = new ChatRunRecoveryOrchestrator(executions, runs,
                new AllowingRecoverLock(), () -> "instance-b", properties, new ChatRunRecoveryCapacityLimiter(properties),
                new StaleRunRecoveryStrategyRegistry(strategies), interactionService,
                terminalCommitService, streamService, runService);
        ChatRun run = runningRun();
        runs.save(run);
        executions.put(staleExecution(run));
        cache.putActive(run);

        int recovered = orchestrator.recoverExpiredRuns();

        assertThat(recovered).isEqualTo(1);
        assertThat(runs.findById("run1")).get().extracting(ChatRun::status).isEqualTo(ChatRunStatus.FAILED);
        assertThat(executions.findByRunId("run1")).get().extracting(ChatRunExecution::executionStatus)
                .isEqualTo(ChatRunExecutionStatus.FAILED);
        assertThat(events.events).hasSize(1);
        assertThat(events.events.getFirst().type()).isEqualTo("run.failed");
        assertThat(events.events.getFirst().payload()).containsEntry("recoveryActionRequired", true);
        assertThat(cache.getActive("tenant1", "user1", "session1")).isEmpty();
    }

    @Test
    void watchdogClosesStaleCancellingRunAsCancelled() {
        TestFixture fixture = fixture();
        ChatRun run = runningRun().cancelling("USER_STOP");
        fixture.runs.save(run);
        fixture.executions.put(staleExecution(run));

        int recovered = fixture.orchestrator.recoverExpiredRuns();

        assertThat(recovered).isEqualTo(1);
        assertThat(fixture.runs.findById(run.id())).get().extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.CANCELLED);
        assertThat(fixture.executions.findByRunId(run.id())).get()
                .extracting(ChatRunExecution::executionStatus)
                .isEqualTo(ChatRunExecutionStatus.CANCELLED);
        assertThat(fixture.events.events).extracting(ChatEvent::type).containsExactly("run.cancelled");
        assertThat(fixture.events.events.getFirst().payload()).containsEntry("messageReady", false);
    }

    @Test
    void watchdogClosesExpiredAsyncWaitingRunWithoutTreatingItAsOwnerRecovery() {
        TestFixture fixture = fixture();
        Instant now = Instant.now();
        ChatRun run = runningRun().withMetadataSnapshot(
                DomainAgentAsyncTaskMetadata.runningOverlay("", now.minusSeconds(1)));
        fixture.runs.save(run);
        fixture.executions.put(new ChatRunExecution(
                "exec-async", run.id(), run.tenantId(), run.userId(), run.sessionId(),
                ChatRunExecutionStatus.ASYNC_WAITING, null, null, now.minusSeconds(1),
                2L, null, null, 0, null, null, Map.of(), now.minusSeconds(60), now));

        int recovered = fixture.orchestrator.recoverExpiredRuns();

        assertThat(recovered).isEqualTo(1);
        assertThat(fixture.runs.findById(run.id())).get().extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.FAILED);
        assertThat(fixture.executions.findByRunId(run.id())).get()
                .extracting(ChatRunExecution::executionStatus)
                .isEqualTo(ChatRunExecutionStatus.FAILED);
        assertThat(fixture.events.events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("run.failed");
            assertThat(event.payload()).containsEntry("code", "DOMAIN_AGENT_ASYNC_TIMEOUT");
        });
    }

    @Test
    void watchdogFailureReleasesContinuationInteractionClaim() {
        TestFixture fixture = fixture();
        ChatRun run = continuationRun("run1", "interaction1");
        fixture.runs.save(run);
        fixture.executions.put(staleExecution(run));
        fixture.interactions.insert(waitingInteraction("interaction1"));
        fixture.interactionService.claimInteractionResponse(new ChatInteractionResponseCommand(
                user(), "interaction1", null, null, Map.of("问题", "答案"), Map.of()), run.id());

        int recovered = fixture.orchestrator.recoverExpiredRuns();

        assertThat(recovered).isEqualTo(1);
        ChatInteractionRequest interaction = fixture.interactions.requests.get("interaction1");
        assertThat(interaction.status()).isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(interaction.continueRunId()).isNull();
        assertThat(fixture.runs.findById(run.id())).get().extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.FAILED);
    }

    @Test
    void failFastRecoveryAlsoReleasesContinuationInteractionClaim() {
        TestFixture fixture = fixture();
        fixture.properties.setStaleRecoveryStrategies(List.of(FailFastRecoveryStrategy.NAME));
        ChatRun run = continuationRun("run1", "interaction1");
        fixture.runs.save(run);
        fixture.executions.put(staleExecution(run));
        fixture.interactions.insert(waitingInteraction("interaction1"));
        fixture.interactionService.claimInteractionResponse(new ChatInteractionResponseCommand(
                user(), "interaction1", null, null, Map.of("问题", "答案"), Map.of()), run.id());

        int recovered = fixture.orchestrator.recoverExpiredRuns();

        assertThat(recovered).isEqualTo(1);
        ChatInteractionRequest interaction = fixture.interactions.requests.get("interaction1");
        assertThat(interaction.status()).isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(interaction.continueRunId()).isNull();
        assertThat(fixture.executions.findByRunId(run.id())).get()
                .extracting(ChatRunExecution::executionStatus)
                .isEqualTo(ChatRunExecutionStatus.FAILED);
    }

    @Test
    void watchdogReconcilesOrphanClaimOnlyForCurrentTerminalContinuation() {
        TestFixture fixture = fixture();
        fixture.interactions.insert(waitingInteraction("interaction1"));
        fixture.interactionService.claimInteractionResponse(new ChatInteractionResponseCommand(
                user(), "interaction1", null, null, Map.of("问题", "答案"), Map.of()), "run-terminal");
        fixture.interactions.terminalRunIds.add("run-terminal");

        int recovered = fixture.orchestrator.recoverExpiredRuns();

        assertThat(recovered).isZero();
        ChatInteractionRequest interaction = fixture.interactions.requests.get("interaction1");
        assertThat(interaction.status()).isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(interaction.continueRunId()).isNull();
    }

    @Test
    void watchdogReleasesRespondingClaimWhenContinuationRunWasNeverCreated() {
        TestFixture fixture = fixture();
        fixture.interactions.insert(waitingInteraction("interaction1"));
        fixture.interactionService.claimInteractionResponse(new ChatInteractionResponseCommand(
                user(), "interaction1", null, null, Map.of("问题", "答案"), Map.of()), "run-missing");
        fixture.interactions.missingRunIds.add("run-missing");

        int recovered = fixture.orchestrator.recoverExpiredRuns();

        assertThat(recovered).isZero();
        ChatInteractionRequest interaction = fixture.interactions.requests.get("interaction1");
        assertThat(interaction.status()).isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(interaction.continueRunId()).isNull();
    }

    @Test
    void watchdogFailsContinuationRunThatNeverCreatedExecution() {
        TestFixture fixture = fixture();
        ChatRun run = continuationRun("run-orphan", "interaction1");
        fixture.runs.save(run);
        fixture.interactions.insert(waitingInteraction("interaction1"));
        fixture.interactionService.claimInteractionResponse(new ChatInteractionResponseCommand(
                user(), "interaction1", null, null, Map.of("问题", "答案"), Map.of()), run.id());
        fixture.interactions.missingExecutionRunIds.add(run.id());

        int recovered = fixture.orchestrator.recoverExpiredRuns();

        assertThat(recovered).isZero();
        assertThat(fixture.runs.findById(run.id())).get().extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.FAILED);
        assertThat(fixture.interactions.requests.get("interaction1").status())
                .isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(fixture.events.events).extracting(ChatEvent::type).containsExactly("run.failed");
    }

    @Test
    void watchdogFailsOrdinaryRunThatNeverCreatedExecution() {
        TestFixture fixture = fixture();
        ChatRun run = runningRun("run-orphan", "tenant1");
        fixture.runs.save(run);
        fixture.runs.executionInitOrphanIds.add(run.id());

        int recovered = fixture.orchestrator.recoverExpiredRuns();

        assertThat(recovered).isEqualTo(1);
        assertThat(fixture.runs.findById(run.id())).get().extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.FAILED);
        assertThat(fixture.events.events).extracting(ChatEvent::type).containsExactly("run.failed");
        assertThat(fixture.events.events.getFirst().payload())
                .containsEntry("code", "RUN_EXECUTION_INIT_ORPHANED")
                .containsEntry("source", "chat-run-watchdog");
    }

    @Test
    void externalTerminalClaimAllowsOnlyOneConflictingTerminalEvent() throws Exception {
        TestFixture fixture = fixture();
        ChatRun run = runningRun();
        fixture.runs.save(run);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<ChatRunTerminalCommitService.ExternalTerminalCommitResult> cancelled =
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return fixture.terminalCommitService.commitExternalTerminal(
                                new ChatRunTerminalCommitService.ExternalTerminalCommitCommand(
                                        RunCancelledEvent.of(run.id(), run.sessionId(), "USER_STOP"), run));
                    });
            java.util.concurrent.Future<ChatRunTerminalCommitService.ExternalTerminalCommitResult> failed =
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return fixture.terminalCommitService.commitExternalTerminal(
                                new ChatRunTerminalCommitService.ExternalTerminalCommitCommand(
                                        ErrorEvent.of(run.id(), run.sessionId(),
                                                "RUN_EXECUTOR_LOST", "执行实例失联"), run));
                    });
            ready.await();
            start.countDown();

            long committed = java.util.stream.Stream.of(cancelled.get(), failed.get())
                    .filter(ChatRunTerminalCommitService.ExternalTerminalCommitResult::committed)
                    .count();
            assertThat(committed).isEqualTo(1);
            assertThat(fixture.events.events).hasSize(1);
            assertThat(fixture.runs.findById(run.id())).get().extracting(ChatRun::status)
                    .satisfies(status -> assertThat(status).isIn(ChatRunStatus.CANCELLED, ChatRunStatus.FAILED));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void recoveryDoesNotReportSuccessWhenTerminalClaimLostButRunIsStillActive() {
        TestFixture fixture = fixture();
        ChatRun run = runningRun();
        fixture.runs.save(run);
        fixture.runs.rejectExternalTerminalClaims = true;
        fixture.executions.put(staleExecution(run));

        int recovered = fixture.orchestrator.recoverExpiredRuns();

        assertThat(recovered).isZero();
        assertThat(fixture.runs.findById(run.id())).get().extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.RUNNING);
        assertThat(fixture.events.events).isEmpty();
    }

    @Test
    void recoveryClaimMovesExecutionOutOfWritableRunningState() {
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        ChatRun run = runningRun();
        executions.put(staleExecution(run));

        Optional<ChatRunExecution> claimed = executions.tryClaimRecovering("run1", "instance-b",
                "MANUAL_CONFIRMATION", Duration.ofSeconds(30));

        assertThat(claimed).isPresent();
        assertThat(claimed.get().executionStatus()).isEqualTo(ChatRunExecutionStatus.RECOVERING);
        assertThat(claimed.get().fencingToken()).isEqualTo(2L);
    }

    @Test
    void recoveryCapacityFullSkipsClaimingStaleRun() {
        TestFixture fixture = fixture();
        ChatRun run = runningRun("run1", "tenant1");
        fixture.runs.save(run);
        fixture.executions.put(staleExecution(run));
        ChatRunRecoveryCapacityLimiter.Permit occupied = fixture.capacityLimiter.tryAcquireRecovery();

        try (occupied) {
            int recovered = fixture.orchestrator.recoverExpiredRuns();

            assertThat(recovered).isZero();
            assertThat(fixture.executions.findByRunId("run1")).get().extracting(ChatRunExecution::executionStatus)
                    .isEqualTo(ChatRunExecutionStatus.RUNNING);
        }
    }

    @Test
    void perTenantClaimLimitPreventsSingleTenantFromOwningWholeScan() {
        TestFixture fixture = fixture();
        fixture.properties.setRecoveryMaxClaimsPerTenantPerScan(1);
        ChatRun tenantOneFirst = runningRun("run1", "tenant1");
        ChatRun tenantOneSecond = runningRun("run2", "tenant1");
        ChatRun tenantTwo = runningRun("run3", "tenant2");
        for (ChatRun run : List.of(tenantOneFirst, tenantOneSecond, tenantTwo)) {
            fixture.runs.save(run);
            fixture.executions.put(staleExecution(run));
        }

        int recovered = fixture.orchestrator.recoverExpiredRuns();

        assertThat(recovered).isEqualTo(2);
        long tenantOneRecovered = List.of("run1", "run2").stream()
                .map(id -> fixture.executions.findByRunId(id).orElseThrow())
                .filter(execution -> execution.executionStatus() == ChatRunExecutionStatus.FAILED)
                .count();
        assertThat(tenantOneRecovered).isEqualTo(1);
        assertThat(fixture.executions.findByRunId("run3")).get().extracting(ChatRunExecution::executionStatus)
                .isEqualTo(ChatRunExecutionStatus.FAILED);
    }

    private ChatRun runningRun() {
        return runningRun("run1", "tenant1");
    }

    private ChatRun runningRun(String runId, String tenantId) {
        Instant now = Instant.now();
        return new ChatRun(runId, tenantId, "user1", "session1", ChatRunStatus.RUNNING,
                "AGENT_RUNTIME", null, "relay", "runtime-session", null, null, null,
                now, null, Map.of(), now, now);
    }

    private ChatRun continuationRun(String runId, String interactionId) {
        Instant now = Instant.now();
        return new ChatRun(runId, "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
                "AGENT_RUNTIME", null, "relay", "runtime-session", null, null, null,
                now, null, Map.of("interactionId", interactionId), now, now);
    }

    private ChatInteractionRequest waitingInteraction(String interactionId) {
        Instant now = Instant.now();
        return new ChatInteractionRequest(interactionId, "tenant1", "user1", "session1", "source-run", null,
                "msg-user", "msg-assistant", "intent-agent", null, null, null,
                ChatInteractionType.INTENT_CLARIFICATION, ChatInteractionStatus.WAITING,
                Map.of("originalQuery", "原问题"), Map.of(), now.plus(Duration.ofHours(1)),
                null, null, now, now);
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private TestFixture fixture() {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryRunCache cache = new InMemoryRunCache();
        FixedSessionRepository sessions = new FixedSessionRepository();
        PermissionChecker permissionChecker = new PermissionChecker();
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), new NoopLiveEventBus(), runs,
                permissionChecker, sessions, new ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, cache, events,
                permissionChecker, sessions);
        ChatRunOperationalProperties properties = new ChatRunOperationalProperties();
        properties.setWatchdogBatchSize(10);
        properties.setWatchdogMaxClaimsPerScan(10);
        properties.setRecoveryMaxConcurrency(1);
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(executions,
                () -> "instance-b", properties, new FixedIdGenerator(), registry);
        InMemoryInteractionRepository interactions = new InMemoryInteractionRepository();
        ChatInteractionApplicationService interactionService = interactionService(interactions);
        ChatRunTerminalCommitService terminalCommitService = terminalCommitService(
                streamService, runs, leaseService, interactionService);
        List<StaleRunRecoveryStrategy> strategies = List.of(
                new ManualConfirmationRecoveryStrategy(streamService, terminalCommitService, runService),
                new FailFastRecoveryStrategy(streamService, terminalCommitService, runService),
                new RuntimeTakeoverRecoveryStrategy(unsupportedRuntimeRecovery())
        );
        ChatRunRecoveryCapacityLimiter capacityLimiter = new ChatRunRecoveryCapacityLimiter(properties);
        ChatRunRecoveryOrchestrator orchestrator = new ChatRunRecoveryOrchestrator(executions, runs,
                new AllowingRecoverLock(), () -> "instance-b", properties, capacityLimiter,
                new StaleRunRecoveryStrategyRegistry(strategies), interactionService,
                terminalCommitService, streamService, runService);
        return new TestFixture(properties, runs, executions, interactions, interactionService,
                capacityLimiter, events, terminalCommitService, orchestrator);
    }

    private record TestFixture(ChatRunOperationalProperties properties,
                               InMemoryRunRepository runs,
                               InMemoryExecutionRepository executions,
                               InMemoryInteractionRepository interactions,
                               ChatInteractionApplicationService interactionService,
                               ChatRunRecoveryCapacityLimiter capacityLimiter,
                               InMemoryEventStore events,
                               ChatRunTerminalCommitService terminalCommitService,
                               ChatRunRecoveryOrchestrator orchestrator) {
    }

    private ChatInteractionApplicationService interactionService(InMemoryInteractionRepository repository) {
        return new ChatInteractionApplicationService(repository, new FixedIdGenerator(), new PermissionChecker(),
                new ChatInteractionProperties());
    }

    private ChatRunTerminalCommitService terminalCommitService(ChatStreamApplicationService streamService,
                                                                ChatRunRepository runs,
                                                                ChatRunLeaseApplicationService leaseService,
                                                                ChatInteractionApplicationService interactionService) {
        SessionApplicationService sessionService = mock(SessionApplicationService.class);
        when(sessionService.requireSessionForInternalUpdate(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    Instant now = Instant.now();
                    return new ChatSession(
                            invocation.getArgument(2), invocation.getArgument(0), invocation.getArgument(1),
                            "title", "ACTIVE", "web", now, now);
                });
        return new ChatRunTerminalCommitService(streamService, sessionService, runs, leaseService, null,
                interactionService, Duration.ofDays(3));
    }

    private ChatRunExecution staleExecution(ChatRun run) {
        Instant now = Instant.now();
        return new ChatRunExecution("exec1", run.id(), run.tenantId(), run.userId(), run.sessionId(),
                ChatRunExecutionStatus.RUNNING, "instance-a", now.minusSeconds(120),
                now.minusSeconds(60), 1L, null, null, 0, null, null, Map.of(), now, now);
    }

    private AgentRuntimeRecoveryPort unsupportedRuntimeRecovery() {
        return new AgentRuntimeRecoveryPort() {
            @Override public boolean supports(AgentRuntimeRecoveryRequest request) { return false; }
            @Override public Flux<ChatEvent> recover(AgentRuntimeRecoveryRequest request) { return Flux.empty(); }
        };
    }

    private static class InMemoryRunRepository implements ChatRunRepository {
        private final Map<String, ChatRun> runs = new HashMap<>();
        private final Set<String> executionInitOrphanIds = new HashSet<>();
        private boolean rejectExternalTerminalClaims;
        @Override public ChatRun save(ChatRun run) { runs.put(run.id(), run); return run; }
        @Override public synchronized boolean tryClaimExternalTerminal(ExternalTerminalClaim claim) {
            if (rejectExternalTerminalClaims) {
                return false;
            }
            ChatRun current = runs.get(claim.runId());
            if (current == null || current.status().terminal()) {
                return false;
            }
            ChatRun claimed = claim.terminalStatus() == ChatRunStatus.CANCELLED
                    ? current.cancelled(0L)
                    : current.failed(0L);
            runs.put(claim.runId(), claimed);
            return true;
        }
        @Override public Optional<ChatRun> findById(String runId) { return Optional.ofNullable(runs.get(runId)); }
        @Override public Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId) {
            return findById(runId).filter(run -> tenantId.equals(run.tenantId())).filter(run -> userId.equals(run.userId()));
        }
        @Override public Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId) {
            return runs.values().stream().filter(run -> tenantId.equals(run.tenantId()))
                    .filter(run -> userId.equals(run.userId())).filter(run -> sessionId.equals(run.sessionId()))
                    .filter(run -> !run.status().terminal()).findFirst();
        }
        @Override public List<ChatRun> findExecutionInitOrphans(Instant orphanBefore, int limit) {
            return executionInitOrphanIds.stream()
                    .map(runs::get)
                    .filter(java.util.Objects::nonNull)
                    .filter(run -> !run.status().terminal())
                    .limit(limit)
                    .toList();
        }
    }

    private static class InMemoryInteractionRepository implements ChatInteractionRequestRepository {
        private final Map<String, ChatInteractionRequest> requests = new HashMap<>();
        private final Set<String> terminalRunIds = new HashSet<>();
        private final Set<String> missingRunIds = new HashSet<>();
        private final Set<String> missingExecutionRunIds = new HashSet<>();

        @Override
        public ChatInteractionRequest insert(ChatInteractionRequest request) {
            requests.put(request.id(), request);
            return request;
        }

        @Override
        public Optional<ChatInteractionRequest> findByOwnerAndId(String tenantId, String userId, String interactionId) {
            return Optional.ofNullable(requests.get(interactionId))
                    .filter(request -> tenantId.equals(request.tenantId()) && userId.equals(request.userId()));
        }

        @Override
        public Optional<ChatInteractionRequest> findWaitingBySession(String tenantId, String userId, String sessionId) {
            return requests.values().stream()
                    .filter(request -> tenantId.equals(request.tenantId()) && userId.equals(request.userId()))
                    .filter(request -> sessionId.equals(request.sessionId()))
                    .filter(ChatInteractionRequest::waiting)
                    .findFirst();
        }

        @Override
        public boolean claimInteractionResponse(ChatInteractionClaimCommand command) {
            ChatInteractionRequest current = requests.get(command.interactionId());
            if (current == null || !current.waiting()) {
                return false;
            }
            requests.put(current.id(), copy(current, command.continueRunId(), ChatInteractionStatus.RESPONDING,
                    command.responsePayload(), null, null));
            return true;
        }

        @Override
        public int markAnswered(String tenantId, String userId, String interactionId, Instant answeredAt) {
            ChatInteractionRequest current = requests.get(interactionId);
            if (!ownedResponding(current, tenantId, userId)) {
                return 0;
            }
            requests.put(current.id(), copy(current, current.continueRunId(), ChatInteractionStatus.ANSWERED,
                    current.responsePayload(), answeredAt, current.cancelledAt()));
            return 1;
        }

        @Override
        public int markWaiting(String tenantId, String userId, String interactionId) {
            ChatInteractionRequest current = requests.get(interactionId);
            if (!ownedResponding(current, tenantId, userId)) {
                return 0;
            }
            requests.put(current.id(), copy(current, null, ChatInteractionStatus.WAITING,
                    current.responsePayload(), current.answeredAt(), current.cancelledAt()));
            return 1;
        }

        @Override
        public int markWaitingForRun(String tenantId, String userId, String interactionId, String continueRunId) {
            ChatInteractionRequest current = requests.get(interactionId);
            if (!ownedResponding(current, tenantId, userId)
                    || !continueRunId.equals(current.continueRunId())) {
                return 0;
            }
            return markWaiting(tenantId, userId, interactionId);
        }

        @Override
        public List<ChatInteractionRequest> findRespondingWithTerminalContinuation(int limit) {
            return requests.values().stream()
                    .filter(request -> request.status() == ChatInteractionStatus.RESPONDING)
                    .filter(request -> terminalRunIds.contains(request.continueRunId()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ContinuationReconcileCandidate> findRespondingReconcileCandidates(Instant orphanBefore, int limit) {
            return requests.values().stream()
                    .filter(request -> request.status() == ChatInteractionStatus.RESPONDING)
                    .map(request -> {
                        ContinuationReconcileState state = terminalRunIds.contains(request.continueRunId())
                                ? ContinuationReconcileState.TERMINAL_RUN
                                : missingRunIds.contains(request.continueRunId())
                                ? ContinuationReconcileState.MISSING_RUN
                                : missingExecutionRunIds.contains(request.continueRunId())
                                ? ContinuationReconcileState.MISSING_EXECUTION
                                : null;
                        return state == null ? null : new ContinuationReconcileCandidate(request, state, orphanBefore);
                    })
                    .filter(java.util.Objects::nonNull)
                    .limit(limit)
                    .toList();
        }

        @Override
        public int markWaitingIfContinuationOrphaned(String tenantId, String userId, String interactionId,
                                                      String continueRunId, Instant orphanBefore) {
            if (!terminalRunIds.contains(continueRunId) && !missingRunIds.contains(continueRunId)) {
                return 0;
            }
            return markWaitingForRun(tenantId, userId, interactionId, continueRunId);
        }

        @Override
        public int cancelOpenBySession(String tenantId, String userId, String sessionId, Instant cancelledAt) {
            return 0;
        }

        @Override
        public int cancelWaitingById(String tenantId, String userId, String interactionId, Instant cancelledAt) {
            return 0;
        }

        @Override
        public int markExpired(String tenantId, String userId, String interactionId) {
            return 0;
        }

        private boolean ownedResponding(ChatInteractionRequest request, String tenantId, String userId) {
            return request != null && tenantId.equals(request.tenantId()) && userId.equals(request.userId())
                    && request.status() == ChatInteractionStatus.RESPONDING;
        }

        private ChatInteractionRequest copy(ChatInteractionRequest current, String continueRunId,
                                            ChatInteractionStatus status, Map<String, Object> responsePayload,
                                            Instant answeredAt, Instant cancelledAt) {
            return new ChatInteractionRequest(current.id(), current.tenantId(), current.userId(), current.sessionId(),
                    current.sourceRunId(), continueRunId, current.userMessageId(), current.assistantMessageId(),
                    current.runtimeProvider(), current.runtimeBindingId(), current.runtimeSessionId(),
                    current.approvalId(), current.interactionType(), status, current.requestPayload(), responsePayload,
                    current.expiresAt(), answeredAt, cancelledAt, current.createdAt(), Instant.now());
        }
    }

    private static class InMemoryExecutionRepository implements ChatRunExecutionRepository {
        private final Map<String, ChatRunExecution> executions = new HashMap<>();
        void put(ChatRunExecution execution) { executions.put(execution.runId(), execution); }
        @Override public ChatRunExecution createForRun(ChatRun run, String executionId, String ownerInstanceId, Duration leaseDuration) { throw new UnsupportedOperationException(); }
        @Override public Optional<ChatRunExecution> findByRunId(String runId) { return Optional.ofNullable(executions.get(runId)); }
        @Override public boolean heartbeat(String runId, String ownerInstanceId, long fencingToken,
                                           Duration leaseDuration) { return false; }
        @Override public boolean markTerminal(String runId, ChatRunExecutionStatus terminalStatus) {
            ChatRunExecution current = executions.get(runId);
            if (current == null) { return false; }
            executions.put(runId, new ChatRunExecution(current.id(), current.runId(), current.tenantId(), current.userId(),
                    current.sessionId(), terminalStatus, current.ownerInstanceId(), current.heartbeatAt(), current.leaseUntil(),
                    current.fencingToken(), current.recoveryStrategy(), current.recoveredByInstanceId(),
                    current.recoveryAttempts(), current.recoveryLeaseUntil(), current.runtimeResumeToken(),
                    current.metadata(), current.createdAt(), Instant.now()));
            return true;
        }
        @Override public List<ChatRunExecution> findLeaseExpired(int limit) {
            Instant now = Instant.now();
            return executions.values().stream().filter(e -> e.executionStatus() == ChatRunExecutionStatus.RUNNING)
                    .filter(e -> e.leaseUntil().isBefore(now)).limit(limit).toList();
        }
        @Override public List<ChatRunExecution> findRecoveryExpired(int limit) { return List.of(); }
        @Override public List<ChatRunExecution> findAsyncWaitingExpired(int limit) {
            Instant now = Instant.now();
            return executions.values().stream()
                    .filter(e -> e.executionStatus() == ChatRunExecutionStatus.ASYNC_WAITING)
                    .filter(e -> e.leaseUntil() != null && e.leaseUntil().isBefore(now))
                    .limit(limit)
                    .toList();
        }
        @Override public Optional<ChatRunExecution> tryClaimRecovering(String runId, String recoveredByInstanceId, String strategy, Duration recoveryLeaseDuration) {
            ChatRunExecution current = executions.get(runId);
            if (current == null || current.executionStatus() != ChatRunExecutionStatus.RUNNING || current.leaseUntil().isAfter(Instant.now())) {
                return Optional.empty();
            }
            ChatRunExecution claimed = new ChatRunExecution(current.id(), current.runId(), current.tenantId(), current.userId(),
                    current.sessionId(), ChatRunExecutionStatus.RECOVERING, current.ownerInstanceId(), current.heartbeatAt(),
                    current.leaseUntil(), current.fencingToken() + 1, strategy, recoveredByInstanceId,
                    current.recoveryAttempts() + 1, Instant.now().plus(recoveryLeaseDuration), current.runtimeResumeToken(),
                    current.metadata(), current.createdAt(), Instant.now());
            executions.put(runId, claimed);
            return Optional.of(claimed);
        }
        @Override public Optional<ChatRunExecution> markTakeoverRunning(String runId, String ownerInstanceId, Duration leaseDuration) { return Optional.empty(); }
        @Override public boolean isLeaseExpired(String runId, Instant now) {
            return findByRunId(runId).filter(e -> e.leaseUntil().isBefore(now)).isPresent();
        }
    }

    private static class InMemoryEventStore implements ChatEventStore {
        private long seq;
        private final List<ChatEvent> events = new ArrayList<>();
        @Override public ChatEvent append(ChatEvent event) {
            ChatEvent stored = new StoredChatEvent(event.runId(), event.sessionId(), ++seq, event.type(), Instant.now(), event.payload());
            events.add(stored);
            return stored;
        }
        @Override public ChatEvent appendWithExecutionGuard(ChatEvent event, com.huawei.it.ex.one.domain.chat.RunExecutionClaim claim) { return append(event); }
        @Override public List<ChatEvent> findByOwnerAndSessionAfterSeq(String tenantId, String userId, String sessionId, long afterSeq) { return List.of(); }
        @Override public List<ChatEvent> findByOwnerAndRunAfterSeq(String tenantId, String userId, String sessionId, String runId, long afterSeq) { return List.of(); }
        @Override public long findLatestSeqByOwnerAndSession(String tenantId, String userId, String sessionId) { return seq; }
    }

    private static class InMemoryRunCache implements ChatRunCache {
        private final Map<String, ChatRun> active = new HashMap<>();
        @Override public Optional<ChatRun> getActive(String tenantId, String userId, String sessionId) { return Optional.ofNullable(active.get(tenantId + userId + sessionId)); }
        @Override public boolean tryClaimActive(ChatRun run) { active.put(run.tenantId() + run.userId() + run.sessionId(), run); return true; }
        @Override public void putActive(ChatRun run) { active.put(run.tenantId() + run.userId() + run.sessionId(), run); }
        @Override public void evictActive(String tenantId, String userId, String sessionId) { active.remove(tenantId + userId + sessionId); }
        @Override public void markCancellationRequested(String runId) {}
        @Override public ChatRunCancelSignal cancellationSignal(String runId) { return ChatRunCancelSignal.NOT_REQUESTED; }
    }

    private static class AllowingRecoverLock implements ChatRunRecoverLock {
        @Override public boolean tryLock(String runId, String ownerInstanceId, Duration ttl) { return true; }
    }

    private static class NoopLiveEventBus implements ChatLiveEventBus {
        @Override public void publish(String topicId, ChatEvent event) {}
        @Override public Flux<ChatEvent> subscribe(String topicId) { return Flux.never(); }
    }

    private static class FixedSessionRepository implements SessionRepository {
        @Override public Optional<ChatSession> findById(String sessionId) { return Optional.empty(); }
        @Override public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
            Instant now = Instant.now();
            return Optional.of(new ChatSession(sessionId, tenantId, userId, "title", "ACTIVE", "web", now, now));
        }
        @Override public List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId) { return List.of(); }
        @Override public ChatSessionPage pageByTenantIdAndUserId(String tenantId, String userId, String cursor, int limit) { return new ChatSessionPage(List.of(), null); }
        @Override public ChatSession save(ChatSession session) { return session; }
    }

    private static class FixedIdGenerator implements IdGenerator {
        @Override public String newId(String bizType, IdGenerateContext context) { return bizType + "_1"; }
    }
}
