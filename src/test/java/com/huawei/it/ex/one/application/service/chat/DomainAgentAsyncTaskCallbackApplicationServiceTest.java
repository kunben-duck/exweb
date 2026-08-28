package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class DomainAgentAsyncTaskCallbackApplicationServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizesCallbackFramesAndPublishesCommittedEvents() throws Exception {
        DomainAgentProperties properties = enabledProperties();
        DomainAgentAsyncTaskCallbackCommitService commitService =
                mock(DomainAgentAsyncTaskCallbackCommitService.class);
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        RuntimeBindingApplicationService bindingService = mock(RuntimeBindingApplicationService.class);
        ChatRun running = asyncRun();
        ChatRun completed = running.completed(9L);
        StoredChatEvent published = new StoredChatEvent(
                running.id(), running.sessionId(), 9L, "run.completed", Instant.now(), Map.of());
        when(runRepository.findById(running.id())).thenReturn(Optional.of(running));
        when(commitService.commit(any())).thenReturn(
                new DomainAgentAsyncTaskCallbackCommitService.CommitResult(
                        true, completed, "msg-assistant",
                        List.of(new DomainAgentAsyncTaskCallbackCommitService.PublishedEvent(published, true))));
        when(bindingService.findActiveDomainAgentBySession(
                running.tenantId(), running.userId(), running.sessionId())).thenReturn(Optional.empty());
        DomainAgentAsyncTaskCallbackApplicationService service = service(
                properties, commitService, runRepository, runService, streamService, bindingService);
        try {
            JsonNode frame = objectMapper.readTree("{\"content\":\"async result\",\"endFlag\":true}");

            DomainAgentAsyncTaskCallbackApplicationService.CallbackResult result = service.callback(
                    new DomainAgentAsyncTaskCallbackCommand(
                            running.id(), "COMPLETED", "APPEND", List.of(frame), null)).block();

            assertThat(result).isNotNull();
            assertThat(result.accepted()).isTrue();
            ArgumentCaptor<DomainAgentAsyncTaskCallbackCommitService.PreparedCallback> callbackCaptor =
                    ArgumentCaptor.forClass(DomainAgentAsyncTaskCallbackCommitService.PreparedCallback.class);
            verify(commitService).commit(callbackCaptor.capture());
            assertThat(callbackCaptor.getValue().businessEvents())
                    .extracting(event -> event.type())
                    .contains("message.delta")
                    .doesNotContain("message.completed");
            verify(runService).synchronizeCommittedRunCache(completed);
            verify(streamService).publishPersisted(published);
        } finally {
            service.closeScheduler();
        }
    }

    @Test
    void returnsNotAcceptedForDuplicateOrLateCallback() {
        DomainAgentProperties properties = enabledProperties();
        DomainAgentAsyncTaskCallbackCommitService commitService =
                mock(DomainAgentAsyncTaskCallbackCommitService.class);
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRun completed = asyncRun().completed(8L);
        when(runRepository.findById(completed.id())).thenReturn(Optional.of(completed));
        DomainAgentAsyncTaskCallbackApplicationService service = service(
                properties, commitService, runRepository,
                mock(ChatRunApplicationService.class), mock(ChatStreamApplicationService.class),
                mock(RuntimeBindingApplicationService.class));
        try {
            DomainAgentAsyncTaskCallbackApplicationService.CallbackResult result = service.callback(
                    new DomainAgentAsyncTaskCallbackCommand(
                            completed.id(), "COMPLETED", null, List.of(), null)).block();

            assertThat(result).isNotNull();
            assertThat(result.accepted()).isFalse();
            verify(commitService, never()).commit(any());
        } finally {
            service.closeScheduler();
        }
    }

    @Test
    void rejectsNestedAsyncStartBeforeReadingRun() throws Exception {
        DomainAgentProperties properties = enabledProperties();
        DomainAgentAsyncTaskCallbackCommitService commitService =
                mock(DomainAgentAsyncTaskCallbackCommitService.class);
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        DomainAgentAsyncTaskCallbackApplicationService service = service(
                properties, commitService, runRepository,
                mock(ChatRunApplicationService.class), mock(ChatStreamApplicationService.class),
                mock(RuntimeBindingApplicationService.class));
        try {
            JsonNode frame = objectMapper.readTree("{\"type\":\"agent.async_started\"}");

            assertThatThrownBy(() -> service.callback(new DomainAgentAsyncTaskCallbackCommand(
                    "run1", "COMPLETED", "APPEND", List.of(frame), null)).block())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("DomainAgent异步回调不能再次启动异步任务");
            verify(runRepository, never()).findById(any());
            verify(commitService, never()).commit(any());
        } finally {
            service.closeScheduler();
        }
    }

    private DomainAgentAsyncTaskCallbackApplicationService service(
            DomainAgentProperties properties,
            DomainAgentAsyncTaskCallbackCommitService commitService,
            ChatRunRepository runRepository,
            ChatRunApplicationService runService,
            ChatStreamApplicationService streamService,
            RuntimeBindingApplicationService bindingService) {
        return new DomainAgentAsyncTaskCallbackApplicationService(
                properties,
                new DomainAgentResponseNormalizer(objectMapper, properties),
                commitService,
                runRepository,
                runService,
                streamService,
                bindingService,
                objectMapper);
    }

    private DomainAgentProperties enabledProperties() {
        DomainAgentProperties properties = new DomainAgentProperties();
        properties.setAsyncTaskEnabled(true);
        properties.setAsyncTaskCallbackMaxConcurrency(2);
        return properties;
    }

    private ChatRun asyncRun() {
        Instant now = Instant.now();
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
