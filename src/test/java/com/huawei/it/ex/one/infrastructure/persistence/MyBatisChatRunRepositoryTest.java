package com.huawei.it.ex.one.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

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
