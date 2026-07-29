package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecution;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import reactor.core.Disposable;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

class ChatRunLeaseApplicationServiceTest {
    @Test
    void heartbeatsTwoHundredClaimsInFourSortedBatches() {
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        RecordingHeartbeatRepository repository = new RecordingHeartbeatRepository(-1, Set.of());
        ChatRunOperationalProperties properties = new ChatRunOperationalProperties();
        properties.setHeartbeatBatchSize(50);
        for (int index = 199; index >= 0; index--) {
            RunExecutionClaim claim = claim(index);
            registry.register(claim.runId(), disposable(new AtomicBoolean()), claim);
        }
        ChatRunLeaseApplicationService service = service(repository, registry, properties);

        service.heartbeatActiveRuns();

        assertThat(repository.batches).hasSize(4);
        assertThat(repository.batches).allSatisfy(batch -> assertThat(batch).hasSize(50));
        assertThat(repository.batches.stream().flatMap(List::stream).map(RunExecutionClaim::runId))
                .isSorted();
    }

    @Test
    void failedHeartbeatBatchDoesNotCancelClaimsAndLaterBatchesStillRun() {
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        RecordingHeartbeatRepository repository = new RecordingHeartbeatRepository(0, Set.of());
        ChatRunOperationalProperties properties = new ChatRunOperationalProperties();
        properties.setHeartbeatBatchSize(50);
        List<AtomicBoolean> disposed = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            RunExecutionClaim claim = claim(index);
            AtomicBoolean state = new AtomicBoolean();
            disposed.add(state);
            registry.register(claim.runId(), disposable(state), claim);
        }
        ChatRunLeaseApplicationService service = service(repository, registry, properties);

        service.heartbeatActiveRuns();

        assertThat(repository.batches).hasSize(3);
        assertThat(disposed).allSatisfy(state -> assertThat(state).isFalse());
        assertThat(registry.activeClaims()).hasSize(120);
    }

    @Test
    void partiallyRejectedBatchCancelsOnlyRejectedClaims() {
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        RunExecutionClaim rejected = claim(2);
        RecordingHeartbeatRepository repository = new RecordingHeartbeatRepository(-1, Set.of(rejected));
        List<AtomicBoolean> disposed = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            RunExecutionClaim claim = claim(index);
            AtomicBoolean state = new AtomicBoolean();
            disposed.add(state);
            registry.register(claim.runId(), disposable(state), claim);
        }
        ChatRunLeaseApplicationService service = service(
                repository, registry, new ChatRunOperationalProperties());

        service.heartbeatActiveRuns();

        assertThat(disposed.get(0)).isFalse();
        assertThat(disposed.get(1)).isFalse();
        assertThat(disposed.get(2)).isTrue();
        assertThat(disposed.get(3)).isFalse();
        assertThat(registry.activeClaims()).doesNotContain(rejected).hasSize(3);
    }

    @Test
    void rejectedHeartbeatCancelsMatchingLocalExecution() {
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        RunExecutionClaim claim = new RunExecutionClaim("run-1", "instance-a", 7L);
        AtomicBoolean disposed = new AtomicBoolean();
        registry.register("run-1", disposable(disposed), claim);
        ChatRunLeaseApplicationService service = service(new HeartbeatRepository(false, false), registry);

        service.heartbeatActiveRuns();

        assertThat(disposed).isTrue();
        assertThat(registry.activeClaims()).isEmpty();
    }

    @Test
    void heartbeatExceptionDoesNotCancelLocalExecution() {
        LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
        RunExecutionClaim claim = new RunExecutionClaim("run-1", "instance-a", 7L);
        AtomicBoolean disposed = new AtomicBoolean();
        registry.register("run-1", disposable(disposed), claim);
        ChatRunLeaseApplicationService service = service(new HeartbeatRepository(false, true), registry);

        service.heartbeatActiveRuns();

        assertThat(disposed).isFalse();
        assertThat(registry.activeClaims()).containsExactly(claim);
    }

    private ChatRunLeaseApplicationService service(ChatRunExecutionRepository repository,
                                                   LocalChatRunExecutionRegistry registry) {
        return service(repository, registry, new ChatRunOperationalProperties());
    }

    private ChatRunLeaseApplicationService service(ChatRunExecutionRepository repository,
                                                   LocalChatRunExecutionRegistry registry,
                                                   ChatRunOperationalProperties properties) {
        ApplicationInstanceIdProvider instanceIdProvider = () -> "instance-a";
        IdGenerator idGenerator = (prefix, context) -> prefix + "-1";
        return new ChatRunLeaseApplicationService(
                repository,
                instanceIdProvider,
                properties,
                idGenerator,
                registry);
    }

    private RunExecutionClaim claim(int index) {
        return new RunExecutionClaim("run-%03d".formatted(index), "instance-a", index + 1L);
    }

    private Disposable disposable(AtomicBoolean disposed) {
        return () -> disposed.set(true);
    }

    private static class HeartbeatRepository implements ChatRunExecutionRepository {
        private final boolean result;
        private final boolean failure;

        private HeartbeatRepository(boolean result, boolean failure) {
            this.result = result;
            this.failure = failure;
        }

        @Override
        public ChatRunExecution createForRun(ChatRun run, String executionId, String ownerInstanceId,
                                             Duration leaseDuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ChatRunExecution> findByRunId(String runId) {
            return Optional.empty();
        }

        @Override
        public boolean heartbeat(String runId, String ownerInstanceId, long fencingToken,
                                 Duration leaseDuration) {
            if (failure) {
                throw new IllegalStateException("database unavailable");
            }
            return result;
        }

        @Override
        public boolean markTerminal(String runId, ChatRunExecutionStatus terminalStatus) {
            return false;
        }

        @Override
        public List<ChatRunExecution> findLeaseExpired(int limit) {
            return List.of();
        }

        @Override
        public List<ChatRunExecution> findRecoveryExpired(int limit) {
            return List.of();
        }

        @Override
        public Optional<ChatRunExecution> tryClaimRecovering(String runId, String recoveredByInstanceId,
                                                             String strategy, Duration recoveryLeaseDuration) {
            return Optional.empty();
        }

        @Override
        public Optional<ChatRunExecution> markTakeoverRunning(String runId, String ownerInstanceId,
                                                              Duration leaseDuration) {
            return Optional.empty();
        }

        @Override
        public boolean isLeaseExpired(String runId, Instant now) {
            return false;
        }
    }

    private static final class RecordingHeartbeatRepository extends HeartbeatRepository {
        private final int failingBatch;
        private final Set<RunExecutionClaim> rejected;
        private final List<List<RunExecutionClaim>> batches = new ArrayList<>();

        private RecordingHeartbeatRepository(int failingBatch, Set<RunExecutionClaim> rejected) {
            super(true, false);
            this.failingBatch = failingBatch;
            this.rejected = new HashSet<>(rejected);
        }

        @Override
        public List<RunExecutionClaim> heartbeatBatch(List<RunExecutionClaim> claims, Duration leaseDuration) {
            int batchIndex = batches.size();
            batches.add(List.copyOf(claims));
            if (batchIndex == failingBatch) {
                throw new IllegalStateException("database timeout");
            }
            return claims.stream().filter(claim -> !rejected.contains(claim)).toList();
        }
    }
}
