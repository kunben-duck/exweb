package com.huawei.it.ex.one.application.service.chat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.conversation.RunStopControlBus;
import com.huawei.it.ex.one.application.service.runtime.RuntimePendingEventGuard;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

class ChatRunOwnerStopFinalizerTest {
    @Test
    void cleansAssemblyAndBudgetsEvenWhenCompletionNotificationFails() {
        ChatRunStopTerminalFinalizer terminalFinalizer = mock(ChatRunStopTerminalFinalizer.class);
        LocalChatRunExecutionRegistry registry = mock(LocalChatRunExecutionRegistry.class);
        RuntimePendingEventGuard pendingEventGuard = mock(RuntimePendingEventGuard.class);
        RunEventPipelineContext context = mock(RunEventPipelineContext.class);
        AssistantAssembly assistant = mock(AssistantAssembly.class);
        RunExecutionClaim claim = new RunExecutionClaim("run-1", "instance-a", 7L);
        ChatRun cancelling = run().cancelling("USER_STOP");
        RunStopControlBus.Request request = new RunStopControlBus.Request(
                "stop-1", "run-1", "instance-b", "instance-a", 7L,
                "USER_STOP");
        LocalChatRunExecutionRegistry.OwnerStopFinalization finalization =
                new LocalChatRunExecutionRegistry.OwnerStopFinalization(
                        context, claim, request, ignored -> {
                            throw new IllegalStateException("Redis unavailable");
                        }, Mono.just(cancelling));
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatSession session = session();
        when(context.user()).thenReturn(user);
        when(context.session()).thenReturn(session);
        when(context.assistant()).thenReturn(assistant);
        when(context.runId()).thenReturn("run-1");
        ChatRun cancelled = cancelling.cancelled(10L);
        when(terminalFinalizer.commit(user, cancelling, "USER_STOP", session, assistant, claim))
                .thenReturn(new ChatRunStopTerminalFinalizer.Result(
                        cancelled, null, true, ChatRunStopAssistantProjector.Projection.notReady()));
        ChatRunOwnerStopFinalizer finalizer = new ChatRunOwnerStopFinalizer(
                terminalFinalizer, registry, pendingEventGuard, Schedulers.immediate());

        finalizer.finalizeAsync(finalization);

        verify(assistant).close();
        verify(pendingEventGuard).releaseRun("run-1");
        verify(registry).completeOwnerStopFinalization(claim);
    }

    @Test
    void waitsForDatabaseOwnerStopConfirmationBeforeCommittingAssembly() {
        ChatRunStopTerminalFinalizer terminalFinalizer = mock(ChatRunStopTerminalFinalizer.class);
        LocalChatRunExecutionRegistry registry = mock(LocalChatRunExecutionRegistry.class);
        RuntimePendingEventGuard pendingEventGuard = mock(RuntimePendingEventGuard.class);
        RunEventPipelineContext context = mock(RunEventPipelineContext.class);
        AssistantAssembly assistant = mock(AssistantAssembly.class);
        RunExecutionClaim claim = new RunExecutionClaim("run-1", "instance-a", 7L);
        RunStopControlBus.Request request = new RunStopControlBus.Request(
                "stop-1", "run-1", "instance-b", "instance-a", 7L,
                "USER_STOP");
        Sinks.One<ChatRun> confirmation = Sinks.one();
        LocalChatRunExecutionRegistry.OwnerStopFinalization finalization =
                new LocalChatRunExecutionRegistry.OwnerStopFinalization(
                        context, claim, request, ignored -> { }, confirmation.asMono());
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatSession session = session();
        ChatRun cancelling = run().cancelling("USER_STOP");
        ChatRun cancelled = cancelling.cancelled(10L);
        when(context.user()).thenReturn(user);
        when(context.session()).thenReturn(session);
        when(context.assistant()).thenReturn(assistant);
        when(context.runId()).thenReturn("run-1");
        when(terminalFinalizer.commit(user, cancelling, "USER_STOP", session, assistant, claim))
                .thenReturn(new ChatRunStopTerminalFinalizer.Result(
                        cancelled, null, true, ChatRunStopAssistantProjector.Projection.notReady()));
        ChatRunOwnerStopFinalizer finalizer = new ChatRunOwnerStopFinalizer(
                terminalFinalizer, registry, pendingEventGuard, Schedulers.immediate());

        finalizer.finalizeAsync(finalization);
        verify(terminalFinalizer, never()).commit(user, cancelling, "USER_STOP", session, assistant, claim);

        confirmation.tryEmitValue(cancelling);
        verify(terminalFinalizer).commit(user, cancelling, "USER_STOP", session, assistant, claim);
        verify(assistant).close();
        verify(registry).completeOwnerStopFinalization(claim);
    }

    private ChatRun run() {
        Instant now = Instant.now();
        return new ChatRun(
                "run-1", "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
                "AGENT_RUNTIME", null, "relay", "relay-session", 1L, 2L,
                null, now, null, Map.of(), now, now);
    }

    private ChatSession session() {
        Instant now = Instant.now();
        return new ChatSession(
                "session1", "tenant1", "user1", "Title", "ACTIVE", "web", now, now);
    }
}
