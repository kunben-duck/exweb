/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.agent.RuntimeInteractionDispatchState;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ErrorEvent;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.chat.RunCancelledEvent;
import com.huawei.it.ex.one.domain.chat.RunCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RunWaitingUserEvent;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

class RunDurationCommitTest {
    private final Instant start = Instant.parse("2026-10-15T00:00:00Z");
    private final Instant end = start.plusMillis(12580);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatRunRepository runs = mock(ChatRunRepository.class);
    private final SessionApplicationService sessions = mock(SessionApplicationService.class);
    private final ChatStreamApplicationService streams = mock(ChatStreamApplicationService.class);
    private final ChatRunLeaseApplicationService leases = mock(ChatRunLeaseApplicationService.class);
    private final ChatInteractionApplicationService interactions = mock(ChatInteractionApplicationService.class);
    private final ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
            streams, sessions, runs, leases, null, interactions, Duration.ZERO, mapper);
    private final ChatSession session = new ChatSession("session1", "tenant1", "user1", "title",
            "ACTIVE", "web", start, start);
    private final ChatMessage userMessage = new ChatMessage("msg-user", "tenant1", "user1", "session1",
            "user", "question", null, start);
    private final ChatRun run = new ChatRun("run1", "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
            "DOMAIN_AGENT", "skill1", "domain-agent", "agent-session", 1L, 2L, null,
            start, null, Map.of(), start, start);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completionAndNoStorePersistSameDurationAsEventWithoutExtraReads(boolean noStore) throws Exception {
        prepare();
        try (var clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(end);
            var result = service.commitCompleted(new ChatRunTerminalCommitService.CompletedCommitCommand(
                    RunCompletedEvent.of("run1", "session1"), context(null, noStore), target()));
            ArgumentCaptor<AssistantMessageSaveCommand> captured = ArgumentCaptor.forClass(AssistantMessageSaveCommand.class);
            verify(sessions).saveAssistantMessage(captured.capture());
            assertDuration(result.event(), captured.getValue().metadataJson());
            assertThat(captured.getValue().metadataJson()).contains("\"skillId\":\"skill1\"");
            assertThat(captured.getValue().content()).isEqualTo(noStore ? "placeholder" : "answer");
            assertThat(new AgentDataPersistenceEventPolicy().retention(result.event(),
                    context(null, noStore).assistant().persistenceState()))
                    .isEqualTo(AgentDataPersistenceEventPolicy.EventRetention.PERSISTED);
        }
        verify(runs, times(2)).findById("run1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitingAndReusedAnswerSaveOnlyCurrentRunDuration(boolean waiting) throws Exception {
        prepare();
        ChatInteractionRequest interaction = mock(ChatInteractionRequest.class);
        when(interaction.interactionType()).thenReturn(ChatInteractionType.AGENT_CLARIFICATION);
        when(interaction.assistantMessageId()).thenReturn("msg-assistant");
        try (var clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(end);
            var context = context(interaction, false);
            var result = waiting
                    ? service.commitWaitingUser(new ChatRunTerminalCommitService.WaitingUserCommitCommand(
                            RunWaitingUserEvent.of("run1", "session1", Map.of("interactionId", "interaction1")),
                            context, target(), interaction))
                    : service.commitCompleted(new ChatRunTerminalCommitService.CompletedCommitCommand(
                            RunCompletedEvent.of("run1", "session1"), context, target()));
            ArgumentCaptor<AssistantMessageUpdateCommand> captured = ArgumentCaptor.forClass(AssistantMessageUpdateCommand.class);
            verify(sessions).updateAssistantMessage(captured.capture());
            assertDuration(result.event(), captured.getValue().metadataJson());
            assertThat(captured.getValue().runId()).isEqualTo("run1");
            if (waiting) {
                assertThat(captured.getValue().metadataJson()).contains("\"finishReason\":\"WAITING_USER\"");
                verify(interactions).saveInteraction(interaction);
            }
        }
        verify(runs, times(2)).findById("run1");
        verify(sessions, never()).saveAssistantMessage(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failureOrCancellationWithoutAssistantAddsOnlyEventField(boolean failed) {
        prepare();
        try (var clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(end);
            ChatEvent event = failed ? ErrorEvent.of("run1", "session1", "ERROR", "failed")
                    : RunCancelledEvent.of("run1", "session1", "USER_STOP");
            var result = service.commitTerminalOnly(new ChatRunTerminalCommitService.TerminalOnlyCommitCommand(
                    event, context(null, false)));
            assertThat(result.event().payload()).containsAllEntriesOf(event.payload()).containsEntry("runDurationMs", 12580L);
        }
        verify(runs).findById("run1");
        verify(sessions, never()).saveAssistantMessage(any());
        verify(sessions, never()).updateAssistantMessage(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void externalStopOrWatchdogUsesSameSnapshotAndDoesNotWriteOnLostCas(boolean accepted) throws Exception {
        prepare();
        when(runs.tryClaimExternalTerminal(any())).thenReturn(accepted);
        when(runs.finalizeExternalTerminal(any())).thenReturn(run);
        AssistantMessageSaveCommand partial = new AssistantMessageSaveCommand("tenant1", "user1", session,
                "partial", "run1", "msg-user", null, List.of(), "{\"partial\":true}", "msg-assistant");
        try (var clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(end);
            var result = service.commitExternalTerminal(ChatRunTerminalCommitService.ExternalTerminalCommitCommand.stop(
                    RunCancelledEvent.of("run1", "session1", "USER_STOP", true, "msg-assistant"), run, partial));
            assertThat(result.committed()).isEqualTo(accepted);
            if (accepted) {
                ArgumentCaptor<AssistantMessageSaveCommand> captured = ArgumentCaptor.forClass(AssistantMessageSaveCommand.class);
                verify(sessions).saveAssistantMessage(captured.capture());
                assertDuration(result.event(), captured.getValue().metadataJson());
                assertThat(captured.getValue().metadataJson()).contains("\"partial\":true");
            } else {
                verify(streams, never()).appendWithoutPublish(any());
                verify(sessions, never()).saveAssistantMessage(any());
            }
        }
    }

    private void prepare() {
        ChatMessage saved = mock(ChatMessage.class);
        when(saved.id()).thenReturn("msg-assistant");
        when(sessions.saveAssistantMessage(any())).thenReturn(saved);
        when(sessions.updateAssistantMessage(any())).thenReturn(saved);
        when(runs.tryFenceOwnerTerminalCommit(any())).thenReturn(true);
        when(runs.findById("run1")).thenReturn(Optional.of(run));
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(streams.appendWithExecutionGuard(any(), any())).thenAnswer(invocation -> stored(invocation.getArgument(0)));
        when(streams.appendWithoutPublish(any())).thenAnswer(invocation -> stored(invocation.getArgument(0)));
    }

    private ChatEvent stored(ChatEvent event) {
        return new StoredChatEvent(event.runId(), event.sessionId(), 10L, event.type(), event.createdAt(), event.payload());
    }

    private void assertDuration(ChatEvent event, String metadata) throws Exception {
        assertThat(event.payload()).containsEntry("runDurationMs", 12580L);
        assertThat(mapper.readTree(metadata).get("runDurationMs").longValue()).isEqualTo(12580L);
    }

    private ChatRunTerminalCommitService.MessageTarget target() {
        return new ChatRunTerminalCommitService.MessageTarget(true, "msg-assistant");
    }

    private ChatRunTerminalCommitService.TerminalCommitContext context(ChatInteractionRequest interaction, boolean noStore) {
        AgentDataPersistenceState state = new AgentDataPersistenceState("placeholder");
        if (noStore) {
            state.tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER).markRuntimeDispatchStarted();
        }
        AssistantAssembly assembly = new AssistantAssembly(state);
        assembly.messageSkill().replace("skill1");
        assembly.observe(MessageSnapshotEvent.of("run1", "session1", "answer"));
        return new ChatRunTerminalCommitService.TerminalCommitContext(
                new UserContext("tenant1", "user1", "tester"), session,
                new ChatRunMessagePlan(ChatRunMode.NEXT, "msg-user", userMessage, null),
                new AtomicReference<>(), assembly, "run1", new RunExecutionClaim("run1", "instance1", 1L),
                interaction, RuntimeInteractionDispatchState.untracked(), new AtomicReference<>(),
                new AtomicReference<>(), start);
    }
}
