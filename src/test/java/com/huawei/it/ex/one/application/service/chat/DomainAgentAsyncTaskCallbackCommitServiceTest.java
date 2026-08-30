/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

class DomainAgentAsyncTaskCallbackCommitServiceTest {
    @Test
    void completedCallbackPersistsNotificationAndUpdatesOnlyAssistantMetadata() {
        Fixture fixture = new Fixture();
        fixture.prepare(ChatRunStatus.COMPLETED, 12L, 10L);

        DomainAgentAsyncTaskCallbackCommitService.CommitResult result = fixture.service.commit(
                new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                        fixture.running.id(), true, null));

        assertThat(result.accepted()).isTrue();
        assertThat(result.events()).extracting(item -> item.event().type()).containsExactly(
                "run.async_finished", "message.completed", "run.completed");
        assertThat(result.events().getFirst().event().payload())
                .containsEntry("status", "COMPLETED")
                .containsEntry("assistantMessageId", fixture.assistant.id());

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(fixture.sessionService).updateAssistantMetadataForInternalUse(
                eq(fixture.session), eq(fixture.assistant), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .contains("\"status\":\"COMPLETED\"")
                .contains("\"skillId\":\"skill1\"");
        verify(fixture.sessionService, never()).updateAssistantMessage(any());
        verify(fixture.executionRepository).markTerminal(
                fixture.running.id(), ChatRunExecutionStatus.COMPLETED);
        verify(fixture.sessionService).advanceLatestMessageSeq(any(), eq(fixture.session), eq(12L));
    }

    @Test
    void failedCallbackPreservesAssistantAndPublishesFailureNotification() throws Exception {
        Fixture fixture = new Fixture();
        fixture.prepare(ChatRunStatus.FAILED, 22L, 20L);
        String error = "错".repeat(1023) + "😀";

        DomainAgentAsyncTaskCallbackCommitService.CommitResult result = fixture.service.commit(
                new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                        fixture.running.id(), false,
                        error));

        assertThat(result.accepted()).isTrue();
        assertThat(result.events()).extracting(item -> item.event().type()).containsExactly(
                "run.async_finished", "message.completed", "run.failed");
        assertThat(result.events().getFirst().event().payload()).containsEntry("status", "FAILED");
        assertThat(result.events().getLast().event().payload())
                .containsEntry("code", "DOMAIN_AGENT_ASYNC_FAILED")
                .containsEntry("error", error);
        assertThat(fixture.objectMapper.writeValueAsBytes(result.events().getLast().event()).length)
                .isLessThan(8192);
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(fixture.sessionService).updateAssistantMetadataForInternalUse(
                eq(fixture.session), eq(fixture.assistant), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue()).contains("\"status\":\"FAILED\"");
        verify(fixture.executionRepository).markTerminal(
                fixture.running.id(), ChatRunExecutionStatus.FAILED);
    }

    @Test
    void failedCallbackOmitsMissingErrorText() {
        Fixture fixture = new Fixture();
        fixture.prepare(ChatRunStatus.FAILED, 22L, 20L);

        DomainAgentAsyncTaskCallbackCommitService.CommitResult result = fixture.service.commit(
                new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                        fixture.running.id(), false, null));

        assertThat(result.events().getLast().event().payload()).doesNotContainKey("error");
    }

    @Test
    void rejectedTerminalClaimDoesNotTouchAssistantOrEvents() {
        Fixture fixture = new Fixture();
        when(fixture.runRepository.findById(fixture.running.id())).thenReturn(Optional.of(fixture.running));
        when(fixture.sessionRepository.findByTenantIdAndUserIdAndId(
                fixture.running.tenantId(), fixture.running.userId(), fixture.running.sessionId()))
                .thenReturn(Optional.of(fixture.session));
        when(fixture.runRepository.tryClaimExternalTerminal(any())).thenReturn(false);

        DomainAgentAsyncTaskCallbackCommitService.CommitResult result = fixture.service.commit(
                new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                        fixture.running.id(), true, null));

        assertThat(result.accepted()).isFalse();
        verify(fixture.streamService, never()).appendBatchWithoutPublish(any());
        verify(fixture.sessionService, never()).updateAssistantMetadataForInternalUse(any(), any(), any());
        verify(fixture.executionRepository, never()).markTerminal(any(), any());
    }

    private static final class Fixture {
        private final ChatRunRepository runRepository = mock(ChatRunRepository.class);
        private final ChatRunExecutionRepository executionRepository = mock(ChatRunExecutionRepository.class);
        private final SessionRepository sessionRepository = mock(SessionRepository.class);
        private final SessionApplicationService sessionService = mock(SessionApplicationService.class);
        private final ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        private final Instant now = Instant.now();
        private final ChatRun running = asyncRun(now);
        private final ChatSession session = new ChatSession(
                "session1", "tenant1", "user1", "title", "ACTIVE", "web", now, now);
        private final ChatMessage assistant = assistant(now);
        private final DomainAgentAsyncTaskCallbackCommitService service =
                new DomainAgentAsyncTaskCallbackCommitService(
                        runRepository, executionRepository, sessionRepository,
                        sessionService, streamService, objectMapper);

        private void prepare(ChatRunStatus terminalStatus, long terminalSequence, long firstSequence) {
            ChatRun claimed = terminalStatus == ChatRunStatus.COMPLETED
                    ? running.completed(0L) : running.failed(0L);
            ChatRun committed = (terminalStatus == ChatRunStatus.COMPLETED
                    ? running.completed(terminalSequence) : running.failed(terminalSequence))
                    .withMetadataSnapshot(DomainAgentAsyncTaskMetadata.clearRunMetadata(running.metadata()));
            when(runRepository.findById(running.id()))
                    .thenReturn(Optional.of(running), Optional.of(claimed));
            when(sessionRepository.findByTenantIdAndUserIdAndId(
                    running.tenantId(), running.userId(), running.sessionId()))
                    .thenReturn(Optional.of(session));
            when(runRepository.tryClaimExternalTerminal(any())).thenReturn(true);
            when(sessionService.requireAssistantForInternalUpdate(session, assistant.id())).thenReturn(assistant);
            AtomicLong sequence = new AtomicLong(firstSequence);
            when(streamService.appendBatchWithoutPublish(any())).thenAnswer(invocation -> {
                List<ChatEvent> events = invocation.getArgument(0);
                return events.stream()
                        .map(event -> (ChatEvent) new StoredChatEvent(
                                event.runId(), event.sessionId(), sequence.getAndIncrement(),
                                event.type(), event.createdAt(), event.payload()))
                        .toList();
            });
            when(runRepository.save(any(ChatRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(runRepository.finalizeExternalTerminal(any())).thenReturn(committed);
        }

        private static ChatRun asyncRun(Instant now) {
            return new ChatRun(
                    "run1", "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
                    "DOMAIN_AGENT", "skill1", "domain-agent", null, ChatRunMode.NEXT,
                    null, "msg-user", "msg-assistant", 1L, 4L, null,
                    now, null,
                    DomainAgentAsyncTaskMetadata.runningOverlay(
                            "msg-assistant", now.plusSeconds(3600)),
                    now, now);
        }

        private static ChatMessage assistant(Instant now) {
            ChatMessagePart part = new ChatMessagePart(
                    "part1", "tenant1", "user1", "session1", "msg-assistant", "run1",
                    "CARD", "existing", null, Map.of(), 1, now);
            return new ChatMessage(
                    "msg-assistant", "tenant1", "user1", "session1",
                    "msg-user", 2L, 1, 0, "assistant", "existing answer", null,
                    "run1", "NORMAL", false, null, null, null, null,
                    "{\"skillId\":\"skill1\",\"domainAgentAsyncTask\":{\"status\":\"ASYNC_RUNNING\"}}",
                    List.of(part), now);
        }
    }
}
