package com.huawei.it.ex.one.infrastructure.persistence;

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

import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

class MyBatisChatRunRepositoryTest {
    @Test
    void interactionContinuationLocksSessionThenClaimBeforeValuesInsert() {
        ChatRunMapper mapper = mock(ChatRunMapper.class);
        ChatRun run = runningRun();
        when(mapper.lockSessionForInteractionContinuation("tenant1", "user1", "session1")).thenReturn(1);
        when(mapper.lockInteractionContinuationClaim(
                "interaction1", "tenant1", "user1", "session1", "run1")).thenReturn(1);
        when(mapper.insert(any(ChatRunWriteRow.class))).thenReturn(1);
        when(mapper.findById("run1")).thenReturn(toRow(run));
        MyBatisChatRunRepository repository = new MyBatisChatRunRepository(mapper, new ObjectMapper());

        assertThat(repository.insertInteractionContinuationIfClaimed(run, "interaction1"))
                .contains(run);

        InOrder order = inOrder(mapper);
        order.verify(mapper).lockSessionForInteractionContinuation("tenant1", "user1", "session1");
        order.verify(mapper).lockInteractionContinuationClaim(
                "interaction1", "tenant1", "user1", "session1", "run1");
        order.verify(mapper).insert(any(ChatRunWriteRow.class));
        order.verify(mapper).findById("run1");
    }

    @Test
    void interactionContinuationDoesNotInsertWhenClaimNoLongerMatches() {
        ChatRunMapper mapper = mock(ChatRunMapper.class);
        when(mapper.lockSessionForInteractionContinuation("tenant1", "user1", "session1")).thenReturn(1);
        MyBatisChatRunRepository repository = new MyBatisChatRunRepository(mapper, new ObjectMapper());

        assertThat(repository.insertInteractionContinuationIfClaimed(runningRun(), "interaction1"))
                .isEmpty();

        verify(mapper, never()).insert(any(ChatRunWriteRow.class));
        verify(mapper, never()).findById("run1");
    }

    @Test
    void interactionContinuationRejectsMissingOwnedSessionBeforeClaimLock() {
        ChatRunMapper mapper = mock(ChatRunMapper.class);
        MyBatisChatRunRepository repository = new MyBatisChatRunRepository(mapper, new ObjectMapper());

        assertThatThrownBy(() -> repository.insertInteractionContinuationIfClaimed(runningRun(), "interaction1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话不存在或不属于当前用户");

        verify(mapper, never()).lockInteractionContinuationClaim(
                any(), any(), any(), any(), any());
        verify(mapper, never()).insert(any(ChatRunWriteRow.class));
    }

    @Test
    void resolvedRouteUsesDedicatedUpdateAndReturnsPersistedTarget() {
        ChatRunMapper mapper = mock(ChatRunMapper.class);
        ChatRun rerouted = runningRun().withResolvedRoute(
                "AGENT_RUNTIME", null, "relay", "relay-session-1");
        when(mapper.updateResolvedRoute(any(ChatRunWriteRow.class))).thenReturn(1);
        when(mapper.findById("run1")).thenReturn(toRow(rerouted));
        MyBatisChatRunRepository repository = new MyBatisChatRunRepository(mapper, new ObjectMapper());

        ChatRun saved = repository.updateResolvedRoute(rerouted);

        assertThat(saved)
                .returns("AGENT_RUNTIME", ChatRun::routeType)
                .returns((String) null, ChatRun::agentCode)
                .returns("relay", ChatRun::runtimeProvider)
                .returns("relay-session-1", ChatRun::runtimeSessionId);
        verify(mapper).updateResolvedRoute(argThat(row ->
                "AGENT_RUNTIME".equals(row.routeType())
                        && row.agentCode() == null
                        && "relay".equals(row.runtimeProvider())
                        && "relay-session-1".equals(row.runtimeSessionId())));
        verify(mapper, never()).updateExisting(any(ChatRunWriteRow.class));
    }

    @Test
    void resolvedRouteRejectsWhenRunIsNoLongerRunning() {
        ChatRunMapper mapper = mock(ChatRunMapper.class);
        when(mapper.updateResolvedRoute(any(ChatRunWriteRow.class))).thenReturn(0);
        MyBatisChatRunRepository repository = new MyBatisChatRunRepository(mapper, new ObjectMapper());

        assertThatThrownBy(() -> repository.updateResolvedRoute(runningRun()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法更新最终路由");

        verify(mapper, never()).findById(any());
    }

    @Test
    void guardedResolvedRouteUsesExecutionClaimAndReturnsPersistedTarget() {
        ChatRunMapper mapper = mock(ChatRunMapper.class);
        ChatRun rerouted = runningRun().withResolvedRoute(
                "AGENT_RUNTIME", null, "relay", "relay-session-1")
                .withMetadata(Map.of("_agentDataPersistence", Map.of(
                        "policy", "ASSISTANT_PLACEHOLDER")));
        RunExecutionClaim claim = new RunExecutionClaim("run1", "instance-1", 7L);
        when(mapper.lockResolvedRouteRun(any(ChatRunWriteRow.class))).thenReturn(1);
        when(mapper.lockResolvedRouteExecution(any(ChatRunWriteRow.class), eq(claim))).thenReturn(1);
        when(mapper.updateResolvedRouteWithExecutionGuard(any(ChatRunWriteRow.class), eq(claim)))
                .thenReturn(1);
        when(mapper.findById("run1")).thenReturn(toRow(rerouted));
        MyBatisChatRunRepository repository = new MyBatisChatRunRepository(mapper, new ObjectMapper());

        ChatRun saved = repository.updateResolvedRouteWithExecutionGuard(rerouted, claim);

        assertThat(saved)
                .returns("relay", ChatRun::runtimeProvider)
                .returns("relay-session-1", ChatRun::runtimeSessionId);
        InOrder order = inOrder(mapper);
        order.verify(mapper).lockResolvedRouteRun(argThat(row ->
                "run1".equals(row.id())
                        && "tenant1".equals(row.tenantId())
                        && "user1".equals(row.userId())
                        && "session1".equals(row.sessionId())));
        order.verify(mapper).lockResolvedRouteExecution(any(ChatRunWriteRow.class), eq(claim));
        order.verify(mapper).updateResolvedRouteWithExecutionGuard(argThat(row ->
                "run1".equals(row.id())
                        && "tenant1".equals(row.tenantId())
                        && "user1".equals(row.userId())
                        && "session1".equals(row.sessionId())
                        && row.metadataJson().contains("ASSISTANT_PLACEHOLDER")), eq(claim));
        order.verify(mapper).findById("run1");
        verify(mapper, never()).updateResolvedRoute(any(ChatRunWriteRow.class));
        verify(mapper, never()).updateExisting(any(ChatRunWriteRow.class));
    }

    @Test
    void guardedResolvedRouteRejectsBeforeExecutionLockWhenRunIsNoLongerRunning() {
        ChatRunMapper mapper = mock(ChatRunMapper.class);
        RunExecutionClaim claim = new RunExecutionClaim("run1", "instance-old", 6L);
        MyBatisChatRunRepository repository = new MyBatisChatRunRepository(mapper, new ObjectMapper());

        assertThatThrownBy(() -> repository.updateResolvedRouteWithExecutionGuard(runningRun(), claim))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run 已停止");

        verify(mapper, never()).lockResolvedRouteExecution(any(), any());
        verify(mapper, never()).updateResolvedRouteWithExecutionGuard(any(), any());
        verify(mapper, never()).findById(any());
    }

    @Test
    void guardedResolvedRouteRejectsWhenExecutionClaimNoLongerMatches() {
        ChatRunMapper mapper = mock(ChatRunMapper.class);
        RunExecutionClaim claim = new RunExecutionClaim("run1", "instance-old", 6L);
        when(mapper.lockResolvedRouteRun(any(ChatRunWriteRow.class))).thenReturn(1);
        MyBatisChatRunRepository repository = new MyBatisChatRunRepository(mapper, new ObjectMapper());

        assertThatThrownBy(() -> repository.updateResolvedRouteWithExecutionGuard(runningRun(), claim))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution owner");

        verify(mapper, never()).updateResolvedRouteWithExecutionGuard(any(), any());
        verify(mapper, never()).findById(any());
    }

    @Test
    void guardedResolvedRouteUsesInteractionResumeTransactionTimeout() throws Exception {
        Transactional transactional = MyBatisChatRunRepository.class
                .getMethod("updateResolvedRouteWithExecutionGuard", ChatRun.class, RunExecutionClaim.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString())
                .isEqualTo("${financeex.runtime-binding.interaction-resume-transaction-timeout-seconds:2}");
    }

    private ChatRun runningRun() {
        Instant now = Instant.parse("2026-07-16T00:00:00Z");
        return new ChatRun(
                "run1", "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
                null, null, null, null, ChatRunMode.NEXT,
                "parent1", "message1", null, null, null, null,
                now, null, Map.of(), now, now);
    }

    private ChatRunRow toRow(ChatRun run) {
        ChatRunRow row = new ChatRunRow();
        row.setId(run.id());
        row.setTenantId(run.tenantId());
        row.setUserId(run.userId());
        row.setSessionId(run.sessionId());
        row.setStatus(run.status().name());
        row.setRouteType(run.routeType());
        row.setAgentCode(run.agentCode());
        row.setRuntimeProvider(run.runtimeProvider());
        row.setRuntimeSessionId(run.runtimeSessionId());
        row.setRunMode(run.runMode().name());
        row.setParentMessageId(run.parentMessageId());
        row.setUserMessageId(run.userMessageId());
        row.setStartedAt(run.startedAt());
        row.setMetadataJson("{}");
        row.setCreatedAt(run.createdAt());
        row.setUpdatedAt(run.updatedAt());
        return row;
    }
}
