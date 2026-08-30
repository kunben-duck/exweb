/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.agent.MessageSkillContext;
import com.huawei.it.ex.one.application.integration.agent.RuntimeInteractionDispatchState;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventStore;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.it.ex.one.application.service.runtime.DeferredDomainAgentBinding;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ChatSessionPage;
import com.huawei.it.ex.one.domain.chat.ErrorEvent;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.chat.RunCancelledEvent;
import com.huawei.it.ex.one.domain.chat.RunCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RunWaitingUserEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ChatRunTerminalCommitServiceTest {
    @Test
    void completedTransactionCommitsRouteSwitchEventAndDeferredBindingTogether() {
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        SessionApplicationService sessionService = mock(SessionApplicationService.class);
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        RuntimeBindingRepository bindingRepository = mock(RuntimeBindingRepository.class);
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
                streamService, sessionService, runRepository, leaseService,
                bindingRepository, null, Duration.ofMinutes(10));
        Instant now = Instant.now();
        RuntimeBinding previous = new RuntimeBinding(
                "binding-a", "tenant1", "user1", "session1", "domain-agent",
                "leaf-a", "session1", RuntimeBindingStatus.ACTIVE, "run-a", now.plusSeconds(60),
                now, now, Map.of("domainAgentId", "skill-a"));
        RuntimeBinding candidate = new RuntimeBinding(
                "binding-b", "tenant1", "user1", "session1", "domain-agent",
                "leaf-a", "session1", RuntimeBindingStatus.ACTIVE, "run-b", now.plusSeconds(60),
                now, now, Map.of("domainAgentId", "skill-b"));
        AtomicReference<DeferredDomainAgentBinding> deferredRef = new AtomicReference<>(
                new DeferredDomainAgentBinding(candidate, null));
        RuntimeEvent applied = RuntimeEvent.metadata("run-b", "session1", Map.of(
                "source", "chatservice",
                "sourceType", "route-switch-applied",
                "targetProvider", "domain-agent",
                "targetId", "skill-b"));
        AtomicReference<PendingRouteSwitchAppliedEvent> pendingAppliedRef = new AtomicReference<>(
                new PendingRouteSwitchAppliedEvent(applied, candidate.id()));
        AtomicReference<RuntimeBinding> bindingRef = new AtomicReference<>(candidate);
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatSession session = new ChatSession(
                "session1", "tenant1", "user1", "test", "ACTIVE", "web", now, now);
        ChatMessage userMessage = new ChatMessage(
                "msg-user", "tenant1", "user1", "session1", "user", "question", null, now);
        AssistantAssembly assistant = new AssistantAssembly();
        assistant.messageSkill().replace("skill-b");
        RunExecutionClaim claim = new RunExecutionClaim("run-b", "instance-test", 2L);
        ChatRunTerminalCommitService.TerminalCommitContext context =
                new ChatRunTerminalCommitService.TerminalCommitContext(
                        user, session,
                        new ChatRunMessagePlan(ChatRunMode.NEXT, userMessage.id(), userMessage, null),
                        bindingRef, assistant, "run-b", claim, null,
                        RuntimeInteractionDispatchState.untracked(), deferredRef, pendingAppliedRef);
        ChatEvent event = RunCompletedEvent.of("run-b", "session1", Map.of("status", "COMPLETED"));
        ChatEvent storedApplied = new RuntimeEvent(
                "run-b", "session1", 8L, now, "runtime.metadata", applied.payload());
        ChatEvent stored = new RunCompletedEvent("run-b", "session1", 9L, now, event.payload());
        ChatMessage savedAssistant = mock(ChatMessage.class);
        when(savedAssistant.id()).thenReturn("msg-assistant");
        when(runRepository.tryFenceOwnerTerminalCommit(any())).thenReturn(true);
        when(runRepository.findById("run-b")).thenReturn(Optional.empty());
        when(streamService.appendBatchWithExecutionGuard(eq(List.of(applied, event)), eq(claim)))
                .thenReturn(List.of(storedApplied, stored));
        when(sessionService.saveAssistantMessage(any())).thenReturn(savedAssistant);
        when(bindingRepository.findById("binding-b")).thenReturn(Optional.empty());
        when(bindingRepository.findActiveBySession("tenant1", "user1", "session1"))
                .thenReturn(List.of(previous));
        when(bindingRepository.cancelActiveForRun("binding-a", "run-a")).thenReturn(true);
        when(bindingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ChatRunTerminalCommitService.CommitResult result = service.commitCompleted(
                new ChatRunTerminalCommitService.CompletedCommitCommand(
                        event, context,
                        new ChatRunTerminalCommitService.MessageTarget(true, "msg-assistant")));

        ArgumentCaptor<RuntimeBinding> bindingCaptor = ArgumentCaptor.forClass(RuntimeBinding.class);
        verify(bindingRepository).cancelActiveForRun("binding-a", "run-a");
        verify(bindingRepository).save(bindingCaptor.capture());
        RuntimeBinding activated = bindingCaptor.getValue();
        assertThat(activated.id()).isEqualTo("binding-b");
        assertThat(activated.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
        assertThat(activated.lastRunId()).isEqualTo("run-b");
        assertThat(activated.leafMessageId()).isEqualTo("msg-assistant");
        assertThat(result.binding()).isEqualTo(activated);
        assertThat(result.replaceBindingCache()).isTrue();
        assertThat(result.precedingEvents()).containsExactly(storedApplied);
        verify(streamService, never()).appendWithExecutionGuard(any(), any());
        ArgumentCaptor<AssistantMessageSaveCommand> assistantCaptor =
                ArgumentCaptor.forClass(AssistantMessageSaveCommand.class);
        verify(sessionService).saveAssistantMessage(assistantCaptor.capture());
        assertThat(assistantCaptor.getValue().partDrafts())
                .filteredOn(part -> "route-switch-applied".equals(part.sourceType()))
                .singleElement()
                .satisfies(part -> assertThat(part.partType()).isEqualTo("METADATA"));
    }

    @Test
    void pendingRouteSwitchEventMustMatchDeferredBindingBeforeAnyEventWrite() {
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        SessionApplicationService sessionService = mock(SessionApplicationService.class);
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        RuntimeBindingRepository bindingRepository = mock(RuntimeBindingRepository.class);
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
                streamService, sessionService, runRepository, leaseService,
                bindingRepository, null, Duration.ZERO);
        Instant now = Instant.now();
        RuntimeBinding candidate = new RuntimeBinding(
                "binding-b", "tenant1", "user1", "session1", "domain-agent",
                null, "session1", RuntimeBindingStatus.ACTIVE, "run-b", null,
                now, now, Map.of("domainAgentId", "skill-b"));
        RuntimeEvent applied = RuntimeEvent.metadata("run-b", "session1", Map.of(
                "source", "chatservice",
                "sourceType", "route-switch-applied"));
        ChatRunTerminalCommitService.TerminalCommitContext context =
                new ChatRunTerminalCommitService.TerminalCommitContext(
                        new UserContext("tenant1", "user1", "User One"),
                        new ChatSession(
                                "session1", "tenant1", "user1", "test", "ACTIVE", "web", now, now),
                        null,
                        new AtomicReference<>(candidate),
                        new AssistantAssembly(),
                        "run-b",
                        new RunExecutionClaim("run-b", "instance-test", 2L),
                        null,
                        RuntimeInteractionDispatchState.untracked(),
                        new AtomicReference<>(new DeferredDomainAgentBinding(candidate, null)),
                        new AtomicReference<>(new PendingRouteSwitchAppliedEvent(applied, "binding-other")));
        when(runRepository.tryFenceOwnerTerminalCommit(any())).thenReturn(true);

        assertThatThrownBy(() -> service.commitCompleted(
                new ChatRunTerminalCommitService.CompletedCommitCommand(
                        RunCompletedEvent.of("run-b", "session1"),
                        context,
                        new ChatRunTerminalCommitService.MessageTarget(true, "msg-assistant"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");

        verify(streamService, never()).appendWithExecutionGuard(any(), any());
        verify(streamService, never()).appendBatchWithExecutionGuard(any(), any());
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void failedTerminalDoesNotPersistDeferredBinding() {
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        RuntimeBindingRepository bindingRepository = mock(RuntimeBindingRepository.class);
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
                streamService, null, runRepository, leaseService, bindingRepository, null, Duration.ZERO);
        Instant now = Instant.now();
        RuntimeBinding candidate = new RuntimeBinding(
                "binding-b", "tenant1", "user1", "session1", "domain-agent",
                null, "session1", RuntimeBindingStatus.ACTIVE, "run-b", null,
                now, now, Map.of("domainAgentId", "skill-b"));
        AtomicReference<DeferredDomainAgentBinding> deferredRef = new AtomicReference<>(
                new DeferredDomainAgentBinding(candidate, null));
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatSession session = new ChatSession(
                "session1", "tenant1", "user1", "test", "ACTIVE", "web", now, now);
        RunExecutionClaim claim = new RunExecutionClaim("run-b", "instance-test", 2L);
        ChatRunTerminalCommitService.TerminalCommitContext context =
                new ChatRunTerminalCommitService.TerminalCommitContext(
                        user, session, null, new AtomicReference<>(candidate), new AssistantAssembly(),
                        "run-b", claim, null, RuntimeInteractionDispatchState.untracked(), deferredRef);
        ChatEvent event = ErrorEvent.of("run-b", "session1", "FAILED", "failed");
        when(runRepository.tryFenceOwnerTerminalCommit(any())).thenReturn(true);
        when(streamService.appendWithExecutionGuard(event, claim)).thenReturn(event);

        ChatRunTerminalCommitService.CommitResult result = service.commitTerminalOnly(
                new ChatRunTerminalCommitService.TerminalOnlyCommitCommand(event, context));

        assertThat(result.binding()).isNull();
        assertThat(result.replaceBindingCache()).isFalse();
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void completedAssistantUsesInMemoryFinalRunSkillWithoutExtraRunLookup() {
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        SessionApplicationService sessionService = mock(SessionApplicationService.class);
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
                streamService, sessionService, runRepository, leaseService, null, null, Duration.ZERO);
        Instant now = Instant.now();
        ChatRun run = new ChatRun(
                "run1", "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
                "DOMAIN_AGENT", "skill-a", "domain-agent", null, ChatRunMode.NEXT,
                null, "msg-user", null, null, null, null, now, null,
                Map.of(MessageSkillContext.RUN_METADATA_KEY, "stale-db-skill"), now, now);
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatSession session = new ChatSession(
                "session1", "tenant1", "user1", "test", "ACTIVE", "web", now, now);
        ChatMessage userMessage = new ChatMessage(
                "msg-user", "tenant1", "user1", "session1", "user", "question", null, now);
        AssistantAssembly assistant = new AssistantAssembly();
        assistant.observe(MessageSnapshotEvent.of("run1", "session1", "answer"));
        assistant.messageSkill().replace("skill-a");
        assistant.messageSkill().replace("skill-b");
        RunExecutionClaim claim = new RunExecutionClaim("run1", "instance-test", 1L);
        ChatRunTerminalCommitService.TerminalCommitContext context =
                new ChatRunTerminalCommitService.TerminalCommitContext(
                        user, session,
                        new ChatRunMessagePlan(ChatRunMode.NEXT, userMessage.id(), userMessage, null),
                        new AtomicReference<>(), assistant, "run1", claim, null);
        ChatEvent event = RunCompletedEvent.of("run1", "session1", Map.of("status", "COMPLETED"));
        ChatEvent stored = new RunCompletedEvent(
                "run1", "session1", 9L, now, event.payload());
        ChatMessage savedAssistant = mock(ChatMessage.class);
        when(savedAssistant.id()).thenReturn("msg-assistant");
        when(runRepository.tryFenceOwnerTerminalCommit(any(ChatRunRepository.OwnerTerminalFence.class)))
                .thenReturn(true);
        when(runRepository.findById("run1")).thenReturn(Optional.of(run));
        when(runRepository.save(any(ChatRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(streamService.appendWithExecutionGuard(event, claim)).thenReturn(stored);
        when(sessionService.saveAssistantMessage(any(AssistantMessageSaveCommand.class)))
                .thenReturn(savedAssistant);

        service.commitCompleted(new ChatRunTerminalCommitService.CompletedCommitCommand(
                event, context, new ChatRunTerminalCommitService.MessageTarget(true, "msg-assistant")));

        ArgumentCaptor<AssistantMessageSaveCommand> commandCaptor =
                ArgumentCaptor.forClass(AssistantMessageSaveCommand.class);
        verify(sessionService).saveAssistantMessage(commandCaptor.capture());
        assertThat(commandCaptor.getValue().metadataJson())
                .contains("\"skillId\":\"skill-b\"")
                .doesNotContain("skill-a")
                .doesNotContain("stale-db-skill");
        // bind assistant及既有observeRun各读取一次；SkillId投影不再增加第三次查询。
        verify(runRepository, times(2)).findById("run1");
    }

    @Test
    void reusedAssistantReceivesOnlyCurrentRunFinalSkill() {
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        SessionApplicationService sessionService = mock(SessionApplicationService.class);
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        ChatInteractionApplicationService interactionService = mock(ChatInteractionApplicationService.class);
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
                streamService, sessionService, runRepository, leaseService, null, interactionService, Duration.ZERO);
        Instant now = Instant.now();
        ChatRun run = new ChatRun(
                "run-b", "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
                "DOMAIN_AGENT", "skill-new", "domain-agent", null, ChatRunMode.CONTINUE_INTERACTION,
                null, "msg-user", null, null, null, null, now, null, Map.of(), now, now);
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatSession session = new ChatSession(
                "session1", "tenant1", "user1", "test", "ACTIVE", "web", now, now);
        ChatMessage userMessage = new ChatMessage(
                "msg-user", "tenant1", "user1", "session1", "user", "answer", null, now);
        ChatInteractionRequest interaction = mock(ChatInteractionRequest.class);
        when(interaction.interactionType()).thenReturn(ChatInteractionType.AGENT_CLARIFICATION);
        when(interaction.assistantMessageId()).thenReturn("msg-assistant");
        AssistantAssembly assistant = new AssistantAssembly();
        assistant.observe(MessageSnapshotEvent.of("run-b", "session1", "continued answer"));
        assistant.messageSkill().replace("skill-new");
        RunExecutionClaim claim = new RunExecutionClaim("run-b", "instance-test", 2L);
        ChatRunTerminalCommitService.TerminalCommitContext context =
                new ChatRunTerminalCommitService.TerminalCommitContext(
                        user, session,
                        new ChatRunMessagePlan(ChatRunMode.CONTINUE_INTERACTION,
                                userMessage.id(), userMessage, null),
                        new AtomicReference<>(), assistant, "run-b", claim, interaction);
        ChatEvent event = RunCompletedEvent.of("run-b", "session1", Map.of("status", "COMPLETED"));
        ChatEvent stored = new RunCompletedEvent(
                "run-b", "session1", 10L, now, event.payload());
        ChatMessage savedAssistant = mock(ChatMessage.class);
        when(savedAssistant.id()).thenReturn("msg-assistant");
        when(runRepository.tryFenceOwnerTerminalCommit(any(ChatRunRepository.OwnerTerminalFence.class)))
                .thenReturn(true);
        when(runRepository.findById("run-b")).thenReturn(Optional.of(run));
        when(runRepository.save(any(ChatRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(streamService.appendWithExecutionGuard(event, claim)).thenReturn(stored);
        when(sessionService.updateAssistantMessage(any(AssistantMessageUpdateCommand.class)))
                .thenReturn(savedAssistant);

        service.commitCompleted(new ChatRunTerminalCommitService.CompletedCommitCommand(
                event, context, new ChatRunTerminalCommitService.MessageTarget(true, "msg-assistant")));

        ArgumentCaptor<AssistantMessageUpdateCommand> commandCaptor =
                ArgumentCaptor.forClass(AssistantMessageUpdateCommand.class);
        verify(sessionService).updateAssistantMessage(commandCaptor.capture());
        assertThat(commandCaptor.getValue().runId()).isEqualTo("run-b");
        assertThat(commandCaptor.getValue().metadataJson())
                .contains("\"skillId\":\"skill-new\"");
    }

    @Test
    void domainAgentRefusalCommitHasBoundedTransactionTimeout() throws Exception {
        Transactional transactional = ChatRunTerminalCommitService.class
                .getMethod("commitDomainAgentRefusal",
                        ChatRunTerminalCommitService.DomainAgentRefusalCommitCommand.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString())
                .isEqualTo("${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}");
    }

    @Test
    void domainAgentRefusalCommitAppendsEventBeforeCancellingBinding() {
        List<String> operations = new ArrayList<>();
        RecordingEventStore eventStore = new RecordingEventStore(operations);
        RecordingRuntimeBindingRepository bindingRepository = new RecordingRuntimeBindingRepository(operations);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(
                eventStore, null, null, null, null, null, null);
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
                streamService, null, null, null, bindingRepository, null, Duration.ZERO);
        Instant now = Instant.now();
        RuntimeBinding binding = new RuntimeBinding(
                "binding1", "tenant1", "user1", "session1", "domain-agent", "msg-user",
                "session1", RuntimeBindingStatus.ACTIVE, "run1", null, now, now,
                Map.of("domainAgentId", "agent-a", "routeSource", "intent-agent"));
        RuntimeEvent refusal = RuntimeEvent.metadata("run1", "session1", Map.of(
                "sourceType", "agent.refusal",
                "code", "FN-EX-CAHT-BIZ-DAG-001"));

        ChatRunTerminalCommitService.CommitResult result = service.commitDomainAgentRefusal(
                new ChatRunTerminalCommitService.DomainAgentRefusalCommitCommand(
                        refusal, new RunExecutionClaim("run1", "instance-test", 1L), binding,
                        "FN-EX-CAHT-BIZ-DAG-001"));

        assertThat(operations).containsExactly("event", "binding");
        assertThat(result.event().sequence()).isEqualTo(1L);
        assertThat(result.binding().status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
        assertThat(result.binding().metadata())
                .containsEntry("lastRejectCode", "FN-EX-CAHT-BIZ-DAG-001");
    }

    @Test
    void domainAgentRefusalGuardRejectionDoesNotUpdateBinding() {
        List<String> operations = new ArrayList<>();
        RecordingRuntimeBindingRepository bindingRepository = new RecordingRuntimeBindingRepository(operations);
        ChatEventStore rejectingStore = new RecordingEventStore(operations) {
            @Override
            public ChatEvent appendWithExecutionGuard(ChatEvent event, RunExecutionClaim claim) {
                operations.add("event-rejected");
                throw new ChatEventAppendRejectedException("fencing rejected refusal");
            }
        };
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
                new ChatStreamApplicationService(rejectingStore, null, null, null, null, null, null),
                null, null, null, bindingRepository, null, Duration.ZERO);
        Instant now = Instant.now();
        RuntimeBinding binding = new RuntimeBinding(
                "binding1", "tenant1", "user1", "session1", "domain-agent", "msg-user",
                "session1", RuntimeBindingStatus.ACTIVE, "run1", null, now, now,
                Map.of("domainAgentId", "agent-a", "routeSource", "intent-agent"));

        assertThatThrownBy(() -> service.commitDomainAgentRefusal(
                new ChatRunTerminalCommitService.DomainAgentRefusalCommitCommand(
                        RuntimeEvent.metadata("run1", "session1", Map.of(
                                "sourceType", "agent.refusal",
                                "code", "FN-EX-CAHT-BIZ-DAG-001")),
                        new RunExecutionClaim("run1", "instance-test", 1L), binding,
                        "FN-EX-CAHT-BIZ-DAG-001")))
                .isInstanceOf(ChatEventAppendRejectedException.class);

        assertThat(operations).containsExactly("event-rejected");
    }

    @Test
    void externalTerminalCommitHasBoundedTransactionTimeout() throws Exception {
        Transactional transactional = ChatRunTerminalCommitService.class
                .getMethod("commitExternalTerminal",
                        ChatRunTerminalCommitService.ExternalTerminalCommitCommand.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString())
                .isEqualTo("${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}");
    }

    @Test
    void externalTerminalUsesLatestClaimedRunSkillForPartialAssistant() {
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        SessionApplicationService sessionService = mock(SessionApplicationService.class);
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
                streamService, sessionService, runRepository, leaseService, null, null, Duration.ZERO);
        Instant now = Instant.now();
        ChatRun staleRun = new ChatRun(
                "run1", "tenant1", "user1", "session1", ChatRunStatus.CANCELLING,
                "DOMAIN_AGENT", "skill-stale", "domain-agent", null, ChatRunMode.NEXT,
                null, "msg-user", null, null, null, "USER_STOP", now, null,
                Map.of(MessageSkillContext.RUN_METADATA_KEY, "skill-stale"), now, now);
        ChatRun latestRun = staleRun.withMetadata(Map.of(
                MessageSkillContext.RUN_METADATA_KEY, "skill-latest"));
        ChatSession session = new ChatSession(
                "session1", "tenant1", "user1", "test", "ACTIVE", "web", now, now);
        AssistantMessageSaveCommand partial = new AssistantMessageSaveCommand(
                "tenant1", "user1", session, "partial", "run1", "msg-user", null,
                List.of(), "{\"partial\":true,\"skillId\":\"skill-old\"}", "msg-assistant");
        ChatEvent event = RunCancelledEvent.of(
                "run1", "session1", "USER_STOP", true, "msg-assistant");
        ChatEvent stored = new RunCancelledEvent(
                "run1", "session1", 12L, now, event.payload());
        ChatMessage savedAssistant = mock(ChatMessage.class);
        when(savedAssistant.id()).thenReturn("msg-assistant");
        when(runRepository.tryClaimExternalTerminal(any(ChatRunRepository.ExternalTerminalClaim.class)))
                .thenReturn(true);
        when(runRepository.findById("run1")).thenReturn(Optional.of(latestRun));
        when(runRepository.save(any(ChatRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.finalizeExternalTerminal(any(ChatRunRepository.ExternalTerminalFinalize.class)))
                .thenReturn(latestRun.cancelled(12L));
        when(sessionService.saveAssistantMessage(any(AssistantMessageSaveCommand.class)))
                .thenReturn(savedAssistant);
        when(streamService.appendWithoutPublish(event)).thenReturn(stored);

        ChatRunTerminalCommitService.ExternalTerminalCommitResult result = service.commitExternalTerminal(
                ChatRunTerminalCommitService.ExternalTerminalCommitCommand.stop(event, staleRun, partial));

        ArgumentCaptor<AssistantMessageSaveCommand> commandCaptor =
                ArgumentCaptor.forClass(AssistantMessageSaveCommand.class);
        verify(sessionService).saveAssistantMessage(commandCaptor.capture());
        assertThat(commandCaptor.getValue().metadataJson())
                .contains("\"skillId\":\"skill-latest\"")
                .doesNotContain("skill-stale")
                .doesNotContain("skill-old");
        assertThat(result.committed()).isTrue();
    }

    @Test
    void stopClosesAsyncWaitingRunWithoutReplacingExistingAssistantContent() {
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        SessionApplicationService sessionService = mock(SessionApplicationService.class);
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
                streamService, sessionService, runRepository, leaseService, null, null, Duration.ZERO);
        Instant now = Instant.now();
        ChatRun stopping = new ChatRun(
                "run-async", "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
                "DOMAIN_AGENT", "skill-a", "domain-agent", null, ChatRunMode.NEXT,
                null, "msg-user", "msg-assistant", 1L, 4L, null, now, null,
                DomainAgentAsyncTaskMetadata.runningOverlay(
                        "msg-assistant", now.plus(Duration.ofHours(1))), now, now)
                .cancelling("USER_STOP");
        ChatRun claimed = stopping.cancelled(0L);
        ChatRun committed = claimed.withMetadataSnapshot(Map.of()).cancelled(12L);
        ChatSession session = new ChatSession(
                "session1", "tenant1", "user1", "test", "ACTIVE", "web", now, now);
        ChatMessage assistant = new ChatMessage(
                "msg-assistant", "tenant1", "user1", "session1",
                "msg-user", 2L, 1, 0, "assistant", "partial async answer", null,
                "run-async", "NORMAL", false, null, null, null, null,
                "{\"skillId\":\"skill-a\",\"domainAgentAsyncTask\":{\"status\":\"ASYNC_RUNNING\"}}",
                now);
        ChatEvent event = RunCancelledEvent.of(
                stopping.id(), stopping.sessionId(), "USER_STOP", true, assistant.id());
        ChatEvent stored = new RunCancelledEvent(
                stopping.id(), stopping.sessionId(), 12L, now, event.payload());
        when(sessionService.requireSessionForInternalUpdate(
                stopping.tenantId(), stopping.userId(), stopping.sessionId())).thenReturn(session);
        when(sessionService.requireAssistantForInternalUpdate(session, assistant.id())).thenReturn(assistant);
        when(runRepository.tryClaimExternalTerminal(any(ChatRunRepository.ExternalTerminalClaim.class)))
                .thenReturn(true);
        when(runRepository.findById(stopping.id())).thenReturn(Optional.of(claimed));
        when(runRepository.save(any(ChatRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(streamService.appendWithoutPublish(event)).thenReturn(stored);
        when(runRepository.finalizeExternalTerminal(any())).thenReturn(committed);

        ChatRunTerminalCommitService.ExternalTerminalCommitResult result = service.commitExternalTerminal(
                ChatRunTerminalCommitService.ExternalTerminalCommitCommand.stop(event, stopping, null));

        ArgumentCaptor<AssistantMessageUpdateCommand> assistantCaptor =
                ArgumentCaptor.forClass(AssistantMessageUpdateCommand.class);
        verify(sessionService).updateAssistantMessage(assistantCaptor.capture());
        assertThat(assistantCaptor.getValue().content()).isEqualTo("partial async answer");
        assertThat(assistantCaptor.getValue().safePartDrafts()).isEmpty();
        assertThat(assistantCaptor.getValue().appendAnswerPart()).isFalse();
        assertThat(assistantCaptor.getValue().metadataJson())
                .contains("\"skillId\":\"skill-a\"")
                .contains("\"status\":\"CANCELLED\"");
        ArgumentCaptor<ChatRun> savedRunCaptor = ArgumentCaptor.forClass(ChatRun.class);
        verify(runRepository).save(savedRunCaptor.capture());
        assertThat(savedRunCaptor.getValue().metadata())
                .doesNotContainKey(DomainAgentAsyncTaskMetadata.RUN_METADATA_KEY);
        verify(leaseService).markTerminal(stopping.id(), ChatRunExecutionStatus.CANCELLED);
        assertThat(result.committed()).isTrue();
        assertThat(result.run().status()).isEqualTo(ChatRunStatus.CANCELLED);
    }

    @Test
    void ownerTerminalFenceRejectsEveryTerminalBeforeAnySideEffect() {
        List<String> operations = new ArrayList<>();
        RejectingRunRepository runRepository = new RejectingRunRepository(operations);
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
                null,
                recordingSessionService(operations),
                runRepository,
                null,
                null,
                null,
                Duration.ofDays(3)
        );
        ChatRunTerminalCommitService.TerminalCommitContext context = terminalContext();

        assertThatThrownBy(() -> service.commitCompleted(
                new ChatRunTerminalCommitService.CompletedCommitCommand(
                        RunCompletedEvent.of("run1", "session1", Map.of("status", "COMPLETED")),
                        context,
                        null
                )))
                .isInstanceOf(ChatEventAppendRejectedException.class);
        assertThat(operations).containsExactly("session-lock", "run-fence");

        operations.clear();
        assertThatThrownBy(() -> service.commitWaitingUser(
                new ChatRunTerminalCommitService.WaitingUserCommitCommand(
                        new RunWaitingUserEvent("run1", "session1", 0L, Instant.now(), Map.of()),
                        context,
                        null,
                        null
                )))
                .isInstanceOf(ChatEventAppendRejectedException.class);
        assertThat(operations).containsExactly("session-lock", "run-fence");

        operations.clear();
        assertThatThrownBy(() -> service.commitTerminalOnly(
                new ChatRunTerminalCommitService.TerminalOnlyCommitCommand(
                        ErrorEvent.of("run1", "session1", "TEST_FAILURE", "test failure"),
                        context
                )))
                .isInstanceOf(ChatEventAppendRejectedException.class);
        assertThat(operations).containsExactly("run-fence");

        org.assertj.core.api.Assertions.assertThat(runRepository.fenceAttempts).hasValue(3);
    }

    @Test
    void externalTerminalLoserDoesNotPersistPreparedPartialAssistant() {
        List<String> operations = new ArrayList<>();
        RejectingRunRepository runRepository = new RejectingRunRepository(operations);
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
                null, recordingSessionService(operations), runRepository, null, null, null, Duration.ofDays(3));
        Instant now = Instant.now();
        ChatRun run = new ChatRun("run1", "tenant1", "user1", "session1", ChatRunStatus.CANCELLING,
                "AGENT_RUNTIME", null, "relay", null, ChatRunMode.NEXT, null, "msg-user",
                null, null, null, "USER_STOP", now, null, Map.of(), now, now);
        ChatSession session = new ChatSession("session1", "tenant1", "user1",
                "test", "ACTIVE", "web", now, now);
        AssistantMessageSaveCommand partial = new AssistantMessageSaveCommand(
                "tenant1", "user1", session, "partial", "run1", "msg-user", null,
                List.of(), "{\"partial\":true}", "msg-assistant");

        ChatRunTerminalCommitService.ExternalTerminalCommitResult result = service.commitExternalTerminal(
                ChatRunTerminalCommitService.ExternalTerminalCommitCommand.stop(
                        RunCancelledEvent.of("run1", "session1", "USER_STOP", true, "msg-assistant"),
                        run,
                        partial));

        assertThat(result.committed()).isFalse();
        assertThat(result.event()).isNull();
        assertThat(operations).containsExactly("session-lock", "run-cas");
    }

    @Test
    void externalTerminalWithoutPartialAssistantDoesNotLockSession() {
        List<String> operations = new ArrayList<>();
        RejectingRunRepository runRepository = new RejectingRunRepository(operations);
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
                null, recordingSessionService(operations), runRepository, null, null, null, Duration.ofDays(3));
        Instant now = Instant.now();
        ChatRun run = new ChatRun("run1", "tenant1", "user1", "session1", ChatRunStatus.CANCELLING,
                "AGENT_RUNTIME", null, "relay", null, ChatRunMode.NEXT, null, "msg-user",
                null, null, null, "USER_STOP", now, null, Map.of(), now, now);

        ChatRunTerminalCommitService.ExternalTerminalCommitResult result = service.commitExternalTerminal(
                ChatRunTerminalCommitService.ExternalTerminalCommitCommand.stop(
                        RunCancelledEvent.of("run1", "session1", "USER_STOP", false, null),
                        run,
                        null));

        assertThat(result.committed()).isFalse();
        assertThat(operations).containsExactly("run-cas");
    }

    @Test
    void watchdogClosingUserStoppedContinuationKeepsInteractionCancelled() {
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        ChatInteractionApplicationService interactionService = mock(ChatInteractionApplicationService.class);
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
                streamService, null, runRepository, leaseService, null, interactionService, Duration.ZERO);
        Instant now = Instant.now();
        ChatRun run = new ChatRun(
                "run1", "tenant1", "user1", "session1", ChatRunStatus.CANCELLING,
                "AGENT_RUNTIME", null, "relay", null, ChatRunMode.CONTINUE_INTERACTION,
                null, "msg-user", null, null, null, "USER_STOP", now, null,
                Map.of("interactionId", "interaction1"), now, now);
        ChatRun committedRun = run.cancelled(5L);
        ChatEvent event = RunCancelledEvent.of(run.id(), run.sessionId(), "USER_STOP", false, null);
        ChatEvent stored = new RunCancelledEvent(
                run.id(), run.sessionId(), 5L, now, event.payload());
        when(runRepository.tryClaimExternalTerminal(any(ChatRunRepository.ExternalTerminalClaim.class)))
                .thenReturn(true);
        when(runRepository.findById("run1")).thenReturn(Optional.of(run));
        when(streamService.appendWithoutPublish(event)).thenReturn(stored);
        when(runRepository.finalizeExternalTerminal(any(ChatRunRepository.ExternalTerminalFinalize.class)))
                .thenReturn(committedRun);

        ChatRunTerminalCommitService.ExternalTerminalCommitResult result = service.commitExternalTerminal(
                new ChatRunTerminalCommitService.ExternalTerminalCommitCommand(
                        event,
                        run,
                        ChatRunRepository.ExternalTerminalGuard.RECOVERY,
                        "instance-recovery",
                        2L,
                        null,
                        null,
                        null));

        assertThat(result.committed()).isTrue();
        verify(interactionService).cancelRespondingForRun(
                eq("tenant1"), eq("user1"), eq("interaction1"), eq("run1"), any(Instant.class));
        verify(interactionService, never()).markWaitingForRun(any(), any(), any(), any());
    }

    @Test
    void relayFailureBeforeResponseDispatchRestoresWaitingInteraction() {
        TerminalTestFixture fixture = terminalTestFixture();
        RuntimeInteractionDispatchState dispatchState = RuntimeInteractionDispatchState.tracked();
        dispatchState.markBindingRestored();

        ChatRunTerminalCommitService.CommitResult result = fixture.service().commitTerminalOnly(
                new ChatRunTerminalCommitService.TerminalOnlyCommitCommand(
                        fixture.failureEvent(), fixture.context(dispatchState)));

        verify(fixture.interactionService()).markWaiting(fixture.interaction());
        verify(fixture.interactionService(), never()).cancelRespondingForRun(
                any(), any(), any(), any(), any());
        verify(fixture.bindingRepository(), never()).cancelActiveForRun(any(), any());
        assertThat(result.binding().status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
    }

    @Test
    void relayFailureAfterResponseDispatchCancelsInteractionAndBinding() {
        TerminalTestFixture fixture = terminalTestFixture();
        RuntimeInteractionDispatchState dispatchState = RuntimeInteractionDispatchState.tracked();
        dispatchState.markResponseDispatched();
        when(fixture.bindingRepository().cancelActiveForRun("binding1", "run1")).thenReturn(true);

        ChatRunTerminalCommitService.CommitResult result = fixture.service().commitTerminalOnly(
                new ChatRunTerminalCommitService.TerminalOnlyCommitCommand(
                        fixture.failureEvent(), fixture.context(dispatchState)));

        verify(fixture.interactionService()).cancelRespondingForRun(
                eq("tenant1"), eq("user1"), eq("interaction1"), eq("run1"), any(Instant.class));
        verify(fixture.interactionService(), never()).markWaiting(any());
        verify(fixture.bindingRepository()).cancelActiveForRun("binding1", "run1");
        assertThat(result.binding().status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
    }

    @Test
    void unavailableRelaySessionCancelsInteractionEvenBeforeResponseDispatch() {
        TerminalTestFixture fixture = terminalTestFixture();
        RuntimeInteractionDispatchState dispatchState = RuntimeInteractionDispatchState.tracked();
        ErrorEvent unavailable = ErrorEvent.of(
                "run1", "session1", "RUNTIME_SESSION_UNAVAILABLE", "runtime session unavailable");
        ErrorEvent stored = new ErrorEvent(
                "run1", "session1", 9L, Instant.now(), unavailable.code(), unavailable.message(), unavailable.payload());
        when(fixture.streamService().appendWithExecutionGuard(unavailable, fixture.claim())).thenReturn(stored);
        RuntimeBinding sourceBinding = fixture.binding().withRun("run-a", null);
        when(fixture.bindingRepository().findById("binding1")).thenReturn(Optional.of(sourceBinding));
        when(fixture.bindingRepository().cancelActiveForInteraction(sourceBinding, "run-a", "run1"))
                .thenReturn(true);

        ChatRunTerminalCommitService.CommitResult result = fixture.service().commitTerminalOnly(
                new ChatRunTerminalCommitService.TerminalOnlyCommitCommand(
                        unavailable, fixture.context(dispatchState, null)));

        verify(fixture.interactionService()).cancelRespondingForRun(
                eq("tenant1"), eq("user1"), eq("interaction1"), eq("run1"), any(Instant.class));
        verify(fixture.interactionService(), never()).markWaiting(any());
        verify(fixture.bindingRepository()).cancelActiveForInteraction(sourceBinding, "run-a", "run1");
        assertThat(result.binding().status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
    }

    @Test
    void failedBindingRestoreCancelsInteractionInsteadOfExposingRetry() {
        TerminalTestFixture fixture = terminalTestFixture();
        RuntimeInteractionDispatchState dispatchState = RuntimeInteractionDispatchState.tracked();
        dispatchState.markBindingRestoreFailed();
        when(fixture.bindingRepository().cancelActiveForRun("binding1", "run1")).thenReturn(true);

        fixture.service().commitTerminalOnly(new ChatRunTerminalCommitService.TerminalOnlyCommitCommand(
                fixture.failureEvent(), fixture.context(dispatchState)));

        verify(fixture.interactionService()).cancelRespondingForRun(
                eq("tenant1"), eq("user1"), eq("interaction1"), eq("run1"), any(Instant.class));
        verify(fixture.interactionService(), never()).markWaiting(any());
    }

    @Test
    void interactionPartialAssistantWithoutOriginalIdCannotFallbackToInsert() {
        Instant now = Instant.now();
        ChatRun run = new ChatRun("run1", "tenant1", "user1", "session1", ChatRunStatus.CANCELLING,
                "AGENT_RUNTIME", null, "relay", null, ChatRunMode.NEXT, null, "msg-user",
                null, null, null, "USER_STOP", now, null,
                Map.of("interactionId", "interaction1"), now, now);
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
                null, recordingSessionService(new ArrayList<>()), new SingleRunRepository(run),
                null, null, null, Duration.ofDays(3));
        ChatSession session = new ChatSession("session1", "tenant1", "user1",
                "test", "ACTIVE", "web", now, now);
        AssistantMessageSaveCommand partial = new AssistantMessageSaveCommand(
                "tenant1", "user1", session, "partial", "run1", "msg-user", null,
                List.of(), "{\"partial\":true}", "msg-new");

        assertThatThrownBy(() -> service.commitExternalTerminal(
                ChatRunTerminalCommitService.ExternalTerminalCommitCommand.stop(
                        RunCancelledEvent.of("run1", "session1", "USER_STOP", true, "msg-new"),
                        run,
                        partial)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须复用原 assistantMessageId");
    }

    private ChatRunTerminalCommitService.TerminalCommitContext terminalContext() {
        Instant now = Instant.now();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatSession session = new ChatSession("session1", user.tenantId(), user.ownerUserId(),
                "test", "ACTIVE", "web", now, now);
        return new ChatRunTerminalCommitService.TerminalCommitContext(
                user,
                session,
                null,
                new AtomicReference<>(),
                new AssistantAssembly(),
                "run1",
                new RunExecutionClaim("run1", "instance-test", 1L),
                null
        );
    }

    private TerminalTestFixture terminalTestFixture() {
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        ChatRunRepository runRepository = mock(ChatRunRepository.class);
        ChatRunLeaseApplicationService leaseService = mock(ChatRunLeaseApplicationService.class);
        RuntimeBindingRepository bindingRepository = mock(RuntimeBindingRepository.class);
        ChatInteractionApplicationService interactionService = mock(ChatInteractionApplicationService.class);
        ChatRunTerminalCommitService service = new ChatRunTerminalCommitService(
                streamService, null, runRepository, leaseService, bindingRepository, interactionService,
                Duration.ZERO);
        RunExecutionClaim claim = new RunExecutionClaim("run1", "instance-test", 1L);
        ChatInteractionRequest interaction = respondingRelayInteraction();
        RuntimeBinding binding = activeRelayBinding();
        ErrorEvent failure = ErrorEvent.of("run1", "session1", "RELAY_ERROR", "relay failed");
        ErrorEvent stored = new ErrorEvent(
                "run1", "session1", 8L, Instant.now(), failure.code(), failure.message(), failure.payload());
        when(runRepository.tryFenceOwnerTerminalCommit(any(ChatRunRepository.OwnerTerminalFence.class)))
                .thenReturn(true);
        when(runRepository.findById("run1")).thenReturn(Optional.empty());
        when(streamService.appendWithExecutionGuard(failure, claim)).thenReturn(stored);
        return new TerminalTestFixture(
                service, streamService, bindingRepository, interactionService, interaction, binding, claim, failure);
    }

    private ChatInteractionRequest respondingRelayInteraction() {
        Instant now = Instant.now();
        return new ChatInteractionRequest(
                "interaction1", "tenant1", "user1", "session1", "run-a", "run1",
                "msg-user", "msg-assistant", "relay", "binding1", "relay-session-1",
                "approval-1", ChatInteractionType.AGENT_CLARIFICATION, ChatInteractionStatus.RESPONDING,
                Map.of(), Map.of(), now.plus(Duration.ofHours(1)), null, null, now, now);
    }

    private RuntimeBinding activeRelayBinding() {
        Instant now = Instant.now();
        return new RuntimeBinding(
                "binding1", "tenant1", "user1", "session1", "relay", "msg-assistant",
                "relay-session-1", RuntimeBindingStatus.ACTIVE, "run1", null, now, now,
                Map.of("runtimeSessionEstablished", true));
    }

    private record TerminalTestFixture(
            ChatRunTerminalCommitService service,
            ChatStreamApplicationService streamService,
            RuntimeBindingRepository bindingRepository,
            ChatInteractionApplicationService interactionService,
            ChatInteractionRequest interaction,
            RuntimeBinding binding,
            RunExecutionClaim claim,
            ErrorEvent failureEvent
    ) {
        private ChatRunTerminalCommitService.TerminalCommitContext context(
                RuntimeInteractionDispatchState dispatchState) {
            return context(dispatchState, binding);
        }

        private ChatRunTerminalCommitService.TerminalCommitContext context(
                RuntimeInteractionDispatchState dispatchState,
                RuntimeBinding currentBinding) {
            UserContext user = new UserContext("tenant1", "user1", "User One");
            ChatSession session = new ChatSession(
                    "session1", "tenant1", "user1", "test", "ACTIVE", "web", Instant.now(), Instant.now());
            return new ChatRunTerminalCommitService.TerminalCommitContext(
                    user, session, null, new AtomicReference<>(currentBinding), new AssistantAssembly(), "run1",
                    claim, interaction, dispatchState);
        }
    }

    private SessionApplicationService recordingSessionService(List<String> operations) {
        return new SessionApplicationService(
                new RecordingSessionRepository(operations), null, null, new PermissionChecker());
    }

    private static final class RejectingRunRepository implements ChatRunRepository {
        private final AtomicInteger fenceAttempts = new AtomicInteger();
        private final List<String> operations;

        private RejectingRunRepository(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public boolean tryFenceOwnerTerminalCommit(OwnerTerminalFence fence) {
            fenceAttempts.incrementAndGet();
            operations.add("run-fence");
            return false;
        }

        @Override
        public boolean tryClaimExternalTerminal(ExternalTerminalClaim claim) {
            operations.add("run-cas");
            return false;
        }

        @Override
        public ChatRun save(ChatRun run) {
            throw new AssertionError("fence rejection must happen before saving run");
        }

        @Override
        public Optional<ChatRun> findById(String runId) {
            return Optional.empty();
        }

        @Override
        public Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId) {
            return Optional.empty();
        }

        @Override
        public Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId) {
            return Optional.empty();
        }
    }

    private static final class RecordingSessionRepository implements SessionRepository {
        private final List<String> operations;

        private RecordingSessionRepository(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public void lockForMessageMutation(String tenantId, String userId, String sessionId) {
            operations.add("session-lock");
        }

        @Override
        public Optional<ChatSession> findById(String sessionId) {
            return Optional.empty();
        }

        @Override
        public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
            return Optional.empty();
        }

        @Override
        public List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId) {
            return List.of();
        }

        @Override
        public ChatSessionPage pageByTenantIdAndUserId(String tenantId, String userId, String cursor, int limit) {
            return new ChatSessionPage(List.of(), null);
        }

        @Override
        public ChatSession save(ChatSession session) {
            return session;
        }
    }

    private static final class SingleRunRepository implements ChatRunRepository {
        private final ChatRun run;

        private SingleRunRepository(ChatRun run) {
            this.run = run;
        }

        @Override
        public ChatRun save(ChatRun value) {
            return value;
        }

        @Override
        public Optional<ChatRun> findById(String runId) {
            return run.id().equals(runId) ? Optional.of(run) : Optional.empty();
        }

        @Override
        public Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId) {
            return findById(runId);
        }

        @Override
        public Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId) {
            return Optional.of(run);
        }
    }

    private static class RecordingEventStore implements ChatEventStore {
        private final List<String> operations;

        private RecordingEventStore(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public ChatEvent append(ChatEvent event) {
            throw new AssertionError("refusal commit must use execution guard");
        }

        @Override
        public ChatEvent appendWithExecutionGuard(ChatEvent event, RunExecutionClaim claim) {
            operations.add("event");
            return new RuntimeEvent(event.runId(), event.sessionId(), 1L, Instant.now(),
                    event.type(), event.payload());
        }

        @Override
        public List<ChatEvent> findByOwnerAndSessionAfterSeq(String tenantId, String userId,
                                                             String sessionId, long afterSeq) {
            return List.of();
        }

        @Override
        public List<ChatEvent> findByOwnerAndRunAfterSeq(String tenantId, String userId, String sessionId,
                                                         String runId, long afterSeq) {
            return List.of();
        }

        @Override
        public long findLatestSeqByOwnerAndSession(String tenantId, String userId, String sessionId) {
            return 0;
        }
    }

    private static final class RecordingRuntimeBindingRepository implements RuntimeBindingRepository {
        private final List<String> operations;

        private RecordingRuntimeBindingRepository(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public Optional<RuntimeBinding> findById(String bindingId) {
            return Optional.empty();
        }

        @Override
        public Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId,
                                                   String provider) {
            return Optional.empty();
        }

        @Override
        public RuntimeBinding save(RuntimeBinding binding) {
            operations.add("binding");
            return binding;
        }
    }
}
