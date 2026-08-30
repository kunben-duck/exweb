/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class LocalChatEventStreamRegistryTest {
    @Test
    void concurrentPublishesForSameRunTopicAreSerialized() throws Exception {
        LocalChatEventStreamRegistry registry = new LocalChatEventStreamRegistry();
        int count = 64;
        CompletableFuture<List<ChatEvent>> received = registry.subscribeRunTopic("chat-run-run1", 0)
                .take(count)
                .collectList()
                .toFuture();

        ExecutorService executor = Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long seq = i + 1L;
            futures.add(executor.submit(() -> {
                start.await();
                registry.publish(new StoredChatEvent("run1", "session1", seq, "message.delta",
                        Instant.now(), Map.of("delta", "local-" + seq)));
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(2, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        List<ChatEvent> events = received.get(5, TimeUnit.SECONDS);
        assertThat(events).hasSize(count);
        assertThat(events.stream().map(ChatEvent::sequence).distinct().count()).isEqualTo(count);
    }
}
