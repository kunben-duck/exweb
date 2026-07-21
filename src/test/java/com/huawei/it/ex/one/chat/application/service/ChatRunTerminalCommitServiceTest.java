package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.model.AssistantAssembly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.chat.application.model.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.chat.application.repository.ChatEventStore;
import com.huawei.it.ex.one.chat.application.repository.ChatRunRepository;
import com.huawei.it.ex.one.chat.application.repository.SessionRepository;
import com.huawei.it.ex.one.runtime.application.repository.RuntimeBindingRepository;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatRunMode;
import com.huawei.it.ex.one.chat.domain.ChatRunStatus;
import com.huawei.it.ex.one.chat.domain.ChatSessionPage;
import com.huawei.it.ex.one.chat.domain.ErrorEvent;
import com.huawei.it.ex.one.chat.domain.RunCancelledEvent;
import com.huawei.it.ex.one.chat.domain.RunCompletedEvent;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import com.huawei.it.ex.one.chat.domain.RunWaitingUserEvent;
import com.huawei.it.ex.one.common.event.RuntimeEvent;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBindingStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class ChatRunTerminalCommitServiceTest {
    @Test
    void domainAgentRefusalCommitHasBoundedTransactionTimeout() throws Exception {
        Transactional transactional = ChatRunTerminalCommitService.class
                .getMethod("commitDomainAgentRefusal",
                        ChatRunTerminalCommitService.DomainAgentRefusalCommitCommand.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString())
                .isEqualTo("${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}");
    }

    @Test
    void domainAgentRefusalCommitAppendsEventBeforeCancellingBinding() {
        List<String> operations = new ArrayList<>();
        RecordingEventStore eventStore = new RecordingEventStore(operations);
        RecordingRuntimeBindingRepository bindingRepository = new RecordingRuntimeBindingRepository(operations);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(
                eventStore, null, null, null, null, null, null);
        ChatRunTerminalCommitService service = ChatTerminalTestFixture.service(
                streamService, null, null, null, bindingRepository, null, Duration.ZERO);
        Instant now = Instant.now();
        RuntimeBinding binding = new RuntimeBinding(
                "binding1", "tenant1", "user1", "session1", "domain-agent", "msg-user",
                "session1", RuntimeBindingStatus.ACTIVE, "run1", null, now, now,
                Map.of("domainAgentId", "agent-a", "routeSource", "intent-agent"));
        RuntimeEvent refusal = RuntimeEvent.metadata("run1", "session1", Map.of(
                "sourceType", "agent.refusal",
                "code", "FN-EX-CAHT-BIZ-DAG-001"));

        ChatRunTerminalCommitService.CommitResult result = service.commitDomainAgentRefusal(
                new ChatRunTerminalCommitService.DomainAgentRefusalCommitCommand(
                        refusal, new RunExecutionClaim("run1", "instance-test", 1L), binding,
                        "FN-EX-CAHT-BIZ-DAG-001"));

        assertThat(operations).containsExactly("event", "binding");
        assertThat(result.event().sequence()).isEqualTo(1L);
        assertThat(result.binding().status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
        assertThat(result.binding().metadata())
                .containsEntry("lastRejectCode", "FN-EX-CAHT-BIZ-DAG-001");
    }

    @Test
    void domainAgentRefusalGuardRejectionDoesNotUpdateBinding() {
        List<String> operations = new ArrayList<>();
        RecordingRuntimeBindingRepository bindingRepository = new RecordingRuntimeBindingRepository(operations);
        ChatEventStore rejectingStore = new RecordingEventStore(operations) {
            @Override
            public ChatEvent appendWithExecutionGuard(ChatEvent event, RunExecutionClaim claim) {
                operations.add("event-rejected");
                throw new ChatEventAppendRejectedException("fencing rejected refusal");
            }
        };
        ChatRunTerminalCommitService service = ChatTerminalTestFixture.service(
                new ChatStreamApplicationService(rejectingStore, null, null, null, null, null, null),
                null, null, null, bindingRepository, null, Duration.ZERO);
        Instant now = Instant.now();
        RuntimeBinding binding = new RuntimeBinding(
                "binding1", "tenant1", "user1", "session1", "domain-agent", "msg-user",
                "session1", RuntimeBindingStatus.ACTIVE, "run1", null, now, now,
                Map.of("domainAgentId", "agent-a", "routeSource", "intent-agent"));

        assertThatThrownBy(() -> service.commitDomainAgentRefusal(
                new ChatRunTerminalCommitService.DomainAgentRefusalCommitCommand(
                        RuntimeEvent.metadata("run1", "session1", Map.of(
                                "sourceType", "agent.refusal",
                                "code", "FN-EX-CAHT-BIZ-DAG-001")),
                        new RunExecutionClaim("run1", "instance-test", 1L), binding,
                        "FN-EX-CAHT-BIZ-DAG-001")))
                .isInstanceOf(ChatEventAppendRejectedException.class);

        assertThat(operations).containsExactly("event-rejected");
    }

    @Test
    void externalTerminalCommitHasBoundedTransactionTimeout() throws Exception {
        Transactional transactional = ChatRunTerminalCommitService.class
                .getMethod("commitExternalTerminal",
                        ChatRunTerminalCommitService.ExternalTerminalCommitCommand.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString())
                .isEqualTo("${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}");
    }

    @Test
    void ownerTerminalFenceRejectsEveryTerminalBeforeAnySideEffect() {
        List<String> operations = new ArrayList<>();
        RejectingRunRepository runRepository = new RejectingRunRepository(operations);
        ChatRunTerminalCommitService service = ChatTerminalTestFixture.service(
                null,
                recordingSessionService(operations),
                runRepository,
                null,
                null,
                null,
                Duration.ofDays(3)
        );
        ChatRunTerminalCommitService.TerminalCommitContext context = terminalContext();

        assertThatThrownBy(() -> service.commitCompleted(
                new ChatRunTerminalCommitService.CompletedCommitCommand(
                        RunCompletedEvent.of("run1", "session1", Map.of("status", "COMPLETED")),
                        context,
                        null
                )))
                .isInstanceOf(ChatEventAppendRejectedException.class);
        assertThat(operations).containsExactly("session-lock", "run-fence");

        operations.clear();
        assertThatThrownBy(() -> service.commitWaitingUser(
                new ChatRunTerminalCommitService.WaitingUserCommitCommand(
                        new RunWaitingUserEvent("run1", "session1", 0L, Instant.now(), Map.of()),
                        context,
                        null,
                        null
                )))
                .isInstanceOf(ChatEventAppendRejectedException.class);
        assertThat(operations).containsExactly("session-lock", "run-fence");

        operations.clear();
        assertThatThrownBy(() -> service.commitTerminalOnly(
                new ChatRunTerminalCommitService.TerminalOnlyCommitCommand(
                        ErrorEvent.of("run1", "session1", "TEST_FAILURE", "test failure"),
                        context
                )))
                .isInstanceOf(ChatEventAppendRejectedException.class);
        assertThat(operations).containsExactly("run-fence");

        org.assertj.core.api.Assertions.assertThat(runRepository.fenceAttempts).hasValue(3);
    }

    @Test
    void externalTerminalLoserDoesNotPersistPreparedPartialAssistant() {
        List<String> operations = new ArrayList<>();
        RejectingRunRepository runRepository = new RejectingRunRepository(operations);
        ChatRunTerminalCommitService service = ChatTerminalTestFixture.service(
                null, recordingSessionService(operations), runRepository, null, null, null, Duration.ofDays(3));
        Instant now = Instant.now();
        ChatRun run = new ChatRun("run1", "tenant1", "user1", "session1", ChatRunStatus.CANCELLING,
                "AGENT_RUNTIME", null, "relay", null, ChatRunMode.NEXT, null, "msg-user",
                null, null, null, "USER_STOP", now, null, Map.of(), now, now);
        ChatSession session = new ChatSession("session1", "tenant1", "user1",
                "test", "ACTIVE", "web", now, now);
        AssistantMessageSaveCommand partial = new AssistantMessageSaveCommand(
                "tenant1", "user1", session, "partial", "run1", "msg-user", null,
                List.of(), "{\"partial\":true}", "msg-assistant");

        ChatRunTerminalCommitService.ExternalTerminalCommitResult result = service.commitExternalTerminal(
                ChatRunTerminalCommitService.ExternalTerminalCommitCommand.stop(
                        RunCancelledEvent.of("run1", "session1", "USER_STOP", true, "msg-assistant"),
                        run,
                        partial));

        assertThat(result.committed()).isFalse();
        assertThat(result.event()).isNull();
        assertThat(operations).containsExactly("session-lock", "run-cas");
    }

    @Test
    void externalTerminalWithoutPartialAssistantDoesNotLockSession() {
        List<String> operations = new ArrayList<>();
        RejectingRunRepository runRepository = new RejectingRunRepository(operations);
        ChatRunTerminalCommitService service = ChatTerminalTestFixture.service(
                null, recordingSessionService(operations), runRepository, null, null, null, Duration.ofDays(3));
        Instant now = Instant.now();
        ChatRun run = new ChatRun("run1", "tenant1", "user1", "session1", ChatRunStatus.CANCELLING,
                "AGENT_RUNTIME", null, "relay", null, ChatRunMode.NEXT, null, "msg-user",
                null, null, null, "USER_STOP", now, null, Map.of(), now, now);

        ChatRunTerminalCommitService.ExternalTerminalCommitResult result = service.commitExternalTerminal(
                ChatRunTerminalCommitService.ExternalTerminalCommitCommand.stop(
                        RunCancelledEvent.of("run1", "session1", "USER_STOP", false, null),
                        run,
                        null));

        assertThat(result.committed()).isFalse();
        assertThat(operations).containsExactly("run-cas");
    }

    @Test
    void interactionPartialAssistantWithoutOriginalIdCannotFallbackToInsert() {
        Instant now = Instant.now();
        ChatRun run = new ChatRun("run1", "tenant1", "user1", "session1", ChatRunStatus.CANCELLING,
                "AGENT_RUNTIME", null, "relay", null, ChatRunMode.NEXT, null, "msg-user",
                null, null, null, "USER_STOP", now, null,
                Map.of("interactionId", "interaction1"), now, now);
        ChatRunTerminalCommitService service = ChatTerminalTestFixture.service(
                null, recordingSessionService(new ArrayList<>()), new SingleRunRepository(run),
                null, null, null, Duration.ofDays(3));
        ChatSession session = new ChatSession("session1", "tenant1", "user1",
                "test", "ACTIVE", "web", now, now);
        AssistantMessageSaveCommand partial = new AssistantMessageSaveCommand(
                "tenant1", "user1", session, "partial", "run1", "msg-user", null,
                List.of(), "{\"partial\":true}", "msg-new");

        assertThatThrownBy(() -> service.commitExternalTerminal(
                ChatRunTerminalCommitService.ExternalTerminalCommitCommand.stop(
                        RunCancelledEvent.of("run1", "session1", "USER_STOP", true, "msg-new"),
                        run,
                        partial)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须复用原 assistantMessageId");
    }

    private ChatRunTerminalCommitService.TerminalCommitContext terminalContext() {
        Instant now = Instant.now();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatSession session = new ChatSession("session1", user.tenantId(), user.ownerUserId(),
                "test", "ACTIVE", "web", now, now);
        return new ChatRunTerminalCommitService.TerminalCommitContext(
                user,
                session,
                null,
                new AtomicReference<>(),
                new AssistantAssembly(),
                "run1",
                new RunExecutionClaim("run1", "instance-test", 1L),
                null
        );
    }

    private SessionApplicationService recordingSessionService(List<String> operations) {
        return TestSessionApplicationServices.create(
                new RecordingSessionRepository(operations), null, null, new PermissionChecker());
    }

    private static final class RejectingRunRepository implements ChatRunRepository {
        private final AtomicInteger fenceAttempts = new AtomicInteger();
        private final List<String> operations;

        private RejectingRunRepository(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public boolean tryFenceOwnerTerminalCommit(OwnerTerminalFence fence) {
            fenceAttempts.incrementAndGet();
            operations.add("run-fence");
            return false;
        }

        @Override
        public boolean tryClaimExternalTerminal(ExternalTerminalClaim claim) {
            operations.add("run-cas");
            return false;
        }

        @Override
        public ChatRun save(ChatRun run) {
            throw new AssertionError("fence rejection must happen before saving run");
        }

        @Override
        public Optional<ChatRun> findById(String runId) {
            return Optional.empty();
        }

        @Override
        public Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId) {
            return Optional.empty();
        }

        @Override
        public Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId) {
            return Optional.empty();
        }
    }

    private static final class RecordingSessionRepository implements SessionRepository {
        private final List<String> operations;

        private RecordingSessionRepository(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public void lockForMessageMutation(String tenantId, String userId, String sessionId) {
            operations.add("session-lock");
        }

        @Override
        public Optional<ChatSession> findById(String sessionId) {
            return Optional.empty();
        }

        @Override
        public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
            return Optional.empty();
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

    private static final class SingleRunRepository implements ChatRunRepository {
        private final ChatRun run;

        private SingleRunRepository(ChatRun run) {
            this.run = run;
        }

        @Override
        public ChatRun save(ChatRun value) {
            return value;
        }

        @Override
        public Optional<ChatRun> findById(String runId) {
            return run.id().equals(runId) ? Optional.of(run) : Optional.empty();
        }

        @Override
        public Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId) {
            return findById(runId);
        }

        @Override
        public Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId) {
            return Optional.of(run);
        }
    }

    private static class RecordingEventStore implements ChatEventStore {
        private final List<String> operations;

        private RecordingEventStore(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public ChatEvent append(ChatEvent event) {
            throw new AssertionError("refusal commit must use execution guard");
        }

        @Override
        public ChatEvent appendWithExecutionGuard(ChatEvent event, RunExecutionClaim claim) {
            operations.add("event");
            return new RuntimeEvent(event.runId(), event.sessionId(), 1L, Instant.now(),
                    event.type(), event.payload());
        }

        @Override
        public List<ChatEvent> findByOwnerAndSessionAfterSeq(String tenantId, String userId,
                                                             String sessionId, long afterSeq) {
            return List.of();
        }

        @Override
        public List<ChatEvent> findByOwnerAndRunAfterSeq(String tenantId, String userId, String sessionId,
                                                         String runId, long afterSeq) {
            return List.of();
        }

        @Override
        public long findLatestSeqByOwnerAndSession(String tenantId, String userId, String sessionId) {
            return 0;
        }
    }

    private static final class RecordingRuntimeBindingRepository implements RuntimeBindingRepository {
        private final List<String> operations;

        private RecordingRuntimeBindingRepository(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public Optional<RuntimeBinding> findById(String bindingId) {
            return Optional.empty();
        }

        @Override
        public Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId,
                                                   String provider) {
            return Optional.empty();
        }

        @Override
        public RuntimeBinding save(RuntimeBinding binding) {
            operations.add("binding");
            return binding;
        }
    }
}
