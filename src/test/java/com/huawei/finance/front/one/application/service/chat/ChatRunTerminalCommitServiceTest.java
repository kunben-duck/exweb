package com.huawei.finance.front.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.front.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.finance.front.one.application.integration.conversation.ChatRunRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunMode;
import com.huawei.finance.front.one.domain.chat.ChatRunStatus;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import com.huawei.finance.front.one.domain.chat.ErrorEvent;
import com.huawei.finance.front.one.domain.chat.RunCancelledEvent;
import com.huawei.finance.front.one.domain.chat.RunCompletedEvent;
import com.huawei.finance.front.one.domain.chat.RunExecutionClaim;
import com.huawei.finance.front.one.domain.chat.RunWaitingUserEvent;
import com.huawei.finance.front.one.domain.chat.ChatSession;
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
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
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
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
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
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
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
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
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
        return new SessionApplicationService(
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
}
