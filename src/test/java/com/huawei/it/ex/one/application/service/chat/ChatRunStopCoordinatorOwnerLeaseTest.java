package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStopResult;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

class ChatRunStopCoordinatorOwnerLeaseTest {
    private final UserContext user = new UserContext("tenant1", "user1", "User One");

    @Test
    void acceptedOwnerKeepsExclusiveFinalizationWhenCommitAckTimesOut() {
        Fixture fixture = fixture();
        ChatRun running = run(ChatRunStatus.RUNNING);
        ChatRun cancelling = running.cancelling("USER_STOP");
        when(fixture.runService.requireOwnedRun(user, "run-1"))
                .thenReturn(running, cancelling);
        when(fixture.handoff.handoff(any(), any(), anyString(), any()))
                .thenReturn(new RunStopHandoffCoordinator.Outcome(false, true, false));
        when(fixture.runService.convergeExpiredCancellingRun(user, cancelling)).thenReturn(cancelling);
        ChatRunStopResult expected = result(cancelling);
        when(fixture.runService.toStopResult(cancelling)).thenReturn(expected);

        ChatRunStopResult actual = fixture.coordinator.stopRunNow(
                user, "run-1", "USER_STOP", RuntimeForwardHeaders.empty());

        assertThat(actual).isSameAs(expected);
        verify(fixture.replay, never()).replayAndCommit(any(), any(), anyString(), any());
        verify(fixture.runService, never()).requestStop(any(), any(ChatRun.class), anyString());
    }

    @Test
    void durableCancellingExecutionPreventsFallbackWhenAcceptedAckWasLost() {
        Fixture fixture = fixture();
        ChatRun running = run(ChatRunStatus.RUNNING);
        ChatRun cancelling = running.cancelling("USER_STOP");
        when(fixture.runService.requireOwnedRun(user, "run-1"))
                .thenReturn(running, cancelling);
        when(fixture.handoff.handoff(any(), any(), anyString(), any()))
                .thenReturn(new RunStopHandoffCoordinator.Outcome(false, false, false));
        when(fixture.leaseService.stopFallbackBlocked("run-1")).thenReturn(true);
        when(fixture.runService.convergeExpiredCancellingRun(user, cancelling)).thenReturn(cancelling);
        ChatRunStopResult expected = result(cancelling);
        when(fixture.runService.toStopResult(cancelling)).thenReturn(expected);

        ChatRunStopResult actual = fixture.coordinator.stopRunNow(
                user, "run-1", "USER_STOP", RuntimeForwardHeaders.empty());

        assertThat(actual).isSameAs(expected);
        verify(fixture.replay, never()).replayAndCommit(any(), any(), anyString(), any());
        verify(fixture.runService, never()).requestStop(any(), any(ChatRun.class), anyString());
    }

    private Fixture fixture() {
        SessionApplicationService sessionService = mock(SessionApplicationService.class);
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        LocalChatRunExecutionRegistry registry = mock(LocalChatRunExecutionRegistry.class);
        AgentRuntimeExecutor runtimeExecutor = mock(AgentRuntimeExecutor.class);
        ChatInteractionApplicationService interactionService = mock(ChatInteractionApplicationService.class);
        ChatRunTerminalCommitService terminalService = mock(ChatRunTerminalCommitService.class);
        RunStopHandoffCoordinator handoff = mock(RunStopHandoffCoordinator.class);
        ChatRunStopReplayService replay = mock(ChatRunStopReplayService.class);
        ChatRunStopCoordinator coordinator = new ChatRunStopCoordinator(
                sessionService, streamService, runService, leaseService, registry,
                runtimeExecutor, interactionService, terminalService, mock(IdGenerator.class));
        coordinator.setStopHandoffCoordinator(handoff);
        coordinator.setStopReplayService(replay);
        return new Fixture(coordinator, runService, leaseService, handoff, replay);
    }

    private ChatRun run(ChatRunStatus status) {
        Instant now = Instant.now();
        return new ChatRun(
                "run-1", "tenant1", "user1", "session1", status,
                "AGENT_RUNTIME", null, null, null, null, null,
                null, now, null, Map.of(), now, now);
    }

    private ChatRunStopResult result(ChatRun run) {
        return new ChatRunStopResult(run.id(), run.sessionId(), run.status(), 0L, Instant.now());
    }

    private record Fixture(
            ChatRunStopCoordinator coordinator,
            ChatRunApplicationService runService,
            ChatRunLeaseApplicationService leaseService,
            RunStopHandoffCoordinator handoff,
            ChatRunStopReplayService replay
    ) {
    }
}
