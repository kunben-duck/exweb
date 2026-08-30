/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStopResult;

import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

class ChatRunStopCoordinatorWaitingTest {
    @Test
    void downstreamCancellationFailureDoesNotRestoreCancelledWaitingState() {
        SessionApplicationService sessionService = mock(SessionApplicationService.class);
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        LocalChatRunExecutionRegistry executionRegistry = mock(LocalChatRunExecutionRegistry.class);
        AgentRuntimeExecutor runtimeExecutor = mock(AgentRuntimeExecutor.class);
        ChatInteractionApplicationService interactionService = mock(ChatInteractionApplicationService.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        ChatWaitingStopCommitService waitingStopService = mock(ChatWaitingStopCommitService.class);
        ChatRunStopCoordinator coordinator = new ChatRunStopCoordinator(
                sessionService,
                streamService,
                runService,
                leaseService,
                executionRegistry,
                runtimeExecutor,
                interactionService,
                null,
                idGenerator);
        coordinator.setWaitingStopCommitService(waitingStopService);

        Instant now = Instant.now();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatRun sourceRun = waitingRun(now);
        ChatInteractionRequest interaction = waitingInteraction(now);
        ChatWaitingStopCommitService.WaitingRuntimeTarget runtimeTarget =
                new ChatWaitingStopCommitService.WaitingRuntimeTarget(
                        sourceRun.id(), sourceRun.sessionId(), "relay", "relay-session-1", null, "AGENT_RUNTIME");
        ChatWaitingStopCommitService.WaitingStopCommitResult committed =
                new ChatWaitingStopCommitService.WaitingStopCommitResult(
                        sourceRun, interaction, null, true, now, null, runtimeTarget);
        ChatRunStopResult baseResult = new ChatRunStopResult(
                sourceRun.id(), sourceRun.sessionId(), ChatRunStatus.WAITING_USER,
                9L, now, false, null, null);
        when(runService.requireOwnedRun(user, sourceRun.id())).thenReturn(sourceRun);
        when(waitingStopService.cancelWaiting(user, sourceRun, "USER_STOP")).thenReturn(committed);
        when(runService.toStopResult(sourceRun)).thenReturn(baseResult);
        when(runtimeExecutor.cancel(any(AgentRuntimeCancelRequest.class)))
                .thenReturn(Mono.error(new IllegalStateException("relay unavailable")));

        ChatRunStopResult result = coordinator.stopRunNow(
                user, sourceRun.id(), "USER_STOP", RuntimeForwardHeaders.empty());

        assertThat(result.status()).isEqualTo(ChatRunStatus.WAITING_USER);
        assertThat(result.waitingUserInput()).isFalse();
        assertThat(result.interactionId()).isEqualTo(interaction.id());
        assertThat(result.interactionStatus()).isEqualTo("CANCELLED");
        verify(runtimeExecutor).cancel(any(AgentRuntimeCancelRequest.class));
    }

    private ChatRun waitingRun(Instant now) {
        return new ChatRun(
                "run-a",
                "tenant1",
                "user1",
                "session1",
                ChatRunStatus.WAITING_USER,
                "AGENT_RUNTIME",
                null,
                "relay",
                "relay-session-1",
                1L,
                9L,
                null,
                now,
                now,
                Map.of(),
                now,
                now);
    }

    private ChatInteractionRequest waitingInteraction(Instant now) {
        return new ChatInteractionRequest(
                "interaction1",
                "tenant1",
                "user1",
                "session1",
                "run-a",
                null,
                "message-user",
                "message-assistant",
                "relay",
                "binding1",
                "relay-session-1",
                "approval1",
                ChatInteractionType.AGENT_CLARIFICATION,
                ChatInteractionStatus.WAITING,
                Map.of(),
                Map.of(),
                now.plusSeconds(3600),
                null,
                null,
                now,
                now);
    }
}
