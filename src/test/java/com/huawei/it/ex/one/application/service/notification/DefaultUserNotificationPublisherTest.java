/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.huawei.it.ex.one.application.integration.notification.UserNotificationBus;
import com.huawei.it.ex.one.domain.notification.UserNotification;
import com.huawei.it.ex.one.domain.notification.UserNotificationRecipient;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;

class DefaultUserNotificationPublisherTest {
    private final UserNotificationRecipient recipient = new UserNotificationRecipient("tenant", "user");
    private final UserNotificationBus bus = mock(UserNotificationBus.class);
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final DefaultUserNotificationPublisher publisher =
            new DefaultUserNotificationPublisher(bus, mapper, tasks::add);

    @Test
    void commitsBeforeEnqueueAndFreezesNestedPayload() throws Exception {
        List<String> values = new ArrayList<>(List.of("before"));
        Map<String, Object> data = new LinkedHashMap<>(Map.of("items", values));
        new TransactionTemplate(new LocalTransactionManager()).executeWithoutResult(status -> {
            publisher.publish(recipient, new UserNotification("task.completed", data));
            values.add("after");
            data.put("new", true);
            assertThat(tasks).isEmpty();
            verifyNoInteractions(bus);
        });
        assertThat(tasks).hasSize(1);
        tasks.remove().run();
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(bus).publish(org.mockito.ArgumentMatchers.eq(recipient), json.capture());
        assertThat(mapper.readTree(json.getValue()).path("data").path("items").size()).isEqualTo(1);
        assertThat(json.getValue()).doesNotContain("after", "new");
    }

    @Test
    void rollbackDiscardsNotification() {
        new TransactionTemplate(new LocalTransactionManager()).executeWithoutResult(status -> {
            publisher.publish(recipient, notification());
            status.setRollbackOnly();
        });
        assertThat(tasks).isEmpty();
        verifyNoInteractions(bus);
    }

    @Test
    void withoutTransactionOnlyEnqueuesAndTransportFailureIsIsolated() {
        doThrow(new IllegalStateException("redis unavailable")).when(bus).publish(any(), anyString());
        publisher.publish(recipient, notification());
        verifyNoInteractions(bus);
        assertThatCode(() -> tasks.remove().run()).doesNotThrowAnyException();
    }

    @Test
    void rejectedExecutorDoesNotTurnCommittedBusinessIntoFailure() {
        DefaultUserNotificationPublisher rejected = new DefaultUserNotificationPublisher(bus, mapper,
                task -> { throw new RejectedExecutionException(); });
        assertThatCode(() -> new TransactionTemplate(new LocalTransactionManager())
                .executeWithoutResult(status -> rejected.publish(recipient, notification())))
                .doesNotThrowAnyException();
        verifyNoInteractions(bus);
    }

    @Test
    void enforcesSerializedByteBoundaryAndRejectsInvalidData() throws Exception {
        int overhead = mapper.writeValueAsBytes(new UserNotification("test", Map.of("text", ""))).length;
        publisher.publish(recipient, new UserNotification("test",
                Map.of("text", "x".repeat(DefaultUserNotificationPublisher.MAX_NOTIFICATION_BYTES - overhead))));
        assertThat(tasks).hasSize(1);
        tasks.clear();
        publisher.publish(recipient, new UserNotification("test",
                Map.of("text", "x".repeat(DefaultUserNotificationPublisher.MAX_NOTIFICATION_BYTES - overhead + 1))));
        publisher.publish(recipient, new UserNotification("test", Map.of("text", "中".repeat(6000))));
        publisher.publish(new UserNotificationRecipient("", "user"), notification());
        publisher.publish(recipient, new UserNotification("", Map.of()));
        publisher.publish(recipient, new UserNotification("test", null));
        publisher.publish(null, null);
        assertThat(tasks).isEmpty();
        verifyNoInteractions(bus);
    }

    private UserNotification notification() {
        return new UserNotification("session.title.updated", Map.of("sessionId", "session"));
    }

    private static final class LocalTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
