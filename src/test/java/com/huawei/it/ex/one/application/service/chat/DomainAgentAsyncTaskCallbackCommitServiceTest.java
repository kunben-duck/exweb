package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

class DomainAgentAsyncTaskCallbackCommitServiceTest {
    @Test
    void appendCallbackPersistsOrderedTerminalEventsAndUpdatesExistingAssistant() {
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRunExecutionRepository executionRepository = mock(ChatRunExecutionRepository.class);
        SessionRepository sessionRepository = mock(SessionRepository.class);
        SessionApplicationService sessionService = mock(SessionApplicationService.class);
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        DomainAgentAsyncTaskCallbackCommitService service = new DomainAgentAsyncTaskCallbackCommitService(
                runRepository, executionRepository, sessionRepository,
                sessionService, streamService, objectMapper);
        Instant now = Instant.now();
        ChatRun running = asyncRun(now);
        ChatRun claimed = running.completed(0L);
        ChatRun committed = running.completed(14L)
                .withMetadataSnapshot(DomainAgentAsyncTaskMetadata.clearRunMetadata(running.metadata()));
        ChatSession session = new ChatSession(
                "session1", "tenant1", "user1", "title", "ACTIVE", "web", now, now);
        ChatMessage assistant = new ChatMessage(
                "msg-assistant", "tenant1", "user1", "session1",
                "msg-user", 2L, 1, 0, "assistant", "existing ", null,
                "run1", "NORMAL", false, null, null, null, null,
                "{\"skillId\":\"skill1\"}", now);
        when(runRepository.findById(running.id()))
                .thenReturn(Optional.of(running), Optional.of(claimed));
        when(sessionRepository.findByTenantIdAndUserIdAndId(
                running.tenantId(), running.userId(), running.sessionId()))
                .thenReturn(Optional.of(session));
        when(runRepository.tryClaimExternalTerminal(any())).thenReturn(true);
        when(sessionService.requireAssistantForInternalUpdate(session, assistant.id())).thenReturn(assistant);
        when(streamService.appendBatchWithoutPublish(any())).thenAnswer(invocation -> {
            List<ChatEvent> events = invocation.getArgument(0);
            AtomicLong sequence = new AtomicLong(10L);
            List<ChatEvent> stored = new ArrayList<>();
            for (ChatEvent event : events) {
                stored.add(new StoredChatEvent(
                        event.runId(), event.sessionId(), sequence.getAndIncrement(),
                        event.type(), event.createdAt(), event.payload()));
            }
            return List.copyOf(stored);
        });
        when(runRepository.save(any(ChatRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.finalizeExternalTerminal(any())).thenReturn(committed);

        DomainAgentAsyncTaskCallbackCommitService.CommitResult result = service.commit(
                new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                        running.id(), true, "APPEND",
                        List.of(MessageDeltaEvent.of(running.id(), running.sessionId(), "result")), null));

        assertThat(result.accepted()).isTrue();
        assertThat(result.events()).extracting(item -> item.event().type()).containsExactly(
                "run.async_result_started",
                "message.delta",
                "message.completed",
                "run.completed");
        ArgumentCaptor<AssistantMessageUpdateCommand> updateCaptor =
                ArgumentCaptor.forClass(AssistantMessageUpdateCommand.class);
        verify(sessionService).updateAssistantMessage(updateCaptor.capture());
        assertThat(updateCaptor.getValue().content()).isEqualTo("existing result");
        assertThat(updateCaptor.getValue().metadataJson())
                .contains("\"status\":\"COMPLETED\"")
                .contains("\"skillId\":\"skill1\"");
        ArgumentCaptor<ChatRunRepository.ExternalTerminalFinalize> finalizeCaptor =
                ArgumentCaptor.forClass(ChatRunRepository.ExternalTerminalFinalize.class);
        verify(runRepository).finalizeExternalTerminal(finalizeCaptor.capture());
        assertThat(finalizeCaptor.getValue().sequence()).isEqualTo(13L);
        verify(executionRepository).markTerminal(running.id(), ChatRunExecutionStatus.COMPLETED);
        verify(sessionService).advanceLatestMessageSeq(any(), any(), org.mockito.ArgumentMatchers.eq(13L));
    }

    @Test
    void replaceCallbackReplacesContentAndDeletesOnlyCurrentRunParts() {
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRunExecutionRepository executionRepository = mock(ChatRunExecutionRepository.class);
        SessionRepository sessionRepository = mock(SessionRepository.class);
        SessionApplicationService sessionService = mock(SessionApplicationService.class);
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        DomainAgentAsyncTaskCallbackCommitService service = new DomainAgentAsyncTaskCallbackCommitService(
                runRepository, executionRepository, sessionRepository,
                sessionService, streamService, objectMapper);
        Instant now = Instant.now();
        ChatRun running = asyncRun(now);
        ChatRun claimed = running.completed(0L);
        ChatRun committed = running.completed(24L)
                .withMetadataSnapshot(DomainAgentAsyncTaskMetadata.clearRunMetadata(running.metadata()));
        ChatSession session = new ChatSession(
                "session1", "tenant1", "user1", "title", "ACTIVE", "web", now, now);
        ChatMessage assistant = new ChatMessage(
                "msg-assistant", "tenant1", "user1", "session1",
                "msg-user", 2L, 1, 0, "assistant", "old answer", null,
                "run1", "NORMAL", false, null, null, null, null,
                "{\"skillId\":\"skill1\"}", now);
        when(runRepository.findById(running.id()))
                .thenReturn(Optional.of(running), Optional.of(claimed));
        when(sessionRepository.findByTenantIdAndUserIdAndId(
                running.tenantId(), running.userId(), running.sessionId()))
                .thenReturn(Optional.of(session));
        when(runRepository.tryClaimExternalTerminal(any())).thenReturn(true);
        when(sessionService.requireAssistantForInternalUpdate(session, assistant.id())).thenReturn(assistant);
        when(streamService.appendBatchWithoutPublish(any())).thenAnswer(invocation -> {
            List<ChatEvent> events = invocation.getArgument(0);
            AtomicLong sequence = new AtomicLong(21L);
            return events.stream()
                    .map(event -> (ChatEvent) new StoredChatEvent(
                            event.runId(), event.sessionId(), sequence.getAndIncrement(),
                            event.type(), event.createdAt(), event.payload()))
                    .toList();
        });
        when(runRepository.save(any(ChatRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.finalizeExternalTerminal(any())).thenReturn(committed);

        DomainAgentAsyncTaskCallbackCommitService.CommitResult result = service.commit(
                new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                        running.id(), true, "REPLACE",
                        List.of(MessageDeltaEvent.of(running.id(), running.sessionId(), "replacement")), null));

        assertThat(result.accepted()).isTrue();
        verify(sessionService).deleteAssistantPartsForRun(session, assistant.id(), running.id());
        ArgumentCaptor<AssistantMessageUpdateCommand> updateCaptor =
                ArgumentCaptor.forClass(AssistantMessageUpdateCommand.class);
        verify(sessionService).updateAssistantMessage(updateCaptor.capture());
        assertThat(updateCaptor.getValue().content()).isEqualTo("replacement");
        assertThat(updateCaptor.getValue().metadataJson()).contains("\"status\":\"COMPLETED\"");
    }

    @Test
    void failedCallbackWithoutFramesPreservesContentAndStillCommitsTerminalEvents() throws Exception {
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRunExecutionRepository executionRepository = mock(ChatRunExecutionRepository.class);
        SessionRepository sessionRepository = mock(SessionRepository.class);
        SessionApplicationService sessionService = mock(SessionApplicationService.class);
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        DomainAgentAsyncTaskCallbackCommitService service = new DomainAgentAsyncTaskCallbackCommitService(
                runRepository, executionRepository, sessionRepository,
                sessionService, streamService, objectMapper);
        Instant now = Instant.now();
        ChatRun running = asyncRun(now);
        ChatRun claimed = running.failed(0L);
        ChatRun committed = running.failed(32L)
                .withMetadataSnapshot(DomainAgentAsyncTaskMetadata.clearRunMetadata(running.metadata()));
        ChatSession session = new ChatSession(
                "session1", "tenant1", "user1", "title", "ACTIVE", "web", now, now);
        ChatMessage assistant = new ChatMessage(
                "msg-assistant", "tenant1", "user1", "session1",
                "msg-user", 2L, 1, 0, "assistant", "existing answer", null,
                "run1", "NORMAL", false, null, null, null, null,
                "{\"skillId\":\"skill1\"}", now);
        when(runRepository.findById(running.id()))
                .thenReturn(Optional.of(running), Optional.of(claimed));
        when(sessionRepository.findByTenantIdAndUserIdAndId(
                running.tenantId(), running.userId(), running.sessionId()))
                .thenReturn(Optional.of(session));
        when(runRepository.tryClaimExternalTerminal(any())).thenReturn(true);
        when(sessionService.requireAssistantForInternalUpdate(session, assistant.id())).thenReturn(assistant);
        when(streamService.appendBatchWithoutPublish(any())).thenAnswer(invocation -> {
            List<ChatEvent> events = invocation.getArgument(0);
            AtomicLong sequence = new AtomicLong(30L);
            return events.stream()
                    .map(event -> (ChatEvent) new StoredChatEvent(
                            event.runId(), event.sessionId(), sequence.getAndIncrement(),
                            event.type(), event.createdAt(), event.payload()))
                    .toList();
        });
        when(runRepository.save(any(ChatRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.finalizeExternalTerminal(any())).thenReturn(committed);

        DomainAgentAsyncTaskCallbackCommitService.CommitResult result = service.commit(
                new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                        running.id(), false, null, List.of(),
                        objectMapper.readTree("{\"code\":\"UPSTREAM_FAILED\"}")));

        assertThat(result.accepted()).isTrue();
        assertThat(result.events()).extracting(item -> item.event().type()).containsExactly(
                "run.async_result_started", "message.completed", "run.failed");
        ArgumentCaptor<AssistantMessageUpdateCommand> updateCaptor =
                ArgumentCaptor.forClass(AssistantMessageUpdateCommand.class);
        verify(sessionService).updateAssistantMessage(updateCaptor.capture());
        assertThat(updateCaptor.getValue().content()).isEqualTo("existing answer");
        assertThat(updateCaptor.getValue().safePartDrafts()).isEmpty();
        assertThat(updateCaptor.getValue().appendAnswerPart()).isFalse();
        assertThat(updateCaptor.getValue().metadataJson()).contains("\"status\":\"FAILED\"");
        verify(sessionService, never()).deleteAssistantPartsForRun(any(), any(), any());
        verify(executionRepository).markTerminal(running.id(), ChatRunExecutionStatus.FAILED);
    }

    private ChatRun asyncRun(Instant now) {
        return new ChatRun(
                "run1", "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
                "DOMAIN_AGENT", "skill1", "domain-agent", null, ChatRunMode.NEXT,
                null, "msg-user", "msg-assistant", 1L, 4L, null,
                now, null,
                DomainAgentAsyncTaskMetadata.runningOverlay(
                        "msg-assistant", now.plusSeconds(3600)),
                now, now);
    }
}
