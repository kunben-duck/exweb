package com.huawei.it.ex.one.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.chat.application.repository.ChatEventStore;
import com.huawei.it.ex.one.chat.application.repository.ChatRunCache;
import com.huawei.it.ex.one.chat.application.repository.ChatRunRepository;
import com.huawei.it.ex.one.chat.application.repository.SessionRepository;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatRunCancelSignal;
import com.huawei.it.ex.one.chat.domain.ChatRunStatus;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.domain.ChatSessionPage;
import com.huawei.it.ex.one.common.event.MessageDeltaEvent;
import com.huawei.it.ex.one.chat.domain.RunCancelledEvent;
import com.huawei.it.ex.one.chat.domain.RunCompletedEvent;
import com.huawei.it.ex.one.common.event.RuntimeEvent;
import com.huawei.it.ex.one.chat.domain.StoredChatEvent;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
class ChatRunApplicationServiceTest {
    @Test
    void stopRunningRunMarksCancelAndCancelledEventClosesRun() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = service(repository, cache);
        ChatRun run = runningRun();
        repository.save(run);
        cache.putActive(run);

        var decision = service.requestStop(user(), "run1", "USER_STOP");

        assertThat(decision.appendCancelledEvent()).isTrue();
        assertThat(repository.saved.status()).isEqualTo(ChatRunStatus.CANCELLING);
        assertThat(cache.cancellationSignal("run1")).isEqualTo(ChatRunCancelSignal.REQUESTED);

        service.observeEvent(new StoredChatEvent("run1", "session1", 8L, "run.cancelled",
                Instant.now(), RunCancelledEvent.of("run1", "session1", "USER_STOP").payload()));

        assertThat(repository.saved.status()).isEqualTo(ChatRunStatus.CANCELLED);
        assertThat(repository.saved.lastSeq()).isEqualTo(8L);
        assertThat(cache.getActive("tenant1", "user1", "session1")).isEmpty();
    }

    @Test
    void stopCompletedRunIsIdempotent() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRun completed = runningRun().completed(9L);
        repository.save(completed);

        var decision = service(repository, cache).requestStop(user(), "run1", "USER_STOP");

        assertThat(decision.appendCancelledEvent()).isFalse();
        assertThat(decision.run().status()).isEqualTo(ChatRunStatus.COMPLETED);
        assertThat(cache.cancellationSignal("run1")).isEqualTo(ChatRunCancelSignal.NOT_REQUESTED);
    }

    @Test
    void stopCancellingRunRetriesTerminalSubmissionWithoutRevertingStatus() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRun cancelling = runningRun().cancelling("USER_STOP");
        repository.save(cancelling);

        var decision = service(repository, cache).requestStop(user(), "run1", "USER_STOP");

        assertThat(decision.appendCancelledEvent()).isTrue();
        assertThat(decision.run().status()).isEqualTo(ChatRunStatus.CANCELLING);
        assertThat(cache.cancellationSignal("run1")).isEqualTo(ChatRunCancelSignal.REQUESTED);
    }

    @Test
    void stopDatabaseFailureDoesNotWriteRedisCancellationFlag() {
        FailingStopClaimRunRepository repository = new FailingStopClaimRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = service(repository, cache);
        repository.save(runningRun());

        assertThatThrownBy(() -> service.requestStop(user(), "run1", "USER_STOP"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stop claim db failure");

        assertThat(repository.findById("run1").orElseThrow().status()).isEqualTo(ChatRunStatus.RUNNING);
        assertThat(cache.cancellationSignal("run1")).isEqualTo(ChatRunCancelSignal.NOT_REQUESTED);
        assertThat(service.shouldAcceptEvent(MessageDeltaEvent.of("run1", "session1", "still running"))).isTrue();
    }

    @Test
    void stopDoesNotAppendCancelledEventWhenTerminalRaceWins() {
        TerminalRaceRunRepository repository = new TerminalRaceRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        repository.save(runningRun());

        var decision = service(repository, cache).requestStop(user(), "run1", "USER_STOP");

        assertThat(decision.appendCancelledEvent()).isFalse();
        assertThat(decision.run().status()).isEqualTo(ChatRunStatus.COMPLETED);
    }

    @Test
    void shouldUseDatabaseOnlyForTerminalAndCancelledEventAdmission() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = service(repository, cache);
        repository.save(runningRun().cancelling("USER_STOP"));

        // 非终态事件不再预查 run 表；最终拒绝由 guarded insert 的 r.status='RUNNING' 条件负责。
        assertThat(service.shouldAcceptEvent(MessageDeltaEvent.of("run1", "session1", "late delta"))).isTrue();
        assertThat(service.shouldAcceptEvent(RunCompletedEvent.of("run1", "session1"))).isFalse();
        assertThat(service.shouldAcceptEvent(RunCancelledEvent.of("run1", "session1", "USER_STOP"))).isTrue();
    }

    @Test
    void observeEventDoesNotUpdateRunForMessageDeltaOrMessageCompleted() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = service(repository, cache);
        repository.save(runningRun());

        service.observeEvent(MessageDeltaEvent.of("run1", "session1", "first delta"));
        service.observeEvent(new StoredChatEvent("run1", "session1", 4L, "message.completed", Instant.now(), Map.of()));

        assertThat(repository.saved.status()).isEqualTo(ChatRunStatus.RUNNING);
        assertThat(repository.saved.lastSeq()).isNull();
    }

    @Test
    void observeRuntimeMetadataUpdatesRuntimeSessionIdForCrossInstanceCancel() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = service(repository, cache);
        repository.save(runningRun().withRuntimeSessionId("generated-session"));

        service.observeEvent(RuntimeEvent.metadata("run1", "session1", Map.of(
                "metadataType", "relay_session_ready",
                "runtimeSessionId", "relay-session-actual")));

        assertThat(repository.saved.runtimeSessionId()).isEqualTo("relay-session-actual");
    }

    @Test
    void shouldRejectEventsImmediatelyWhenRedisCancelFlagExists() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = service(repository, cache);
        repository.save(runningRun());
        cache.markCancellationRequested("run1");

        assertThat(service.shouldAcceptEvent(MessageDeltaEvent.of("run1", "session1", "late delta"))).isFalse();
    }

    @Test
    void streamStatusUsesActiveRunAndLatestSeq() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        InMemoryEventStore eventStore = new InMemoryEventStore(11L);
        ChatRun run = runningRun().withFirstSeq(1L).withLastSeq(3L);
        repository.save(run);
        cache.putActive(run);
        ChatRunApplicationService service = new ChatRunApplicationService(repository, cache, eventStore,
                new PermissionChecker(), new FixedSessionRepository());

        var status = service.streamStatus(user(), "session1");

        assertThat(status.latestSeq()).isEqualTo(11L);
        assertThat(status.activeRunId()).isEqualTo("run1");
        assertThat(status.activeRunStatus()).isEqualTo(ChatRunStatus.RUNNING);
        assertThat(status.activeStreamTopicId()).isEqualTo("chat-run-run1");
        assertThat(status.activeRunFirstSeq()).isEqualTo(1L);
        assertThat(status.activeRunLastSeq()).isEqualTo(3L);
        assertThat(status.cancellable()).isTrue();
    }

    @Test
    void findOwnedRunsByIdsReturnsOnlyCurrentUsersRuns() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        ChatRunApplicationService service = service(repository, new InMemoryRunCache());
        repository.save(run("run1", "user1", "relay"));
        repository.save(run("run2", "user1", "domain-agent"));
        repository.save(run("run3", "other", "relay"));

        Map<String, ChatRun> runs = service.findOwnedRunsByIds(user(),
                java.util.Arrays.asList("run1", "run2", "run3", "run1", "", null));

        assertThat(runs).containsOnlyKeys("run1", "run2");
        assertThat(runs.get("run1").runtimeProvider()).isEqualTo("relay");
        assertThat(runs.get("run2").runtimeProvider()).isEqualTo("domain-agent");
    }

    @Test
    void bindRuntimeProviderRecordsIntentAgentForClarificationRun() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        ChatRunApplicationService service = service(repository, new InMemoryRunCache());
        repository.save(runningRun().withResolvedRoute("SYSTEM_RESPONSE", null, null, null));

        ChatRun updated = service.bindRuntimeProvider("run1", "intent-agent");

        assertThat(updated.runtimeProvider()).isEqualTo("intent-agent");
        assertThat(repository.findById("run1")).get()
                .extracting(ChatRun::runtimeProvider)
                .isEqualTo("intent-agent");
    }

    @Test
    void createRunningRejectsWhenSessionAlreadyHasActiveRun() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRun active = runningRun();
        repository.save(active);
        cache.putActive(active);
        ChatRunApplicationService service = service(repository, cache);

        assertThatThrownBy(() -> service.createRunning(new CreateChatRunContext(
                "run2",
                user(),
                "session1",
                com.huawei.it.ex.one.intent.application.model.RouteTarget.agentRuntime("test", 1.0, "test"),
                null,
                Map.of(),
                com.huawei.it.ex.one.chat.domain.ChatRunMode.NEXT,
                null,
                null
        ))).isInstanceOf(com.huawei.it.ex.one.chat.domain.ActiveRunExistsException.class)
                .hasMessageContaining("ACTIVE_RUN_EXISTS");
    }

    @Test
    void createRunningUsesDatabaseInsertInsteadOfRedisClaim() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache() {
            @Override
            public boolean tryClaimActive(ChatRun run) {
                return false;
            }
        };

        ChatRun created = service(repository, cache).createRunning(new CreateChatRunContext(
                "run2",
                user(),
                "session1",
                com.huawei.it.ex.one.intent.application.model.RouteTarget.agentRuntime("test", 1.0, "test"),
                null,
                Map.of(),
                com.huawei.it.ex.one.chat.domain.ChatRunMode.NEXT,
                null,
                null
        ));

        assertThat(created.id()).isEqualTo("run2");
        assertThat(repository.findById("run2")).contains(created);
        assertThat(cache.getActive("tenant1", "user1", "session1")).contains(created);
    }

    @Test
    void createRunningRejectsDeletedSessionBeforeClaimingActiveRun() {
        InMemoryRunRepository repository = new InMemoryRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = new ChatRunApplicationService(repository, cache, new InMemoryEventStore(0L),
                new PermissionChecker(), new StatusSessionRepository("DELETED"));

        assertThatThrownBy(() -> service.createRunning(new CreateChatRunContext(
                "run2",
                user(),
                "session1",
                com.huawei.it.ex.one.intent.application.model.RouteTarget.agentRuntime("test", 1.0, "test"),
                null,
                Map.of(),
                com.huawei.it.ex.one.chat.domain.ChatRunMode.NEXT,
                null,
                null
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话不存在");
        assertThat(cache.getActive("tenant1", "user1", "session1")).isEmpty();
    }

    @Test
    void interactionContinuationDoesNotCreateRunAfterClaimWasReconciled() {
        ClaimLostRunRepository repository = new ClaimLostRunRepository();
        InMemoryRunCache cache = new InMemoryRunCache();
        ChatRunApplicationService service = service(repository, cache);

        assertThatThrownBy(() -> service.createInteractionRunning(new CreateChatRunContext(
                "run2",
                user(),
                "session1",
                com.huawei.it.ex.one.intent.application.model.RouteTarget.agentRuntime("test", 1.0, "test"),
                null,
                Map.of("interactionId", "interaction1"),
                com.huawei.it.ex.one.chat.domain.ChatRunMode.NEXT,
                "msg-user",
                "msg-user"
        ), "interaction1"))
                .isInstanceOf(com.huawei.it.ex.one.chat.domain.ChatInteractionUnavailableException.class);

        assertThat(repository.findById("run2")).isEmpty();
        assertThat(cache.getActive("tenant1", "user1", "session1")).isEmpty();
    }

    private ChatRunApplicationService service(InMemoryRunRepository repository, InMemoryRunCache cache) {
        return new ChatRunApplicationService(repository, cache, new InMemoryEventStore(0L),
                new PermissionChecker(), new FixedSessionRepository());
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private ChatRun runningRun() {
        Instant now = Instant.now();
        return new ChatRun("run1", "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
                "AGENT_RUNTIME", null, "relay", null, null, null, null,
                now, null, Map.of(), now, now);
    }

    private ChatRun run(String runId, String userId, String runtimeProvider) {
        Instant now = Instant.now();
        return new ChatRun(runId, "tenant1", userId, "session1", ChatRunStatus.COMPLETED,
                "AGENT_RUNTIME", null, runtimeProvider, null, null, null, null,
                now, now, Map.of(), now, now);
    }

    private static class InMemoryRunRepository implements ChatRunRepository {
        private final Map<String, ChatRun> runs = new HashMap<>();
        private ChatRun saved;

        @Override
        public ChatRun save(ChatRun run) {
            saved = run;
            runs.put(run.id(), run);
            return run;
        }

        @Override
        public Optional<ChatRun> findById(String runId) {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId) {
            return findById(runId)
                    .filter(run -> tenantId.equals(run.tenantId()))
                    .filter(run -> userId.equals(run.userId()));
        }

        @Override
        public Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId) {
            return runs.values().stream()
                    .filter(run -> tenantId.equals(run.tenantId()))
                    .filter(run -> userId.equals(run.userId()))
                    .filter(run -> sessionId.equals(run.sessionId()))
                    .filter(run -> !run.status().terminal())
                    .findFirst();
        }
    }

    private static class TerminalRaceRunRepository extends InMemoryRunRepository {
        @Override
        public ChatRun save(ChatRun run) {
            if (run.status() == ChatRunStatus.CANCELLING) {
                return super.save(run.completed(9L));
            }
            return super.save(run);
        }
    }

    private static class FailingStopClaimRunRepository extends InMemoryRunRepository {
        @Override
        public boolean tryMarkCancelling(StopClaim claim) {
            throw new IllegalStateException("stop claim db failure");
        }
    }

    private static class ClaimLostRunRepository extends InMemoryRunRepository {
        @Override
        public Optional<ChatRun> insertInteractionContinuationIfClaimed(ChatRun run, String interactionId) {
            return Optional.empty();
        }
    }

    private static class InMemoryRunCache implements ChatRunCache {
        private final Map<String, ChatRun> active = new HashMap<>();
        private final Map<String, Boolean> cancelled = new HashMap<>();

        @Override
        public Optional<ChatRun> getActive(String tenantId, String userId, String sessionId) {
            return Optional.ofNullable(active.get(tenantId + ":" + userId + ":" + sessionId));
        }

        @Override
        public boolean tryClaimActive(ChatRun run) {
            String key = run.tenantId() + ":" + run.userId() + ":" + run.sessionId();
            if (active.containsKey(key)) {
                return false;
            }
            active.put(key, run);
            return true;
        }

        @Override
        public void putActive(ChatRun run) {
            active.put(run.tenantId() + ":" + run.userId() + ":" + run.sessionId(), run);
        }

        @Override
        public void evictActive(String tenantId, String userId, String sessionId) {
            active.remove(tenantId + ":" + userId + ":" + sessionId);
        }

        @Override
        public void markCancellationRequested(String runId) {
            cancelled.put(runId, true);
        }

        @Override
        public ChatRunCancelSignal cancellationSignal(String runId) {
            return Boolean.TRUE.equals(cancelled.get(runId))
                    ? ChatRunCancelSignal.REQUESTED
                    : ChatRunCancelSignal.NOT_REQUESTED;
        }
    }

    private static class InMemoryEventStore implements ChatEventStore {
        private final long latestSeq;

        private InMemoryEventStore(long latestSeq) {
            this.latestSeq = latestSeq;
        }

        @Override
        public ChatEvent append(ChatEvent event) {
            return event;
        }

        @Override
        public ChatEvent appendWithExecutionGuard(ChatEvent event, com.huawei.it.ex.one.chat.domain.RunExecutionClaim claim) {
            return append(event);
        }

        @Override
        public List<ChatEvent> findByOwnerAndSessionAfterSeq(String tenantId, String userId, String sessionId, long afterSeq) {
            return List.of();
        }

        @Override
        public List<ChatEvent> findByOwnerAndRunAfterSeq(String tenantId, String userId, String sessionId, String runId, long afterSeq) {
            return List.of();
        }

        @Override
        public long findLatestSeqByOwnerAndSession(String tenantId, String userId, String sessionId) {
            return latestSeq;
        }
    }

    private static class FixedSessionRepository implements SessionRepository {
        @Override
        public Optional<ChatSession> findById(String sessionId) {
            return Optional.empty();
        }

        @Override
        public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
            Instant now = Instant.now();
            return Optional.of(new ChatSession(sessionId, tenantId, userId, "title", "ACTIVE", "web", now, now));
        }

        @Override
        public List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId) {
            return List.of();
        }

        @Override
        public ChatSessionPage pageByTenantIdAndUserId(String tenantId, String userId, String cursor, int limit) {
            return new ChatSessionPage(List.of(), null);
        }

        @Override
        public ChatSession save(ChatSession session) {
            return session;
        }
    }

    private static final class StatusSessionRepository extends FixedSessionRepository {
        private final String status;

        private StatusSessionRepository(String status) {
            this.status = status;
        }

        @Override
        public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
            Instant now = Instant.now();
            return Optional.of(new ChatSession(sessionId, tenantId, userId, "title", status, "web", now, now));
        }
    }
}
