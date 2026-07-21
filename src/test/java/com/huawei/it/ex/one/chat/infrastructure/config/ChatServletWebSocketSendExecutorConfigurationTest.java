package com.huawei.it.ex.one.chat.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.chat.application.config.ChatWebSocketProperties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ChatServletWebSocketSendExecutorConfigurationTest {
    private final ChatServletWebSocketSendExecutorConfiguration configuration =
            new ChatServletWebSocketSendExecutorConfiguration();

    @Test
    void platformThreadExecutorKeepsBoundedPoolAndNamedDaemonThreads() throws Exception {
        ChatWebSocketProperties properties = new ChatWebSocketProperties();
        properties.setServletSendUseVirtualThreads(false);
        properties.setServletSendExecutorCoreSize(1);
        properties.setServletSendExecutorMaxSize(2);
        ExecutorService executor = configuration.chatServletWebSocketSendExecutor(properties);

        try {
            assertThat(executor).isInstanceOf(ThreadPoolExecutor.class);
            ThreadSnapshot snapshot = executor.submit(() -> new ThreadSnapshot(
                    Thread.currentThread().getName(), Thread.currentThread().isDaemon())).get(5, TimeUnit.SECONDS);
            assertThat(snapshot.name()).startsWith("finex-ws-send-");
            assertThat(snapshot.daemon()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void virtualThreadConfigurationStillUsesVirtualThreads() throws Exception {
        ChatWebSocketProperties properties = new ChatWebSocketProperties();
        properties.setServletSendUseVirtualThreads(true);
        ExecutorService executor = configuration.chatServletWebSocketSendExecutor(properties);

        try {
            assertThat(executor.submit(() -> Thread.currentThread().isVirtual()).get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    private record ThreadSnapshot(String name, boolean daemon) {}
}
