/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.application.integration.notification.UserNotificationPublisher;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.application.service.observation.ObservationTestFixture;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ErrorEvent;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.RunCancelledEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;
import com.huawei.it.ex.one.domain.notification.UserNotification;
import com.huawei.it.ex.one.domain.notification.UserNotificationRecipient;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

class DomainAgentAsyncTaskCallbackCommitServiceTest {
    @ParameterizedTest
    @CsvSource({"APPEND,false", "REPLACE,false", "EMPTY,false", "FAILED,false",
            "APPEND,true", "REPLACE,true", "EMPTY,true", "FAILED,true"})
    void deletedSessionRejectsEveryCallbackWithoutChangingBusinessState(String mode, boolean noStore) {
        StoredFixture fixture = new StoredFixture("DELETED", noStore);
        ChatRun before = fixture.run.get();

        var result = fixture.transaction(() -> fixture.service.commit(fixture.callback(mode)));

        assertThat(result.accepted()).isFalse();
        assertThat(result.events()).isEmpty();
        assertThat(fixture.run.get()).isSameAs(before);
        assertThat(fixture.execution.get()).isEqualTo(ChatRunExecutionStatus.ASYNC_WAITING);
        assertThat(fixture.message.get()).isSameAs(fixture.base.assistant);
        verifyNoInteractions(fixture.messages, fixture.base.streamService,
                fixture.base.notifications, fixture.base.executionRepository);
        verify(fixture.base.runRepository, never()).tryClaimExternalTerminal(any());
        verify(fixture.base.runRepository, never()).save(any());
        verify(fixture.base.runRepository, never()).finalizeExternalTerminal(any());
        verify(fixture.base.sessionRepository, never()).advanceLatestMessageSeq(any(), any(), any(), any(Long.class));
        verify(fixture.base.sessionRepository).findByTenantIdAndUserIdAndId("tenant1", "user1", "session1");
        verify(fixture.base.sessionRepository).lockAndFindForMessageMutation("tenant1", "user1", "session1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deletionAndCallbackSerializeOnLatestSessionWithoutWritingAfterDeletion(boolean deleteFirst) throws Exception {
        StoredFixture fixture = new StoredFixture("ACTIVE", false);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        fixture.coordinateLock(firstLocked, releaseFirst, secondAttempted);
        Supplier<Boolean> delete = () -> fixture.transaction(() -> {
            assertThat(fixture.sessions.deleteSession(
                    new UserContext("tenant1", "user1", "user1"), "session1").status()).isEqualTo("DELETED");
            return false;
        });
        Supplier<Boolean> callback = () -> fixture.transaction(
                () -> fixture.service.commit(fixture.callback("REPLACE")).accepted());

        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var first = executor.submit(() -> (deleteFirst ? delete : callback).get());
                await(firstLocked);
                var second = executor.submit(() -> (deleteFirst ? callback : delete).get());
                await(secondAttempted);
                assertThat(second.isDone()).isFalse();
                assertThat(fixture.session.get().status()).isEqualTo("ACTIVE");
                releaseFirst.countDown();
                boolean accepted = deleteFirst ? second.get(10, TimeUnit.SECONDS) : first.get(10, TimeUnit.SECONDS);
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);

                assertThat(accepted).isEqualTo(!deleteFirst);
                assertThat(fixture.session.get().status()).isEqualTo("DELETED");
                if (deleteFirst) {
                    assertThat(fixture.message.get()).isSameAs(fixture.base.assistant);
                    assertThat(fixture.run.get().status()).isEqualTo(ChatRunStatus.RUNNING);
                    verifyNoInteractions(fixture.messages, fixture.base.streamService, fixture.base.notifications);
                    verify(fixture.base.runRepository, never()).tryClaimExternalTerminal(any());
                } else {
                    assertThat(fixture.message.get().content()).isEqualTo("replacement");
                    assertThat(fixture.run.get().status()).isEqualTo(ChatRunStatus.COMPLETED);
                    assertThat(fixture.execution.get()).isEqualTo(ChatRunExecutionStatus.COMPLETED);
                    var order = inOrder(fixture.base.sessionRepository, fixture.base.runRepository, fixture.messages);
                    order.verify(fixture.base.sessionRepository).lockAndFindForMessageMutation(
                            "tenant1", "user1", "session1");
                    order.verify(fixture.base.runRepository).tryClaimExternalTerminal(any());
                    order.verify(fixture.messages).updateAssistantAsyncResult(any(), any(), eq(true));
                    order.verify(fixture.base.sessionRepository).save(any());
                }
            } finally {
                releaseFirst.countDown();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,APPEND", "ACTIVE,REPLACE", "ACTIVE,EMPTY", "ACTIVE,FAILED",
            "ARCHIVED,APPEND", "ARCHIVED,REPLACE", "ARCHIVED,EMPTY", "ARCHIVED,FAILED"})
    void nonDeletedCallbacksUseLockedSnapshotAndKeepOriginalResults(String status, String mode) {
        StoredFixture fixture = new StoredFixture(status, false);
        when(fixture.base.sessionRepository.findByTenantIdAndUserIdAndId("tenant1", "user1", "session1"))
                .thenReturn(Optional.of(fixture.base.session));

        var result = fixture.transaction(() -> fixture.service.commit(fixture.callback(mode)));

        assertThat(result.accepted()).isTrue();
        assertThat(result.run().status()).isEqualTo("FAILED".equals(mode)
                ? ChatRunStatus.FAILED : ChatRunStatus.COMPLETED);
        String expected = switch (mode) {
            case "APPEND" -> "existing answerreplacement";
            case "REPLACE" -> "replacement";
            default -> "existing answer";
        };
        assertThat(fixture.message.get().content()).isEqualTo(expected);
        verify(fixture.sessions).requireAssistantForInternalUpdate(fixture.session.get(), "msg-assistant");
        verify(fixture.sessions).advanceLatestMessageSeq(any(), eq(fixture.session.get()), any(Long.class));
        assertThat(fixture.session.get().status()).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectedDeletedCallbackLeavesStopAndTimeoutAbleToFinish(boolean timeout) {
        StoredFixture fixture = new StoredFixture("DELETED", false);
        assertThat(fixture.transaction(() -> fixture.service.commit(fixture.callback("REPLACE")))
                .accepted()).isFalse();
        ChatRun source = fixture.run.get();
        source = timeout ? source.withMetadataSnapshot(DomainAgentAsyncTaskMetadata.runningOverlay(
                "msg-assistant", Instant.now().minusSeconds(1))) : source.cancelling("SESSION_DELETE");
        fixture.run.set(source);
        ChatRunLeaseApplicationService leases = mock(ChatRunLeaseApplicationService.class);
        doAnswer(invocation -> {
            fixture.execution.set(invocation.getArgument(1));
            return null;
        }).when(leases).markTerminal(any(), any());
        var terminal = new ChatRunTerminalCommitService(fixture.base.streamService, fixture.sessions,
                fixture.base.runRepository, leases, null, null, Duration.ZERO);
        ChatEvent event = timeout
                ? ErrorEvent.of(source.id(), source.sessionId(), "DOMAIN_AGENT_ASYNC_TIMEOUT", "timeout")
                : RunCancelledEvent.of(source.id(), source.sessionId(), "SESSION_DELETE", true, "msg-assistant");
        var command = timeout ? ChatRunTerminalCommitService.ExternalTerminalCommitCommand.asyncTimeout(event, source)
                : ChatRunTerminalCommitService.ExternalTerminalCommitCommand.stop(event, source, null);

        var result = fixture.transaction(() -> terminal.commitExternalTerminal(command));

        assertThat(result.committed()).isTrue();
        assertThat(fixture.run.get().status()).isEqualTo(timeout ? ChatRunStatus.FAILED : ChatRunStatus.CANCELLED);
        assertThat(fixture.execution.get()).isEqualTo(timeout
                ? ChatRunExecutionStatus.FAILED : ChatRunExecutionStatus.CANCELLED);
        assertThat(fixture.run.get().metadata()).doesNotContainKey(DomainAgentAsyncTaskMetadata.RUN_METADATA_KEY);
        assertThat(fixture.session.get().status()).isEqualTo("DELETED");
        assertThat(fixture.message.get()).isSameAs(fixture.base.assistant);
        verifyNoInteractions(fixture.messages);
    }

    @Test
    void missingSessionStillFailsBeforeAnyTerminalClaim() {
        Fixture fixture = new Fixture();
        fixture.prepare(ChatRunStatus.COMPLETED, 12L, 10L);
        when(fixture.sessionRepository.findByTenantIdAndUserIdAndId("tenant1", "user1", "session1"))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> fixture.service.commit(
                new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback("run1", true, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DomainAgent async callback session is unavailable");
        verify(fixture.runRepository, never()).tryClaimExternalTerminal(any());
        verifyNoInteractions(fixture.streamService, fixture.notifications);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPEND", "REPLACE", "EMPTY", "FAILED"})
    void asyncDurationIncludesBackgroundWaitAndReplacesExistingMetadataValue(String mode) throws Exception {
        Fixture fixture = new Fixture();
        boolean completed = !"FAILED".equals(mode);
        boolean resultProvided = "APPEND".equals(mode) || "REPLACE".equals(mode);
        fixture.prepare(completed ? ChatRunStatus.COMPLETED : ChatRunStatus.FAILED, 20L, 10L);
        var existing = fixture.assistant.withMetadataJson(new RunDuration(1L)
                .mergeMetadata(fixture.objectMapper, fixture.assistant.metadataJson()));
        when(fixture.sessionService.requireAssistantForInternalUpdate(fixture.session, existing.id())).thenReturn(existing);
        Instant callbackTime = fixture.running.startedAt().plusSeconds(1800);
        try (var clock = mockStatic(Instant.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(callbackTime);
            var result = fixture.service.commit(new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                    fixture.running.id(), completed, resultProvided ? mode : null, resultProvided,
                    resultProvided ? List.of(new MessageDeltaEvent("run1", "session1", 0L, callbackTime,
                            "result", Map.of("delta", "result"))) : List.of(), null));
            assertThat(result.events().getLast().event().payload()).containsEntry("runDurationMs", 1800000L);
            String metadata;
            if (resultProvided) {
                var capture = ArgumentCaptor.forClass(AsyncAssistantResultUpdateCommand.class);
                verify(fixture.sessionService).updateAssistantAsyncResult(capture.capture());
                metadata = capture.getValue().metadataJson();
            } else {
                var capture = ArgumentCaptor.forClass(String.class);
                verify(fixture.sessionService).updateAssistantMetadataForInternalUse(
                        eq(fixture.session), eq(existing), capture.capture());
                metadata = capture.getValue();
            }
            assertThat(fixture.objectMapper.readTree(metadata).get("runDurationMs").longValue()).isEqualTo(1800000L);
            assertThat(metadata).contains("\"skillId\":\"skill1\"");
            verify(fixture.runRepository, times(2)).findById("run1");
        }
    }

    @Test
    void completedCallbackPersistsNotificationAndUpdatesOnlyAssistantMetadata() throws Exception {
        Fixture fixture = new Fixture();
        fixture.prepare(ChatRunStatus.COMPLETED, 12L, 10L);

        DomainAgentAsyncTaskCallbackCommitService.CommitResult result = fixture.service.commit(
                new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                        fixture.running.id(), true, null));

        assertThat(result.accepted()).isTrue();
        verify(fixture.notifications).publish(new UserNotificationRecipient("tenant1", "user1"),
                new UserNotification("session.async.finished", Map.of(
                        "sessionId", "session1", "runId", "run1", "status", "COMPLETED")));
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
        Object duration = result.events().getLast().event().payload().get("runDurationMs");
        assertThat(duration).isInstanceOf(Long.class);
        assertThat(fixture.objectMapper.readTree(metadataCaptor.getValue()).get("runDurationMs").longValue())
                .isEqualTo((Long) duration);
        assertThat(result.events().subList(0, result.events().size() - 1))
                .allSatisfy(item -> assertThat(item.event().payload()).doesNotContainKey("runDurationMs"));
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
        verify(fixture.notifications).publish(new UserNotificationRecipient("tenant1", "user1"),
                new UserNotification("session.async.finished", Map.of(
                        "sessionId", "session1", "runId", "run1", "status", "FAILED")));
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
    void appendResultPersistsBusinessEventsAndAppendsExactContent() {
        Fixture fixture = new Fixture();
        fixture.prepare(ChatRunStatus.COMPLETED, 16L, 10L);
        List<ChatEvent> businessEvents = List.of(
                RuntimeEvent.thinking(fixture.running.id(), fixture.running.sessionId(), Map.of(
                        "source", "domain-agent", "sourceType", "content.think", "text", "work")),
                new MessageDeltaEvent(fixture.running.id(), fixture.running.sessionId(), 0L, Instant.now(),
                        "result", Map.of("delta", "result", "sourceType", "domain-agent-content")));

        DomainAgentAsyncTaskCallbackCommitService.CommitResult result = fixture.service.commit(
                new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                        fixture.running.id(), true, "APPEND", true, businessEvents, null));

        assertThat(result.events()).extracting(item -> item.event().type()).containsExactly(
                "run.async_result_started", "runtime.thinking", "message.delta",
                "run.async_finished", "message.completed", "run.completed");
        ArgumentCaptor<AsyncAssistantResultUpdateCommand> commandCaptor =
                ArgumentCaptor.forClass(AsyncAssistantResultUpdateCommand.class);
        verify(fixture.sessionService).updateAssistantAsyncResult(commandCaptor.capture());
        assertThat(commandCaptor.getValue().content()).isEqualTo(
                "existing answer" + AssistantAssembly.DOMAIN_AGENT_CONTENT_SEGMENT_MARKER + "result");
        assertThat(commandCaptor.getValue().resultContent()).isEqualTo(
                AssistantAssembly.DOMAIN_AGENT_CONTENT_SEGMENT_MARKER + "result");
        assertThat(commandCaptor.getValue().replaceCurrentRunParts()).isFalse();
    }

    @Test
    void replaceResultUsesOnlyCallbackContentAndReplacesCurrentRunParts() {
        Fixture fixture = new Fixture();
        fixture.prepare(ChatRunStatus.COMPLETED, 14L, 10L);
        List<ChatEvent> businessEvents = List.of(new MessageDeltaEvent(
                fixture.running.id(), fixture.running.sessionId(), 0L, Instant.now(),
                "replacement", Map.of("delta", "replacement", "sourceType", "domain-agent-content")));

        fixture.service.commit(new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                fixture.running.id(), true, "REPLACE", true, businessEvents, null));

        ArgumentCaptor<AsyncAssistantResultUpdateCommand> commandCaptor =
                ArgumentCaptor.forClass(AsyncAssistantResultUpdateCommand.class);
        verify(fixture.sessionService).updateAssistantAsyncResult(commandCaptor.capture());
        assertThat(commandCaptor.getValue().content()).isEqualTo("replacement");
        assertThat(commandCaptor.getValue().resultContent()).isEqualTo("replacement");
        assertThat(commandCaptor.getValue().replaceCurrentRunParts()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void replaceWithoutBusinessResultUpdatesOnlyAsyncMetadata(boolean heartbeat) throws Exception {
        Fixture fixture = new Fixture();
        fixture.prepare(ChatRunStatus.COMPLETED, 12L, 10L);
        ObjectMapper mapper = new ObjectMapper();
        DomainAgentResponseNormalizer normalizer = new DomainAgentResponseNormalizer(mapper);
        List<ChatEvent> events = heartbeat
                ? normalizer.normalizeCallbackFrame(fixture.running.id(), fixture.running.sessionId(),
                        mapper.readTree("{\"type\":\"heartbeat\"}"), normalizer.newStreamState())
                : List.of();

        DomainAgentAsyncTaskCallbackCommitService.CommitResult result = fixture.service.commit(
                new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                        fixture.running.id(), true, "REPLACE", !events.isEmpty(), events, null));

        assertThat(result.events()).extracting(item -> item.event().type()).containsExactly(
                "run.async_finished", "message.completed", "run.completed");
        verify(fixture.sessionService).updateAssistantMetadataForInternalUse(
                eq(fixture.session), eq(fixture.assistant), any());
        verify(fixture.sessionService, never()).updateAssistantAsyncResult(any());
    }

    @Test
    void disabledEventBatchingUsesSingleEventPersistencePath() {
        Fixture fixture = new Fixture(false);
        fixture.prepare(ChatRunStatus.COMPLETED, 15L, 10L);
        List<ChatEvent> businessEvents = List.of(new MessageDeltaEvent(
                fixture.running.id(), fixture.running.sessionId(), 0L, Instant.now(),
                "result", Map.of("delta", "result", "sourceType", "domain-agent-content")));

        DomainAgentAsyncTaskCallbackCommitService.CommitResult result = fixture.service.commit(
                new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                        fixture.running.id(), true, "APPEND", true, businessEvents, null));

        assertThat(result.events()).hasSize(5);
        verify(fixture.streamService, never()).appendBatchWithoutPublish(any());
        verify(fixture.streamService, times(5)).appendWithoutPublish(any(ChatEvent.class));
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

    @Test
    void lostTerminalClaimDoesNotNotify() {
        Fixture fixture = new Fixture();
        fixture.prepare(ChatRunStatus.COMPLETED, 12, 10);
        when(fixture.runRepository.tryClaimExternalTerminal(any())).thenReturn(false);
        assertThat(fixture.service.commit(new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                "run1", true, null)).accepted()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(fixture.notifications);
    }

    @Test
    void observationTerminalIsPublishedOnlyAfterWinningCommitNotRollbackOrCasLoss() {
        try (var observation = new com.huawei.it.ex.one.application.service.observation.ObservationTestFixture()) {
            Fixture fixture = new Fixture();
            fixture.prepare(ChatRunStatus.COMPLETED, 12, 10);
            fixture.service.setObservationSupport(observation.turns);
            observation.turns.begin(new com.huawei.it.ex.one.domain.auth.UserContext("tenant1", "user1", "user1"),
                    "run1", "NEXT", "session1");
            observation.turns.committed(observation.turns.decorate(fixture.running), null);
            com.huawei.it.ex.one.application.service.observation.ObservationTestFixture.await(
                    () -> observation.traces.size() == 2);
            assertThat(observation.traces.getLast().status()).isEqualTo("ASYNC_WAITING");
            var tx = new org.springframework.transaction.support.TransactionTemplate(observation.transactions);
            tx.executeWithoutResult(status -> {
                assertThat(fixture.service.commit(new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                        "run1", true, null)).accepted()).isTrue();
                assertThat(observation.traces).noneMatch(t -> "COMPLETED".equals(t.status()));
                status.setRollbackOnly();
            });
            org.mockito.Mockito.reset(fixture.streamService);
            fixture.prepare(ChatRunStatus.COMPLETED, 12, 10);
            when(fixture.runRepository.tryClaimExternalTerminal(any())).thenReturn(false);
            tx.executeWithoutResult(status -> assertThat(fixture.service.commit(
                    new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback("run1", true, null))
                    .accepted()).isFalse());
            assertThat(observation.traces).noneMatch(t -> "COMPLETED".equals(t.status()));
            org.mockito.Mockito.reset(fixture.streamService);
            fixture.prepare(ChatRunStatus.COMPLETED, 12, 10);
            tx.executeWithoutResult(status -> {
                assertThat(fixture.service.commit(new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                        "run1", true, null)).accepted()).isTrue();
                assertThat(observation.traces).noneMatch(t -> "COMPLETED".equals(t.status()));
            });
            com.huawei.it.ex.one.application.service.observation.ObservationTestFixture.await(
                    () -> observation.traces.stream().anyMatch(t -> "COMPLETED".equals(t.status())));
            assertThat(observation.traces.stream().filter(t -> "COMPLETED".equals(t.status()))).hasSize(1);
        }
    }

    // 真实业务服务配合受控仓储和本地事务同步；行锁由本地锁模拟，不替代 openGauss 并发验证。
    private static final class StoredFixture {
        private final Fixture base = new Fixture();
        private final AtomicReference<ChatRun> run = new AtomicReference<>(base.running);
        private final AtomicReference<ChatSession> session;
        private final AtomicReference<ChatMessage> message = new AtomicReference<>(base.assistant);
        private final AtomicReference<ChatRunExecutionStatus> execution =
                new AtomicReference<>(ChatRunExecutionStatus.ASYNC_WAITING);
        private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
        private final SessionApplicationService sessions;
        private final DomainAgentAsyncTaskCallbackCommitService service;
        private final TransactionTemplate transactions = new TransactionTemplate(new ObservationTestFixture.LocalTransactions());
        private final ReentrantLock lock = new ReentrantLock();

        private StoredFixture(String status, boolean noStore) {
            base.prepare(ChatRunStatus.COMPLETED, 20L, 10L);
            session = new AtomicReference<>(new ChatSession("session1", "tenant1", "user1",
                    "latest title", status, "web", base.now, base.now));
            if (noStore) {
                var metadata = new LinkedHashMap<>(run.get().metadata());
                metadata.putAll(new AgentDataPersistenceState("placeholder")
                        .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER).runMetadataOverlay());
                run.set(run.get().withMetadataSnapshot(metadata));
            }
            AtomicLong ids = new AtomicLong();
            sessions = spy(new SessionApplicationService(base.sessionRepository, messages,
                    (type, context) -> type + ids.incrementAndGet(), new PermissionChecker()));
            service = new DomainAgentAsyncTaskCallbackCommitService(base.runRepository, base.executionRepository,
                    base.sessionRepository, sessions, base.streamService, base.eventBatcher, base.objectMapper);
            service.setNotificationPublisher(base.notifications);
            when(base.runRepository.findById("run1")).thenAnswer(invocation -> Optional.of(run.get()));
            when(base.runRepository.save(any())).thenAnswer(invocation -> {
                run.set(invocation.getArgument(0));
                return run.get();
            });
            when(base.runRepository.tryClaimExternalTerminal(any())).thenAnswer(invocation -> {
                ChatRunRepository.ExternalTerminalClaim claim = invocation.getArgument(0);
                ChatRun current = run.get();
                if (execution.get() != ChatRunExecutionStatus.ASYNC_WAITING
                        || current.status() != (claim.terminalStatus() == ChatRunStatus.CANCELLED
                        ? ChatRunStatus.CANCELLING : ChatRunStatus.RUNNING)) {
                    return false;
                }
                if (claim.guard() == ChatRunRepository.ExternalTerminalGuard.ASYNC_CALLBACK
                        && DomainAgentAsyncTaskMetadata.expiresAt(current).isBefore(claim.finishedAt())) {
                    return false;
                }
                run.set(terminalRun(current, claim.terminalStatus(), 0L));
                return true;
            });
            when(base.runRepository.finalizeExternalTerminal(any())).thenAnswer(invocation -> {
                ChatRunRepository.ExternalTerminalFinalize finish = invocation.getArgument(0);
                run.set(terminalRun(run.get(), finish.terminalStatus(), finish.sequence()));
                return run.get();
            });
            doAnswer(invocation -> {
                execution.set(invocation.getArgument(1));
                return null;
            }).when(base.executionRepository).markTerminal(any(), any());
            when(base.sessionRepository.findByTenantIdAndUserIdAndId("tenant1", "user1", "session1"))
                    .thenAnswer(invocation -> Optional.of(session.get()));
            when(base.sessionRepository.save(any())).thenAnswer(invocation -> {
                session.set(invocation.getArgument(0));
                return session.get();
            });
            when(base.sessionRepository.lockAndFindForMessageMutation("tenant1", "user1", "session1"))
                    .thenAnswer(invocation -> session.get());
            when(messages.findByOwnerAndId("tenant1", "user1", "msg-assistant"))
                    .thenAnswer(invocation -> Optional.of(message.get()));
            when(messages.updateAssistantAsyncResult(any(), any(), any(Boolean.class))).thenAnswer(invocation -> {
                message.set(invocation.getArgument(1));
                return message.get();
            });
            when(messages.updateAssistantMetadata(any(), any())).thenAnswer(invocation -> {
                message.set(message.get().withMetadataJson(invocation.getArgument(1)));
                return message.get();
            });
        }

        private void coordinateLock(CountDownLatch firstLocked, CountDownLatch releaseFirst,
                                    CountDownLatch secondAttempted) {
            AtomicInteger attempts = new AtomicInteger();
            Runnable acquire = () -> {
                int attempt = attempts.incrementAndGet();
                if (attempt == 2) {
                    secondAttempted.countDown();
                }
                try {
                    assertThat(lock.tryLock(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int completionStatus) { lock.unlock(); }
                });
                if (attempt == 1) {
                    firstLocked.countDown();
                    await(releaseFirst);
                }
            };
            when(base.sessionRepository.lockAndFindForMessageMutation("tenant1", "user1", "session1"))
                    .thenAnswer(invocation -> {
                        acquire.run();
                        return session.get();
                    });
            doAnswer(invocation -> {
                acquire.run();
                return null;
            }).when(base.sessionRepository).lockForMessageMutation("tenant1", "user1", "session1");
        }

        private <T> T transaction(Supplier<T> action) {
            return transactions.execute(status -> action.get());
        }

        private DomainAgentAsyncTaskCallbackCommitService.PreparedCallback callback(String mode) {
            boolean result = "APPEND".equals(mode) || "REPLACE".equals(mode);
            return new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback("run1", !"FAILED".equals(mode),
                    result ? mode : null, result, result ? List.of(new MessageDeltaEvent("run1", "session1", 0L,
                    base.now, "replacement", Map.of("delta", "replacement", "sourceType", "domain-agent-content")))
                    : List.of(), null);
        }

        private static ChatRun terminalRun(ChatRun run, ChatRunStatus status, long sequence) {
            return switch (status) {
                case COMPLETED -> run.completed(sequence);
                case FAILED -> run.failed(sequence);
                case CANCELLED -> run.cancelled(sequence);
                default -> throw new AssertionError("unexpected terminal status");
            };
        }
    }

    private static final class Fixture {
        private final UserNotificationPublisher notifications = mock(UserNotificationPublisher.class);
        private final ChatRunRepository runRepository = mock(ChatRunRepository.class);
        private final ChatRunExecutionRepository executionRepository = mock(ChatRunExecutionRepository.class);
        private final SessionRepository sessionRepository = mock(SessionRepository.class);
        private final SessionApplicationService sessionService = mock(SessionApplicationService.class);
        private final ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        private final ChatEventBatcher eventBatcher;
        private final Instant now = Instant.now();
        private final ChatRun running = asyncRun(now);
        private final ChatSession session = new ChatSession(
                "session1", "tenant1", "user1", "title", "ACTIVE", "web", now, now);
        private final ChatMessage assistant = assistant(now);
        private final DomainAgentAsyncTaskCallbackCommitService service;

        private Fixture() {
            this(true);
        }

        private Fixture(boolean eventBatchEnabled) {
            ChatStreamProperties properties = new ChatStreamProperties();
            properties.setEventBatchEnabled(eventBatchEnabled);
            this.eventBatcher = new ChatEventBatcher(properties, objectMapper);
            this.service = new DomainAgentAsyncTaskCallbackCommitService(
                    runRepository, executionRepository, sessionRepository,
                    sessionService, streamService, eventBatcher, objectMapper);
            this.service.setNotificationPublisher(notifications);
            when(sessionService.lockAndReloadForMessageMutation("tenant1", "user1", session)).thenReturn(session);
        }

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
            when(streamService.appendWithoutPublish(any(ChatEvent.class))).thenAnswer(invocation -> {
                ChatEvent event = invocation.getArgument(0);
                return new StoredChatEvent(
                        event.runId(), event.sessionId(), sequence.getAndIncrement(),
                        event.type(), event.createdAt(), event.payload());
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
