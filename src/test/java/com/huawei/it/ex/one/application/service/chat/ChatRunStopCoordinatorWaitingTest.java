/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
import reactor.core.publisher.Sinks;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.Map;

class ChatRunStopCoordinatorWaitingTest {
    @ParameterizedTest
    @CsvSource({"relay,true", "relay,false", "domain-agent,true", "domain-agent,false"})
    void waitingStopDoesNotWaitForDownstreamOrRestoreCancelledState(String provider, boolean failed) {
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
        ChatRun sourceRun = waitingRun(now)
                .withResolvedRoute("AGENT_RUNTIME", "skill-a", provider, "downstream-session");
        ChatInteractionRequest interaction = waitingInteraction(now);
        ChatWaitingStopCommitService.WaitingRuntimeTarget runtimeTarget =
                new ChatWaitingStopCommitService.WaitingRuntimeTarget(
                        sourceRun.id(), sourceRun.sessionId(), provider, "downstream-session", "skill-a", "AGENT_RUNTIME");
        ChatWaitingStopCommitService.WaitingStopCommitResult committed =
                new ChatWaitingStopCommitService.WaitingStopCommitResult(
                        sourceRun, interaction, null, true, now, null, runtimeTarget);
        ChatRunStopResult baseResult = new ChatRunStopResult(
                sourceRun.id(), sourceRun.sessionId(), ChatRunStatus.WAITING_USER,
                9L, now, false, null, null);
        when(runService.requireOwnedRun(user, sourceRun.id())).thenReturn(sourceRun);
        when(waitingStopService.cancelWaiting(user, sourceRun, "USER_STOP")).thenReturn(committed);
        when(runService.toStopResult(sourceRun)).thenReturn(baseResult);
        Sinks.One<Void> response = Sinks.one();
        when(runtimeExecutor.cancel(any(AgentRuntimeCancelRequest.class)))
                .thenReturn(failed ? Mono.error(new IllegalStateException("downstream unavailable")) : response.asMono());

        try {
            ChatRunStopResult result = coordinator.stopRun(user, sourceRun.id(), "USER_STOP", RuntimeForwardHeaders.empty())
                    .block(java.time.Duration.ofSeconds(2));

            assertThat(result.status()).isEqualTo(ChatRunStatus.WAITING_USER);
            assertThat(result.waitingUserInput()).isFalse();
            assertThat(result.interactionId()).isEqualTo(interaction.id());
            assertThat(result.interactionStatus()).isEqualTo("CANCELLED");
            var order = inOrder(waitingStopService, runtimeExecutor);
            order.verify(waitingStopService).cancelWaiting(user, sourceRun, "USER_STOP");
            order.verify(runtimeExecutor).cancel(any(AgentRuntimeCancelRequest.class));
        } finally {
            response.tryEmitEmpty();
        }
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
