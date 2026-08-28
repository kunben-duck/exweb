package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.RunAsyncRunningEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

class DomainAgentAsyncTaskApplicationServiceTest {
    @Test
    void commitsAsyncBoundaryWithAssistantAndInvalidatesLiveExecutionClaim() {
        DomainAgentProperties properties = new DomainAgentProperties();
        properties.setAsyncTaskEnabled(true);
        properties.setAsyncTaskMaxDuration(Duration.ofHours(24));
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRunExecutionRepository executionRepository = mock(ChatRunExecutionRepository.class);
        SessionApplicationService sessionService = mock(SessionApplicationService.class);
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        ChatInteractionApplicationService interactionService = mock(ChatInteractionApplicationService.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        ObjectMapper objectMapper = new ObjectMapper();
        DomainAgentAsyncTaskApplicationService service = new DomainAgentAsyncTaskApplicationService(
                properties, runRepository, executionRepository, sessionService,
                streamService, interactionService, idGenerator, objectMapper);
        Instant now = Instant.now();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatSession session = new ChatSession(
                "session1", "tenant1", "user1", "title", "ACTIVE", "web", now, now);
        ChatMessage userMessage = new ChatMessage(
                "msg-user", "tenant1", "user1", "session1", "user", "query", null, now);
        ChatMessage assistantMessage = new ChatMessage(
                "msg-assistant", "tenant1", "user1", "session1", "assistant", "partial", null, now);
        ChatRun running = new ChatRun(
                "run1", "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
                "DOMAIN_AGENT", "skill1", "domain-agent", null, ChatRunMode.NEXT,
                null, userMessage.id(), null, 1L, 3L, null, now, null,
                Map.of(), now, now);
        RunExecutionClaim claim = new RunExecutionClaim("run1", "instance1", 7L);
        AssistantAssembly assembly = new AssistantAssembly();
        assembly.observe(MessageDeltaEvent.of("run1", "session1", "partial"));
        RunEventPipelineContext context = new RunEventPipelineContext(
                user, session,
                new ChatRunMessagePlan(ChatRunMode.NEXT, userMessage.id(), userMessage, null),
                new AtomicReference<>(), new AtomicReference<>(), assembly,
                running.id(), claim, new AtomicReference<>(Map.of()), null, null, List.of());
        when(runRepository.findById(running.id())).thenReturn(Optional.of(running));
        when(idGenerator.newId(eq("msg"), any())).thenReturn("msg-assistant");
        when(streamService.appendWithExecutionGuard(any(RunAsyncRunningEvent.class), eq(claim)))
                .thenAnswer(invocation -> {
                    RunAsyncRunningEvent event = invocation.getArgument(0);
                    return new StoredChatEvent(
                            event.runId(), event.sessionId(), 4L, event.type(), event.createdAt(), event.payload());
                });
        when(sessionService.saveAssistantMessage(any())).thenReturn(assistantMessage);
        when(runRepository.transitionToAsyncWaiting(any(ChatRun.class), eq(claim)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(executionRepository.markAsyncWaiting(eq(claim), any())).thenReturn(true);

        DomainAgentAsyncTaskApplicationService.StartResult result = service.commitStarted(
                RunAsyncRunningEvent.of(running.id(), session.id(), "background"), context);

        assertThat(result.event().type()).isEqualTo("run.async_running");
        assertThat(result.event().payload())
                .containsEntry("status", "ASYNC_RUNNING")
                .containsEntry("assistantMessageId", "msg-assistant")
                .containsEntry("messageReady", true);
        assertThat(result.run().assistantMessageId()).isEqualTo("msg-assistant");
        assertThat(DomainAgentAsyncTaskMetadata.isAsyncRunning(result.run())).isTrue();
        verify(sessionService).saveAssistantMessage(any(AssistantMessageSaveCommand.class));
        verify(executionRepository).markAsyncWaiting(eq(claim), eq(result.expiresAt()));
    }
}
