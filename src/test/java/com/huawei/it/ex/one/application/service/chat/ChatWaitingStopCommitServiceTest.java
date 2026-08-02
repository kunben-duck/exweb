package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.conversation.ChatInteractionRequestRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.memory.RouteMemoryRepository;
import com.huawei.it.ex.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

class ChatWaitingStopCommitServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-01T10:00:00Z");

    private final UserContext user = new UserContext("tenant1", "user1", "User One");
    private final SessionApplicationService sessionService = mock(SessionApplicationService.class);
    private final ChatInteractionRequestRepository interactionRepository =
            mock(ChatInteractionRequestRepository.class);
    private final ChatRunRepository runRepository = mock(ChatRunRepository.class);
    private final RuntimeBindingRepository bindingRepository = mock(RuntimeBindingRepository.class);
    private final RuntimeBindingApplicationService bindingService =
            mock(RuntimeBindingApplicationService.class);
    private final RouteMemoryRepository routeMemoryRepository = mock(RouteMemoryRepository.class);
    private final ChatWaitingStopCommitService service = new ChatWaitingStopCommitService(
            sessionService,
            interactionRepository,
            runRepository,
            bindingRepository,
            bindingService,
            routeMemoryRepository);

    private ChatSession session;
    private ChatRun sourceRun;

    @BeforeEach
    void setUp() {
        session = new ChatSession(
                "session1", user.tenantId(), user.ownerUserId(), "Session", "ACTIVE", "web", NOW, NOW);
        sourceRun = run("run-a", ChatRunStatus.WAITING_USER, "intent-agent", null, null);
        when(sessionService.getSession(user, session.id())).thenReturn(session);
    }

    @Test
    void waitingIntentClarificationCancelsOnlyLocalWaitingState() {
        ChatInteractionRequest interaction = interaction(
                ChatInteractionStatus.WAITING, null, null, "intent-agent", null, null);
        stubInteraction(interaction);
        when(interactionRepository.cancelWaitingById(
                eq(user.tenantId()), eq(user.ownerUserId()), eq(interaction.id()), any(Instant.class)))
                .thenReturn(1);

        ChatWaitingStopCommitService.WaitingStopCommitResult result =
                service.cancelWaiting(user, sourceRun, "USER_STOP");

        assertThat(result.interactionCancelled()).isTrue();
        assertThat(result.effectiveRun()).isNull();
        assertThat(result.runtimeTarget()).isNull();
        verify(routeMemoryRepository).foldActiveClarifications(
                eq(user.tenantId()), eq(user.ownerUserId()), eq(session.id()), any(Instant.class));
        verify(bindingRepository, never()).cancelActiveForInteraction(any(), any(), any());
    }

    @Test
    void waitingRelayQuestionnaireCancelsExactBindingAndKeepsRuntimeSessionForRemoteStop() {
        RuntimeBinding binding = binding(RuntimeBindingStatus.ACTIVE, "run-a");
        ChatInteractionRequest interaction = interaction(
                ChatInteractionStatus.WAITING,
                null,
                binding.id(),
                "relay",
                binding.runtimeSessionId(),
                "approval-1");
        stubInteraction(interaction);
        when(interactionRepository.cancelWaitingById(
                eq(user.tenantId()), eq(user.ownerUserId()), eq(interaction.id()), any(Instant.class)))
                .thenReturn(1);
        when(bindingRepository.findById(binding.id())).thenReturn(Optional.of(binding));
        when(bindingRepository.cancelActiveForInteraction(binding, sourceRun.id(), null)).thenReturn(true);

        ChatWaitingStopCommitService.WaitingStopCommitResult result =
                service.cancelWaiting(user, sourceRun, "USER_STOP");

        assertThat(result.cancelledBinding()).isNotNull();
        assertThat(result.cancelledBinding().status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
        assertThat(result.runtimeTarget()).isNotNull();
        assertThat(result.runtimeTarget().provider()).isEqualTo("relay");
        assertThat(result.runtimeTarget().runtimeSessionId()).isEqualTo("relay-session-1");
        assertThat(result.runtimeTarget().runId()).isEqualTo("run-a");
        verify(bindingService).synchronizeCache(result.cancelledBinding());
    }

    @Test
    void respondingInteractionMarksContinuationCancellingBeforeCancellingInteraction() {
        ChatRun continuation = run("run-b", ChatRunStatus.RUNNING, "relay", "relay-session-1", null);
        ChatInteractionRequest interaction = interaction(
                ChatInteractionStatus.RESPONDING, continuation.id(), null, "relay", "relay-session-1", "approval-1");
        stubInteraction(interaction);
        when(runRepository.findByTenantIdAndUserIdAndIdForUpdate(
                user.tenantId(), user.ownerUserId(), continuation.id()))
                .thenReturn(Optional.of(continuation));
        when(runRepository.tryMarkCancelling(any(ChatRunRepository.StopClaim.class))).thenReturn(true);
        when(interactionRepository.cancelRespondingForRun(
                eq(user.tenantId()), eq(user.ownerUserId()), eq(interaction.id()),
                eq(continuation.id()), any(Instant.class)))
                .thenReturn(1);

        ChatWaitingStopCommitService.WaitingStopCommitResult result =
                service.cancelWaiting(user, sourceRun, "USER_STOP");

        assertThat(result.interactionCancelled()).isTrue();
        assertThat(result.effectiveRun()).isNotNull();
        assertThat(result.effectiveRun().status()).isEqualTo(ChatRunStatus.CANCELLING);
        assertThat(result.effectiveRun().cancelReason()).isEqualTo("USER_STOP");
        InOrder order = inOrder(sessionService, runRepository, interactionRepository);
        order.verify(sessionService).lockForMessageMutation(user.tenantId(), user.ownerUserId(), session);
        order.verify(interactionRepository).findLatestBySourceRun(
                user.tenantId(), user.ownerUserId(), session.id(), sourceRun.id());
        order.verify(runRepository).findByTenantIdAndUserIdAndIdForUpdate(
                user.tenantId(), user.ownerUserId(), continuation.id());
        order.verify(runRepository).tryMarkCancelling(any(ChatRunRepository.StopClaim.class));
        order.verify(interactionRepository).findByOwnerAndIdForUpdate(
                user.tenantId(), user.ownerUserId(), interaction.id());
        order.verify(interactionRepository).cancelRespondingForRun(
                eq(user.tenantId()), eq(user.ownerUserId()), eq(interaction.id()),
                eq(continuation.id()), any(Instant.class));
    }

    @Test
    void terminalContinuationStillUsesItsRunIdForIdempotentRuntimeCancellation() {
        ChatRun continuation = run("run-b", ChatRunStatus.CANCELLED, "relay", "relay-session-1", null);
        RuntimeBinding binding = binding(RuntimeBindingStatus.ACTIVE, continuation.id());
        ChatInteractionRequest interaction = interaction(
                ChatInteractionStatus.RESPONDING,
                continuation.id(),
                binding.id(),
                "relay",
                binding.runtimeSessionId(),
                "approval-1");
        stubInteraction(interaction);
        when(runRepository.findByTenantIdAndUserIdAndIdForUpdate(
                user.tenantId(), user.ownerUserId(), continuation.id()))
                .thenReturn(Optional.of(continuation));
        when(interactionRepository.cancelRespondingForRun(
                eq(user.tenantId()), eq(user.ownerUserId()), eq(interaction.id()),
                eq(continuation.id()), any(Instant.class)))
                .thenReturn(1);
        when(bindingRepository.findById(binding.id())).thenReturn(Optional.of(binding));
        when(bindingRepository.cancelActiveForInteraction(
                binding, sourceRun.id(), continuation.id())).thenReturn(true);

        ChatWaitingStopCommitService.WaitingStopCommitResult result =
                service.cancelWaiting(user, sourceRun, "USER_STOP");

        assertThat(result.effectiveRun()).isNull();
        assertThat(result.runtimeTarget()).isNotNull();
        assertThat(result.runtimeTarget().runId()).isEqualTo(continuation.id());
    }

    @Test
    void oldWaitingRunDoesNotCancelAConversationNewerInteraction() {
        when(interactionRepository.findLatestBySourceRun(
                user.tenantId(), user.ownerUserId(), session.id(), sourceRun.id()))
                .thenReturn(Optional.empty());

        ChatWaitingStopCommitService.WaitingStopCommitResult result =
                service.cancelWaiting(user, sourceRun, "USER_STOP");

        assertThat(result.interaction()).isNull();
        assertThat(result.interactionCancelled()).isFalse();
        verify(interactionRepository, never()).cancelWaitingById(any(), any(), any(), any());
        verify(interactionRepository, never()).cancelRespondingForRun(any(), any(), any(), any(), any());
        verify(routeMemoryRepository, never()).foldActiveClarifications(any(), any(), any(), any());
    }

    @Test
    void waitingStopUsesBoundedTransactionTimeout() throws NoSuchMethodException {
        Method method = ChatWaitingStopCommitService.class.getMethod(
                "cancelWaiting", UserContext.class, ChatRun.class, String.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString())
                .isEqualTo("${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}");
    }

    private void stubInteraction(ChatInteractionRequest interaction) {
        when(interactionRepository.findLatestBySourceRun(
                user.tenantId(), user.ownerUserId(), session.id(), sourceRun.id()))
                .thenReturn(Optional.of(interaction));
        when(interactionRepository.findByOwnerAndIdForUpdate(
                user.tenantId(), user.ownerUserId(), interaction.id()))
                .thenReturn(Optional.of(interaction));
    }

    private ChatInteractionRequest interaction(
            ChatInteractionStatus status,
            String continueRunId,
            String bindingId,
            String provider,
            String runtimeSessionId,
            String approvalId) {
        return new ChatInteractionRequest(
                "interaction-1",
                user.tenantId(),
                user.ownerUserId(),
                session.id(),
                sourceRun.id(),
                continueRunId,
                "message-user",
                "message-assistant",
                provider,
                bindingId,
                runtimeSessionId,
                approvalId,
                ChatInteractionType.INTENT_CLARIFICATION,
                status,
                Map.of(),
                Map.of(),
                NOW.plusSeconds(3600),
                null,
                null,
                NOW,
                NOW);
    }

    private RuntimeBinding binding(RuntimeBindingStatus status, String lastRunId) {
        return new RuntimeBinding(
                "binding-1",
                user.tenantId(),
                user.ownerUserId(),
                session.id(),
                "relay",
                "message-user",
                "relay-session-1",
                status,
                lastRunId,
                null,
                NOW,
                NOW,
                Map.of());
    }

    private ChatRun run(
            String runId,
            ChatRunStatus status,
            String provider,
            String runtimeSessionId,
            String agentCode) {
        return new ChatRun(
                runId,
                user.tenantId(),
                user.ownerUserId(),
                "session1",
                status,
                "AGENT_RUNTIME",
                agentCode,
                provider,
                runtimeSessionId,
                1L,
                2L,
                null,
                NOW,
                status == ChatRunStatus.WAITING_USER ? NOW : null,
                Map.of(),
                NOW,
                NOW);
    }
}
