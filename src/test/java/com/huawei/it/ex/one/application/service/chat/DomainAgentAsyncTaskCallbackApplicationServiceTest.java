/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
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
import com.huawei.it.ex.one.domain.chat.DomainAgentAsyncCallbackNotReadyException;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class DomainAgentAsyncTaskCallbackApplicationServiceTest {
    @Test
    void commitsCompletionNotificationAndPublishesCommittedEvents() {
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
        when(bindingService.completeDomainAgentAfterAsyncRun(
                running.tenantId(), running.userId(), running.sessionId(), running.id(), "msg-assistant"))
                .thenReturn(true);
        DomainAgentAsyncTaskCallbackApplicationService service = service(
                properties, commitService, runRepository, runService, streamService, bindingService);
        try {
            DomainAgentAsyncTaskCallbackApplicationService.CallbackResult result = service.callback(
                    new DomainAgentAsyncTaskCallbackCommand(
                            running.id(), "COMPLETED", "ignored failure detail")).block();

            assertThat(result).isNotNull();
            assertThat(result.accepted()).isTrue();
            ArgumentCaptor<DomainAgentAsyncTaskCallbackCommitService.PreparedCallback> callbackCaptor =
                    ArgumentCaptor.forClass(DomainAgentAsyncTaskCallbackCommitService.PreparedCallback.class);
            verify(commitService).commit(callbackCaptor.capture());
            assertThat(callbackCaptor.getValue().runId()).isEqualTo(running.id());
            assertThat(callbackCaptor.getValue().completed()).isTrue();
            assertThat(callbackCaptor.getValue().error()).isNull();
            verify(runService).synchronizeCommittedRunCache(completed);
            InOrder completionOrder = inOrder(bindingService, streamService);
            completionOrder.verify(bindingService).completeDomainAgentAfterAsyncRun(
                    running.tenantId(), running.userId(), running.sessionId(), running.id(), "msg-assistant");
            completionOrder.verify(streamService).publishPersisted(published);
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
                            completed.id(), "COMPLETED", null)).block();

            assertThat(result).isNotNull();
            assertThat(result.accepted()).isFalse();
            verify(commitService, never()).commit(any());
        } finally {
            service.closeScheduler();
        }
    }

    @Test
    void asksCallerToRetryWhenAsyncWaitingStateIsNotCommittedYet() {
        DomainAgentProperties properties = enabledProperties();
        DomainAgentAsyncTaskCallbackCommitService commitService =
                mock(DomainAgentAsyncTaskCallbackCommitService.class);
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRun notReady = asyncRun().withMetadataSnapshot(Map.of());
        when(runRepository.findById(notReady.id())).thenReturn(Optional.of(notReady));
        DomainAgentAsyncTaskCallbackApplicationService service = service(
                properties, commitService, runRepository,
                mock(ChatRunApplicationService.class), mock(ChatStreamApplicationService.class),
                mock(RuntimeBindingApplicationService.class));
        try {
            assertThatThrownBy(() -> service.callback(new DomainAgentAsyncTaskCallbackCommand(
                    notReady.id(), "COMPLETED", null)).block())
                    .isInstanceOf(DomainAgentAsyncCallbackNotReadyException.class);
            verify(commitService, never()).commit(any());
        } finally {
            service.closeScheduler();
        }
    }

    @Test
    void rejectsExpiredAsyncCallbackWithoutClaimingTerminal() {
        DomainAgentProperties properties = enabledProperties();
        DomainAgentAsyncTaskCallbackCommitService commitService =
                mock(DomainAgentAsyncTaskCallbackCommitService.class);
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRun expired = asyncRun().withMetadataSnapshot(DomainAgentAsyncTaskMetadata.runningOverlay(
                "msg-assistant", Instant.now().minusSeconds(1)));
        when(runRepository.findById(expired.id())).thenReturn(Optional.of(expired));
        DomainAgentAsyncTaskCallbackApplicationService service = service(
                properties, commitService, runRepository,
                mock(ChatRunApplicationService.class), mock(ChatStreamApplicationService.class),
                mock(RuntimeBindingApplicationService.class));
        try {
            DomainAgentAsyncTaskCallbackApplicationService.CallbackResult result = service.callback(
                    new DomainAgentAsyncTaskCallbackCommand(
                            expired.id(), "COMPLETED", null)).block();

            assertThat(result).isNotNull();
            assertThat(result.accepted()).isFalse();
            verify(commitService, never()).commit(any());
        } finally {
            service.closeScheduler();
        }
    }

    @Test
    void truncatesFailedErrorToUnicodeSafeLimitAndStillCommits() {
        DomainAgentProperties properties = enabledProperties();
        DomainAgentAsyncTaskCallbackCommitService commitService =
                mock(DomainAgentAsyncTaskCallbackCommitService.class);
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRun running = asyncRun();
        ChatRun failed = running.failed(9L);
        when(runRepository.findById(running.id())).thenReturn(Optional.of(running));
        when(commitService.commit(any())).thenReturn(
                new DomainAgentAsyncTaskCallbackCommitService.CommitResult(
                        true, failed, "msg-assistant", List.of()));
        DomainAgentAsyncTaskCallbackApplicationService service = service(
                properties, commitService, runRepository,
                mock(ChatRunApplicationService.class), mock(ChatStreamApplicationService.class),
                mock(RuntimeBindingApplicationService.class));
        try {
            String error = "错".repeat(1023) + "😀😀";

            DomainAgentAsyncTaskCallbackApplicationService.CallbackResult result = service.callback(
                    new DomainAgentAsyncTaskCallbackCommand(running.id(), "FAILED", error)).block();

            assertThat(result).isNotNull();
            assertThat(result.accepted()).isTrue();
            ArgumentCaptor<DomainAgentAsyncTaskCallbackCommitService.PreparedCallback> callbackCaptor =
                    ArgumentCaptor.forClass(DomainAgentAsyncTaskCallbackCommitService.PreparedCallback.class);
            verify(commitService).commit(callbackCaptor.capture());
            String normalized = callbackCaptor.getValue().error();
            assertThat(normalized.codePointCount(0, normalized.length())).isEqualTo(1024);
            assertThat(normalized).endsWith("😀").doesNotEndWith("😀😀");
        } finally {
            service.closeScheduler();
        }
    }

    @Test
    void normalizesBlankErrorToNull() {
        assertThat(new DomainAgentAsyncTaskCallbackCommand("run1", "FAILED", " \n ").error()).isNull();
        assertThat(new DomainAgentAsyncTaskCallbackCommand("run1", "FAILED", "  failed  ").error())
                .isEqualTo("failed");
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
                commitService,
                runRepository,
                runService,
                streamService,
                bindingService);
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
