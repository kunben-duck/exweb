package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.facade.ResolvedChatAttachments;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.CandidateDomainAgentSwitchCommand;
import com.huawei.it.ex.one.domain.chat.CandidateSwitchConflictException;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessageAttachment;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStopResult;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.document.UploadedDocument;

import reactor.core.publisher.Mono;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class CandidateDomainAgentSwitchApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    private final ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
    private final SessionApplicationService sessionService = mock(SessionApplicationService.class);
    private final ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
    private final DocumentFacade documentFacade = mock(DocumentFacade.class);
    private final ChatRunStopCoordinator stopCoordinator = mock(ChatRunStopCoordinator.class);
    private final ChatRunStartCoordinator startCoordinator = mock(ChatRunStartCoordinator.class);
    private final ChatRunExecutionCoordinator executionCoordinator = mock(ChatRunExecutionCoordinator.class);
    private final CandidateDomainAgentSwitchApplicationService service =
            new CandidateDomainAgentSwitchApplicationService(
                    runService,
                    sessionService,
                    messageRepository,
                    documentFacade,
                    stopCoordinator,
                    startCoordinator,
                    executionCoordinator);

    private UserContext user;
    private ChatSession session;
    private ChatMessage userMessage;

    @BeforeEach
    void setUp() {
        user = new UserContext("tenant1", "user1", "User One");
        session = session("msg_assistant_a");
        userMessage = userMessage();
        when(sessionService.getSession(user, "session1")).thenReturn(session);
        when(messageRepository.findByOwnerAndId("tenant1", "user1", "msg_user"))
                .thenReturn(Optional.of(userMessage));
        when(runService.findActiveRun(user, "session1")).thenReturn(Optional.empty());
    }

    @Test
    void stopsRunningSourceBeforeStartingReplacementFromSameUserMessage() {
        ChatRun running = run(ChatRunStatus.RUNNING, null);
        ChatRun cancelled = run(ChatRunStatus.CANCELLED, "msg_assistant_a");
        when(sessionService.getSession(user, "session1"))
                .thenReturn(session("msg_user"), session("msg_assistant_a"));
        when(runService.requireOwnedRun(user, "run_a")).thenReturn(running, cancelled);
        when(stopCoordinator.stopRun(
                eq(user), any(TraceContext.class), eq("run_a"), eq("CANDIDATE_SWITCH"),
                any(RuntimeForwardHeaders.class)))
                .thenReturn(Mono.just(new ChatRunStopResult(
                        "run_a", "session1", ChatRunStatus.CANCELLED, 12L, NOW,
                        true, "msg_assistant_a", "msg_assistant_a")));
        ChatRunStartResult expected = new ChatRunStartResult(
                "run_b", "session1", 13L, NOW, "chat-run-run_b");
        when(startCoordinator.startStandard(
                eq(user), any(TraceContext.class), any(ChatCommand.class), any()))
                .thenReturn(Mono.just(expected));

        ChatRunStartResult actual = service.switchDomainAgent(
                user,
                new TraceContext("trace1"),
                command(),
                RuntimeForwardHeaders.empty()).block();

        assertThat(actual).isEqualTo(expected);
        ArgumentCaptor<ChatCommand> commandCaptor = ArgumentCaptor.forClass(ChatCommand.class);
        verify(startCoordinator).startStandard(
                eq(user), eq(new TraceContext("trace1")), commandCaptor.capture(), any());
        ChatCommand replacement = commandCaptor.getValue();
        assertThat(replacement.message()).isEqualTo("原始问题");
        assertThat(replacement.targetType()).isEqualTo("DOMAIN_AGENT");
        assertThat(replacement.targetId()).isEqualTo("skill_b");
        assertThat(replacement.runMode()).isEqualTo(ChatRunMode.REGENERATE_ASSISTANT);
        assertThat(replacement.regeneratedMessageId()).isEqualTo("msg_assistant_a");
        assertThat(replacement.metadata()).containsEntry("bizKey", "current-run-only");
        assertThat(SelectedIntentContext.intentName(replacement.metadata())).isEqualTo("候选技能B");
        InOrder order = inOrder(stopCoordinator, startCoordinator);
        order.verify(stopCoordinator).stopRun(
                eq(user), any(TraceContext.class), eq("run_a"), eq("CANDIDATE_SWITCH"),
                any(RuntimeForwardHeaders.class));
        order.verify(startCoordinator).startStandard(
                eq(user), any(TraceContext.class), any(ChatCommand.class), any());
    }

    @Test
    void revalidatesPersistedAttachmentsBeforeStoppingAndUsesTrustedResults() {
        ChatMessageAttachment second = attachment("attachment2", "doc2", 1, "stale-two.txt");
        ChatMessageAttachment first = attachment("attachment1", "doc1", 0, "stale-one.txt");
        userMessage = userMessage.withAttachments(List.of(second, first));
        when(messageRepository.findByOwnerAndId("tenant1", "user1", "msg_user"))
                .thenReturn(Optional.of(userMessage));
        AttachmentRef trustedFirst = new AttachmentRef(
                "doc1", "trusted-one.pdf", "application/pdf", 128L, 42L, "LIBRARY");
        AttachmentRef trustedSecond = new AttachmentRef(
                "doc2", "trusted-two.pdf", "application/pdf", 256L, 84L, "LIBRARY");
        UploadedDocument firstDocument = new UploadedDocument(
                "doc1", "tenant1", "user1", "session1", "trusted-one.pdf",
                "obs", "object1", "application/pdf", 128L, "AVAILABLE", "LIBRARY",
                42L, null, NOW, NOW);
        UploadedDocument secondDocument = new UploadedDocument(
                "doc2", "tenant1", "user1", "session1", "trusted-two.pdf",
                "obs", "object2", "application/pdf", 256L, "AVAILABLE", "LIBRARY",
                84L, null, NOW, NOW);
        when(documentFacade.resolveChatAttachmentsForUser(eq(user), any()))
                .thenReturn(new ResolvedChatAttachments(
                        List.of(trustedFirst, trustedSecond),
                        List.of(firstDocument, secondDocument)));
        ChatRun running = run(ChatRunStatus.RUNNING, null);
        ChatRun cancelled = run(ChatRunStatus.CANCELLED, "msg_assistant_a");
        when(sessionService.getSession(user, "session1"))
                .thenReturn(session("msg_user"), session("msg_assistant_a"));
        when(runService.requireOwnedRun(user, "run_a")).thenReturn(running, cancelled);
        when(stopCoordinator.stopRun(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new ChatRunStopResult(
                        "run_a", "session1", ChatRunStatus.CANCELLED, 12L, NOW)));
        when(startCoordinator.startStandard(eq(user), any(), any(), any()))
                .thenReturn(Mono.just(new ChatRunStartResult(
                        "run_b", "session1", 13L, NOW, "chat-run-run_b")));

        service.switchDomainAgent(
                user, TraceContext.empty(), command(), RuntimeForwardHeaders.empty()).block();

        verify(documentFacade).resolveChatAttachmentsForUser(
                eq(user),
                argThat(attachments -> attachments.stream()
                        .map(AttachmentRef::documentId)
                        .toList()
                        .equals(List.of("doc1", "doc2"))));
        ArgumentCaptor<ChatCommand> commandCaptor = ArgumentCaptor.forClass(ChatCommand.class);
        verify(startCoordinator).startStandard(eq(user), any(), commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue().attachments())
                .containsExactly(trustedFirst, trustedSecond);
        InOrder order = inOrder(documentFacade, stopCoordinator, startCoordinator);
        order.verify(documentFacade).resolveChatAttachmentsForUser(eq(user), any());
        order.verify(stopCoordinator).stopRun(any(), any(), any(), any(), any());
        order.verify(startCoordinator).startStandard(eq(user), any(), any(), any());
    }

    @Test
    void rejectsWhenStopHasNotReachedTerminalState() {
        ChatRun running = run(ChatRunStatus.RUNNING, null);
        ChatRun cancelling = run(ChatRunStatus.CANCELLING, null);
        ChatSession userLeafSession = session("msg_user");
        when(sessionService.getSession(user, "session1")).thenReturn(userLeafSession);
        when(runService.requireOwnedRun(user, "run_a")).thenReturn(running, cancelling);
        when(stopCoordinator.stopRun(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new ChatRunStopResult(
                        "run_a", "session1", ChatRunStatus.CANCELLING, 8L, NOW)));

        assertThatThrownBy(() -> service.switchDomainAgent(
                        user, TraceContext.empty(), command(), RuntimeForwardHeaders.empty()).block())
                .isInstanceOf(CandidateSwitchConflictException.class)
                .extracting(error -> ((CandidateSwitchConflictException) error).code())
                .isEqualTo(CandidateSwitchConflictException.STOP_PENDING);

        verify(startCoordinator, never()).startStandard(any(), any(), any(), any());
    }

    @Test
    void rejectsStaleSourceBeforeStoppingIt() {
        when(runService.requireOwnedRun(user, "run_a"))
                .thenReturn(run(ChatRunStatus.RUNNING, "msg_assistant_a"));
        when(sessionService.getSession(user, "session1")).thenReturn(session("msg_other"));

        assertThatThrownBy(() -> service.switchDomainAgent(
                        user, TraceContext.empty(), command(), RuntimeForwardHeaders.empty()).block())
                .isInstanceOf(CandidateSwitchConflictException.class)
                .extracting(error -> ((CandidateSwitchConflictException) error).code())
                .isEqualTo(CandidateSwitchConflictException.STALE_SOURCE);

        verify(stopCoordinator, never()).stopRun(any(), any(), any(), any(), any());
        verify(startCoordinator, never()).startStandard(any(), any(), any(), any());
    }

    @Test
    void startsFirstAssistantVersionWhenSourceHasNoPersistedAssistant() {
        ChatRun completed = run(ChatRunStatus.COMPLETED, null);
        ChatSession userLeafSession = session("msg_user");
        when(sessionService.getSession(user, "session1")).thenReturn(userLeafSession);
        when(runService.requireOwnedRun(user, "run_a")).thenReturn(completed, completed);
        when(startCoordinator.startStandard(
                eq(user), any(TraceContext.class), any(ChatCommand.class), any()))
                .thenReturn(Mono.just(new ChatRunStartResult(
                        "run_b", "session1", 13L, NOW, "chat-run-run_b")));

        service.switchDomainAgent(
                user, TraceContext.empty(), command(), RuntimeForwardHeaders.empty()).block();

        ArgumentCaptor<ChatCommand> commandCaptor = ArgumentCaptor.forClass(ChatCommand.class);
        verify(startCoordinator).startStandard(
                eq(user), any(TraceContext.class), commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue().regeneratedMessageId()).isNull();
        verify(stopCoordinator, never()).stopRun(any(), any(), any(), any(), any());
    }

    private CandidateDomainAgentSwitchCommand command() {
        return new CandidateDomainAgentSwitchCommand(
                "run_a",
                "msg_user",
                "skill_b",
                SelectedIntentContext.attach(
                        Map.of("bizKey", "current-run-only"), "intent_b", "候选技能B"),
                null,
                "finance_pc_entry");
    }

    private ChatRun run(ChatRunStatus status, String assistantMessageId) {
        return new ChatRun(
                "run_a", "tenant1", "user1", "session1", status,
                "DOMAIN_AGENT", "skill_a", "domain-agent", null,
                ChatRunMode.NEXT, null, "msg_user", assistantMessageId,
                1L, 10L, null, NOW, status.terminal() ? NOW : null,
                Map.of("sourceOnly", true), NOW, NOW);
    }

    private ChatSession session(String currentLeaf) {
        return new ChatSession(
                "session1", "tenant1", "user1", "标题", "ACTIVE", "web",
                "app1", "App One", currentLeaf, "session1", null, null,
                2L, 0L, 0L, null, NOW, NOW);
    }

    private ChatMessage userMessage() {
        return new ChatMessage(
                "msg_user", "tenant1", "user1", "session1", null,
                1L, 0, 1, "user", "原始问题", null, "run_a",
                "NORMAL", false, null, null, null, null, null,
                List.of(), List.of(), NOW);
    }

    private ChatMessageAttachment attachment(
            String id,
            String documentId,
            int order,
            String name) {
        return new ChatMessageAttachment(
                id, "tenant1", "user1", "session1", "msg_user", documentId,
                order, name, "text/plain", 64L, null, NOW);
    }
}
