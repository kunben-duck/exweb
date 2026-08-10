package com.huawei.it.ex.one.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

class MyBatisChatRunExecutionRepositoryTest {
    @Test
    void ownerCheckPreservesMapperDecision() {
        ChatRunExecutionMapper mapper = mock(ChatRunExecutionMapper.class);
        RunExecutionClaim claim = new RunExecutionClaim("run-1", "instance-a", 3L);
        when(mapper.countCurrentOwnerRunning("run-1", "instance-a", 3L))
                .thenReturn(1, 0);
        MyBatisChatRunExecutionRepository repository =
                new MyBatisChatRunExecutionRepository(mapper, new ObjectMapper());

        assertThat(repository.isCurrentOwnerRunning(claim)).isTrue();
        assertThat(repository.isCurrentOwnerRunning(claim)).isFalse();
    }

    @Test
    void ownerCheckPropagatesDatabaseTimeout() {
        ChatRunExecutionMapper mapper = mock(ChatRunExecutionMapper.class);
        RunExecutionClaim claim = new RunExecutionClaim("run-1", "instance-a", 3L);
        QueryTimeoutException timeout = new QueryTimeoutException("owner query timed out");
        when(mapper.countCurrentOwnerRunning("run-1", "instance-a", 3L))
                .thenThrow(timeout);
        MyBatisChatRunExecutionRepository repository =
                new MyBatisChatRunExecutionRepository(mapper, new ObjectMapper());

        assertThatThrownBy(() -> repository.isCurrentOwnerRunning(claim))
                .isSameAs(timeout);
    }

    @Test
    void ownerCheckUsesDedicatedReadOnlyTransactionTimeout() throws NoSuchMethodException {
        Method method = MyBatisChatRunExecutionRepository.class.getMethod(
                "isCurrentOwnerRunning", RunExecutionClaim.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
        assertThat(transactional.timeoutString())
                .isEqualTo("${financeex.chat-run.execution-owner-query-timeout-seconds:2}");
    }

    @Test
    void fullyRenewedBatchDoesNotRequireEligibilityRead() {
        ChatRunExecutionMapper mapper = mock(ChatRunExecutionMapper.class);
        List<RunExecutionClaim> claims = List.of(
                new RunExecutionClaim("run-1", "instance-a", 1L),
                new RunExecutionClaim("run-2", "instance-a", 2L));
        when(mapper.heartbeatBatch(eq(claims), any(Instant.class))).thenReturn(claims.size());
        MyBatisChatRunExecutionRepository repository =
                new MyBatisChatRunExecutionRepository(mapper, new ObjectMapper());

        assertThat(repository.heartbeatBatch(claims, Duration.ofSeconds(90)))
                .containsExactlyElementsOf(claims);

        verify(mapper, never()).findHeartbeatEligibleClaims(anyList());
    }

    @Test
    void partiallyRenewedBatchReturnsOnlyClaimsStillEligible() {
        ChatRunExecutionMapper mapper = mock(ChatRunExecutionMapper.class);
        RunExecutionClaim renewed = new RunExecutionClaim("run-1", "instance-a", 1L);
        RunExecutionClaim rejected = new RunExecutionClaim("run-2", "instance-a", 2L);
        List<RunExecutionClaim> claims = List.of(renewed, rejected);
        when(mapper.heartbeatBatch(eq(claims), any(Instant.class))).thenReturn(1);
        when(mapper.findHeartbeatEligibleClaims(claims)).thenReturn(List.of(row(renewed)));
        MyBatisChatRunExecutionRepository repository =
                new MyBatisChatRunExecutionRepository(mapper, new ObjectMapper());

        assertThat(repository.heartbeatBatch(claims, Duration.ofSeconds(90)))
                .containsExactly(renewed);
    }

    @Test
    void heartbeatBatchUsesDedicatedShortTransactionTimeout() throws NoSuchMethodException {
        Method method = MyBatisChatRunExecutionRepository.class.getMethod(
                "heartbeatBatch", List.class, Duration.class);

        assertThat(method.getAnnotation(Transactional.class).timeoutString())
                .isEqualTo("${financeex.chat-run.heartbeat-transaction-timeout-seconds:2}");
    }

    @Test
    void shortensCancellingRunLeaseWithoutExtendingItInApplicationCode() {
        ChatRunExecutionMapper mapper = mock(ChatRunExecutionMapper.class);
        when(mapper.shortenLeaseForCancellingRun(eq("run-1"), any(Instant.class))).thenReturn(1);
        MyBatisChatRunExecutionRepository repository =
                new MyBatisChatRunExecutionRepository(mapper, new ObjectMapper());

        assertThat(repository.shortenLeaseForCancellingRun("run-1", Duration.ofSeconds(15))).isTrue();
        verify(mapper).shortenLeaseForCancellingRun(eq("run-1"), any(Instant.class));
    }

    @Test
    void ownerStopAcceptanceUsesCompleteOwnershipAndFencingGuard() {
        ChatRunExecutionMapper mapper = mock(ChatRunExecutionMapper.class);
        RunExecutionClaim claim = new RunExecutionClaim("run-1", "instance-a", 7L);
        when(mapper.markOwnerStopAccepted(
                eq("run-1"), eq("tenant1"), eq("user1"), eq("session1"),
                eq("instance-a"), eq(7L), any(Instant.class))).thenReturn(1);
        MyBatisChatRunExecutionRepository repository =
                new MyBatisChatRunExecutionRepository(mapper, new ObjectMapper());

        assertThat(repository.markOwnerStopAccepted(run(), claim, Duration.ofSeconds(15))).isTrue();
    }

    private ChatRunExecutionRow row(RunExecutionClaim claim) {
        ChatRunExecutionRow row = new ChatRunExecutionRow();
        row.setRunId(claim.runId());
        row.setOwnerInstanceId(claim.ownerInstanceId());
        row.setFencingToken(claim.fencingToken());
        return row;
    }

    private ChatRun run() {
        Instant now = Instant.now();
        return new ChatRun(
                "run-1", "tenant1", "user1", "session1", ChatRunStatus.CANCELLING,
                "AGENT_RUNTIME", null, "relay", null, null, null,
                "USER_STOP", now, null, Map.of(), now, now);
    }
}
