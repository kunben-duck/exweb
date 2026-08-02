package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.SessionTitleProperties;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.domain.chat.ChatSession;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

class SessionTitleCommitServiceTest {
    @Test
    void rejectsLateResultAndManualTitle() {
        SessionTitleProperties properties = new SessionTitleProperties();
        properties.setEnabled(true);
        SessionTitleMetadata metadata = new SessionTitleMetadata(new ObjectMapper(), properties);
        SessionRepository repository = mock(SessionRepository.class);
        doNothing().when(repository).lockForMessageMutation(anyString(), anyString(), anyString());
        ChatSession current = session(metadata.markAuto(null, 2, 5L));
        when(repository.findByTenantIdAndUserIdAndId("tenant-1", "user-1", "session-1"))
                .thenReturn(Optional.of(current));
        SessionTitleCommitService service = new SessionTitleCommitService(repository, metadata);

        boolean stale = service.apply(candidate(1, 4L), "旧标题");
        when(repository.findByTenantIdAndUserIdAndId("tenant-1", "user-1", "session-1"))
                .thenReturn(Optional.of(session(metadata.markUser(current.metadataJson()))));
        boolean manual = service.apply(candidate(3, 6L), "自动标题");

        assertThat(stale).isFalse();
        assertThat(manual).isFalse();
        verify(repository, never()).updateTitleWithoutTouch(
                org.mockito.ArgumentMatchers.any(), anyString(), anyString());
    }

    private SessionTitleCandidate candidate(int queryCount, long nodeOrder) {
        return new SessionTitleCandidate(
                "tenant-1", "user-1", "session-1", "run-1", List.of("问题"),
                "zh-CN", queryCount, nodeOrder);
    }

    private ChatSession session(String metadataJson) {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        return new ChatSession(
                "session-1", "tenant-1", "user-1", "标题", "ACTIVE", "web",
                null, null, null, "session-1", null, null, 0L, 0L, 0L,
                metadataJson, now, now);
    }
}
