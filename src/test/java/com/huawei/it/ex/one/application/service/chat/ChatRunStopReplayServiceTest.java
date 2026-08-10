package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.RuntimeStreamLimitsProperties;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStopDecision;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ChatRunStopReplayServiceTest {
    private final UserContext user = new UserContext("tenant1", "user1", "User One");

    @Test
    void replaysPersistedEventsBySequencePageAndKeepsAssemblyUntilCommit() {
        RuntimeStreamLimitsProperties properties = properties();
        properties.setStopReplayPageSize(2);
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        ChatRunStopTerminalFinalizer terminalFinalizer = mock(ChatRunStopTerminalFinalizer.class);
        AssistantAssemblyBudgetRegistry budgets = new AssistantAssemblyBudgetRegistry(
                properties, new ObjectMapper());
        ChatRun run = run("run-1");
        ChatRun cancelling = run.cancelling("USER_STOP");
        when(runService.requireOwnedRun(user, run.id())).thenReturn(run);
        when(runService.requestStop(user, run, "USER_STOP"))
                .thenReturn(new ChatRunStopDecision(cancelling, true));
        when(streamService.findPersistedRunEventPage(user, cancelling, 0L, 2))
                .thenReturn(List.of(delta(run, 1L, "part-"), delta(run, 2L, "answer")));
        when(streamService.findPersistedRunEventPage(user, cancelling, 2L, 2))
                .thenReturn(List.of(delta(run, 3L, "!")));
        AtomicReference<String> committedContent = new AtomicReference<>();
        when(terminalFinalizer.commit(any(), any(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    AssistantAssembly assistant = invocation.getArgument(4);
                    committedContent.set(assistant.finalContent());
                    return result(cancelling.cancelled(4L));
                });
        ChatRunStopReplayService service = new ChatRunStopReplayService(
                properties, streamService, runService,
                new AssistantAssemblyFactory(budgets), terminalFinalizer);

        ChatRunStopTerminalFinalizer.Result result = service.replayAndCommit(
                user, run, "USER_STOP", session());

        assertThat(result.run().status()).isEqualTo(ChatRunStatus.CANCELLED);
        assertThat(committedContent).hasValue("part-answer!");
        assertThat(service.availableReplayPermits()).isEqualTo(2);
        assertThat(budgets.activeBytes()).isZero();
    }

    @Test
    void thirdConcurrentFallbackSkipsReplayWithoutWaitingAndStillCommitsCancellation() throws Exception {
        RuntimeStreamLimitsProperties properties = properties();
        properties.setStopReplayMaxConcurrency(2);
        ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        ChatRunStopTerminalFinalizer terminalFinalizer = mock(ChatRunStopTerminalFinalizer.class);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger queries = new AtomicInteger();
        when(streamService.findPersistedRunEventPage(any(), any(), anyLong(), anyInt()))
                .thenAnswer(invocation -> {
                    queries.incrementAndGet();
                    entered.countDown();
                    assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
                    return List.of();
                });
        when(runService.requireOwnedRun(any(), anyString()))
                .thenAnswer(invocation -> run(invocation.getArgument(1)));
        when(runService.requestStop(any(), any(ChatRun.class), anyString()))
                .thenAnswer(invocation -> {
                    ChatRun candidate = invocation.getArgument(1);
                    return new ChatRunStopDecision(candidate.cancelling(invocation.getArgument(2)), true);
                });
        AtomicInteger commitsWithoutAssembly = new AtomicInteger();
        when(terminalFinalizer.commit(any(), any(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    ChatRun candidate = invocation.getArgument(1);
                    if (invocation.getArgument(4) == null) {
                        commitsWithoutAssembly.incrementAndGet();
                    }
                    return result(candidate.cancelled(9L));
                });
        AssistantAssemblyBudgetRegistry budgets = new AssistantAssemblyBudgetRegistry(
                properties, new ObjectMapper());
        ChatRunStopReplayService service = new ChatRunStopReplayService(
                properties, streamService, runService,
                new AssistantAssemblyFactory(budgets), terminalFinalizer);

        CompletableFuture<?> first = CompletableFuture.runAsync(() -> service.replayAndCommit(
                user, run("run-1"), "USER_STOP", session()));
        CompletableFuture<?> second = CompletableFuture.runAsync(() -> service.replayAndCommit(
                user, run("run-2"), "USER_STOP", session()));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        ChatRunStopTerminalFinalizer.Result third = service.replayAndCommit(
                user, run("run-3"), "USER_STOP", session());

        assertThat(third.run().status()).isEqualTo(ChatRunStatus.CANCELLED);
        assertThat(commitsWithoutAssembly).hasValue(1);
        assertThat(queries).hasValue(2);
        release.countDown();
        CompletableFuture.allOf(first, second).get(3, TimeUnit.SECONDS);
        assertThat(service.availableReplayPermits()).isEqualTo(2);
        assertThat(budgets.activeBytes()).isZero();
    }

    private RuntimeStreamLimitsProperties properties() {
        RuntimeStreamLimitsProperties properties = new RuntimeStreamLimitsProperties();
        properties.setStopReplayTotalTimeout(java.time.Duration.ofSeconds(5));
        properties.setStopReplayQueryTimeoutSeconds(2);
        return properties;
    }

    private ChatRunStopTerminalFinalizer.Result result(ChatRun run) {
        return new ChatRunStopTerminalFinalizer.Result(
                run, null, true, ChatRunStopAssistantProjector.Projection.notReady());
    }

    private ChatEvent delta(ChatRun run, long sequence, String value) {
        return new StoredChatEvent(
                run.id(), run.sessionId(), sequence, "message.delta", Instant.now(), Map.of("delta", value));
    }

    private ChatRun run(String runId) {
        Instant now = Instant.now();
        return new ChatRun(
                runId, "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
                "AGENT_RUNTIME", null, "relay", "relay-session", 1L, 2L,
                null, now, null, Map.of(), now, now);
    }

    private ChatSession session() {
        Instant now = Instant.now();
        return new ChatSession(
                "session1", "tenant1", "user1", "Title", "ACTIVE", "web", now, now);
    }
}
