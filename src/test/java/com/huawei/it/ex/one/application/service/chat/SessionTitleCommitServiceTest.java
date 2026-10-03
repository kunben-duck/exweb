/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.SessionTitleProperties;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.notification.UserNotificationBus;
import com.huawei.it.ex.one.application.integration.notification.UserNotificationPublisher;
import com.huawei.it.ex.one.application.service.notification.DefaultUserNotificationPublisher;
import com.huawei.it.ex.one.domain.chat.ChatSession;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
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
        UserNotificationPublisher notifications = mock(UserNotificationPublisher.class);
        service.setNotificationPublisher(notifications);

        boolean stale = service.apply(candidate(1, 4L), "旧标题");
        when(repository.findByTenantIdAndUserIdAndId("tenant-1", "user-1", "session-1"))
                .thenReturn(Optional.of(session(metadata.markUser(current.metadataJson()))));
        boolean manual = service.apply(candidate(3, 6L), "自动标题");

        assertThat(stale).isFalse();
        assertThat(manual).isFalse();
        verifyNoInteractions(notifications);
        verify(repository, never()).updateTitleWithoutTouch(
                org.mockito.ArgumentMatchers.any(), anyString(), anyString());
    }

    @Test
    void successfulTitleOnlyQueuesNotificationAfterCommit() {
        SessionTitleProperties properties = new SessionTitleProperties();
        properties.setEnabled(true);
        SessionTitleMetadata metadata = new SessionTitleMetadata(new ObjectMapper(), properties);
        SessionRepository repository = mock(SessionRepository.class);
        ChatSession current = session(metadata.markAuto(null, 1, 2));
        when(repository.findByTenantIdAndUserIdAndId("tenant-1", "user-1", "session-1"))
                .thenReturn(Optional.of(current));
        SessionTitleCommitService service = new SessionTitleCommitService(repository, metadata);
        UserNotificationBus bus = mock(UserNotificationBus.class);
        List<Runnable> tasks = new ArrayList<>();
        service.setNotificationPublisher(new DefaultUserNotificationPublisher(bus, new ObjectMapper(), tasks::add));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(service.apply(candidate(2, 5), "new title")).isTrue();
            assertThat(tasks).isEmpty();
            verifyNoInteractions(bus);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(tasks).hasSize(1);
        } finally {
            TransactionSynchronizationManager.clear();
        }
        tasks.getFirst().run();
        org.mockito.ArgumentCaptor<String> json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(bus).publish(org.mockito.ArgumentMatchers.any(), json.capture());
        assertThat(json.getValue()).contains("session.title.updated", "session-1").doesNotContain("new title");
    }

    private SessionTitleCandidate candidate(int queryCount, long nodeOrder) {
        return new SessionTitleCandidate(
                "tenant-1", "user-1", "session-1", "run-1", List.of("问题"),
                "zh_CN", queryCount, nodeOrder);
    }

    private ChatSession session(String metadataJson) {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        return new ChatSession(
                "session-1", "tenant-1", "user-1", "标题", "ACTIVE", "web",
                null, null, null, "session-1", null, null, 0L, 0L, 0L,
                metadataJson, now, now);
    }
}
