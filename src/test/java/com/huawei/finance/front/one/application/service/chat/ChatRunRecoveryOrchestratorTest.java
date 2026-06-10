package com.huawei.finance.front.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.config.ChatReadCursorProperties;
import com.huawei.finance.front.one.application.config.ChatRunOperationalProperties;
import com.huawei.finance.front.one.application.config.ChatWebSocketProperties;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRecoveryPort;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRecoveryRequest;
import com.huawei.finance.front.one.application.integration.conversation.ChatEventStore;
import com.huawei.finance.front.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.finance.front.one.application.integration.conversation.ChatReadCursorCache;
import com.huawei.finance.front.one.application.integration.conversation.ChatReadCursorRepository;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunCache;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunRecoverLock;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.finance.front.one.application.service.recovery.FailFastRecoveryStrategy;
import com.huawei.finance.front.one.application.service.recovery.ManualConfirmationRecoveryStrategy;
import com.huawei.finance.front.one.application.service.recovery.RuntimeTakeoverRecoveryStrategy;
import com.huawei.finance.front.one.application.service.recovery.StaleRunRecoveryStrategy;
import com.huawei.finance.front.one.application.service.recovery.StaleRunRecoveryStrategyRegistry;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatReadCursor;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunCancelSignal;
import com.huawei.finance.front.one.domain.chat.ChatRunExecution;
import com.huawei.finance.front.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.finance.front.one.domain.chat.ChatRunStatus;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import com.huawei.finance.front.one.domain.chat.StoredChatEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
class ChatRunRecoveryOrchestratorTest {
    @Test
    void manualConfirmationClosesStaleRunAndWritesSingleFailedEvent() {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryRunCache cache = new InMemoryRunCache();
        FixedSessionRepository sessions = new FixedSessionRepository();
        PermissionChecker permissionChecker = new PermissionChecker();
        ChatReadCursorApplicationService readCursorService = new ChatReadCursorApplicationService(
                new EmptyReadCursorRepository(), new EmptyReadCursorCache(), permissionChecker, sessions,
                new ChatReadCursorProperties());
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), new NoopLiveEventBus(), runs, readCursorService,
                permissionChecker, sessions, new ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, cache, events,
                readCursorService, permissionChecker, sessions);
        ChatRunOperationalProperties properties = new ChatRunOperationalProperties();
        properties.setWatchdogBatchSize(10);
        properties.setWatchdogMaxClaimsPerScan(10);
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(executions,
                () -> "instance-b", properties, new FixedIdGenerator(), registry);
        List<StaleRunRecoveryStrategy> strategies = List.of(
                new ManualConfirmationRecoveryStrategy(streamService, runService, leaseService),
                new FailFastRecoveryStrategy(streamService, runService, leaseService),
                new RuntimeTakeoverRecoveryStrategy(unsupportedRuntimeRecovery())
        );
        ChatRunRecoveryOrchestrator orchestrator = new ChatRunRecoveryOrchestrator(executions, runs,
                new AllowingRecoverLock(), () -> "instance-b", properties, new ChatRunRecoveryCapacityLimiter(properties),
                new StaleRunRecoveryStrategyRegistry(strategies));
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

    private TestFixture fixture() {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryRunCache cache = new InMemoryRunCache();
        FixedSessionRepository sessions = new FixedSessionRepository();
        PermissionChecker permissionChecker = new PermissionChecker();
        ChatReadCursorApplicationService readCursorService = new ChatReadCursorApplicationService(
                new EmptyReadCursorRepository(), new EmptyReadCursorCache(), permissionChecker, sessions,
                new ChatReadCursorProperties());
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), new NoopLiveEventBus(), runs, readCursorService,
                permissionChecker, sessions, new ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, cache, events,
                readCursorService, permissionChecker, sessions);
        ChatRunOperationalProperties properties = new ChatRunOperationalProperties();
        properties.setWatchdogBatchSize(10);
        properties.setWatchdogMaxClaimsPerScan(10);
        properties.setRecoveryMaxConcurrency(1);
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(executions,
                () -> "instance-b", properties, new FixedIdGenerator(), registry);
        List<StaleRunRecoveryStrategy> strategies = List.of(
                new ManualConfirmationRecoveryStrategy(streamService, runService, leaseService),
                new FailFastRecoveryStrategy(streamService, runService, leaseService),
                new RuntimeTakeoverRecoveryStrategy(unsupportedRuntimeRecovery())
        );
        ChatRunRecoveryCapacityLimiter capacityLimiter = new ChatRunRecoveryCapacityLimiter(properties);
        ChatRunRecoveryOrchestrator orchestrator = new ChatRunRecoveryOrchestrator(executions, runs,
                new AllowingRecoverLock(), () -> "instance-b", properties, capacityLimiter,
                new StaleRunRecoveryStrategyRegistry(strategies));
        return new TestFixture(properties, runs, executions, capacityLimiter, orchestrator);
    }

    private record TestFixture(ChatRunOperationalProperties properties,
                               InMemoryRunRepository runs,
                               InMemoryExecutionRepository executions,
                               ChatRunRecoveryCapacityLimiter capacityLimiter,
                               ChatRunRecoveryOrchestrator orchestrator) {
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
        @Override public ChatRun save(ChatRun run) { runs.put(run.id(), run); return run; }
        @Override public Optional<ChatRun> findById(String runId) { return Optional.ofNullable(runs.get(runId)); }
        @Override public Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId) {
            return findById(runId).filter(run -> tenantId.equals(run.tenantId())).filter(run -> userId.equals(run.userId()));
        }
        @Override public Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId) {
            return runs.values().stream().filter(run -> tenantId.equals(run.tenantId()))
                    .filter(run -> userId.equals(run.userId())).filter(run -> sessionId.equals(run.sessionId()))
                    .filter(run -> !run.status().terminal()).findFirst();
        }
    }

    private static class InMemoryExecutionRepository implements ChatRunExecutionRepository {
        private final Map<String, ChatRunExecution> executions = new HashMap<>();
        void put(ChatRunExecution execution) { executions.put(execution.runId(), execution); }
        @Override public ChatRunExecution createForRun(ChatRun run, String executionId, String ownerInstanceId, Duration leaseDuration) { throw new UnsupportedOperationException(); }
        @Override public Optional<ChatRunExecution> findByRunId(String runId) { return Optional.ofNullable(executions.get(runId)); }
        @Override public boolean heartbeat(String runId, String ownerInstanceId, Duration leaseDuration) { return false; }
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
        @Override public ChatEvent appendWithExecutionGuard(ChatEvent event, com.huawei.finance.front.one.domain.chat.RunExecutionClaim claim) { return append(event); }
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

    private static class EmptyReadCursorRepository implements ChatReadCursorRepository {
        @Override public Optional<ChatReadCursor> find(String tenantId, String userId, String sessionId) { return Optional.empty(); }
        @Override public ChatReadCursor upsert(String tenantId, String userId, String sessionId, long lastConsumedSeq) {
            return new ChatReadCursor("cursor1", tenantId, userId, sessionId, lastConsumedSeq, Instant.now());
        }
    }

    private static class EmptyReadCursorCache implements ChatReadCursorCache {
        @Override public Optional<ChatReadCursor> find(String tenantId, String userId, String sessionId) { return Optional.empty(); }
        @Override public void put(ChatReadCursor cursor) {}
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
