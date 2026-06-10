package com.huawei.finance.front.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.config.ChatStreamProperties;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ErrorEvent;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.domain.chat.MessageSnapshotEvent;
import com.huawei.finance.front.one.domain.chat.RuntimeEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
class ChatDeltaCoalescerTest {
    @Test
    void coalescesConsecutiveDeltaAndFlushesBeforeBoundaryEvent() {
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setDeltaCoalesceWindow(Duration.ofSeconds(5));
        properties.setDeltaCoalesceMaxChars(512);
        ChatDeltaCoalescer coalescer = new ChatDeltaCoalescer(properties);

        List<ChatEvent> events = coalescer.coalesce(Flux.just(
                MessageDeltaEvent.of("run1", "session1", "你"),
                MessageDeltaEvent.of("run1", "session1", "好"),
                MessageCompletedEvent.of("run1", "session1")
        )).collectList().block();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo("message.delta");
        assertThat(events.get(0).payload()).containsEntry("delta", "你好");
        assertThat(events.get(1).type()).isEqualTo("message.completed");
    }

    @Test
    void flushesWhenMaxCharsReached() {
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setDeltaCoalesceWindow(Duration.ofSeconds(5));
        properties.setDeltaCoalesceMaxChars(2);
        ChatDeltaCoalescer coalescer = new ChatDeltaCoalescer(properties);

        List<ChatEvent> events = coalescer.coalesce(Flux.just(
                MessageDeltaEvent.of("run1", "session1", "ab"),
                MessageDeltaEvent.of("run1", "session1", "c"),
                MessageCompletedEvent.of("run1", "session1")
        )).collectList().block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0).payload()).containsEntry("delta", "ab");
        assertThat(events.get(1).payload()).containsEntry("delta", "c");
    }

    @Test
    void doesNotMergeDeltaAcrossDifferentRunOrSession() {
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setDeltaCoalesceWindow(Duration.ofSeconds(5));
        ChatDeltaCoalescer coalescer = new ChatDeltaCoalescer(properties);

        List<ChatEvent> events = coalescer.coalesce(Flux.just(
                MessageDeltaEvent.of("run1", "session1", "a"),
                MessageDeltaEvent.of("run2", "session2", "b"),
                MessageCompletedEvent.of("run2", "session2")
        )).collectList().block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0).runId()).isEqualTo("run1");
        assertThat(events.get(0).payload()).containsEntry("delta", "a");
        assertThat(events.get(1).runId()).isEqualTo("run2");
        assertThat(events.get(1).payload()).containsEntry("delta", "b");
    }

    @Test
    void flushesBufferedDeltaBeforePropagatingSourceError() {
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setDeltaCoalesceWindow(Duration.ofSeconds(5));
        ChatDeltaCoalescer coalescer = new ChatDeltaCoalescer(properties);

        List<ChatEvent> events = coalescer.coalesce(Flux.concat(
                Flux.just(MessageDeltaEvent.of("run1", "session1", "partial")),
                Flux.error(new IllegalStateException("boom"))
        )).onErrorResume(ex -> Flux.just(ErrorEvent.of("run1", "session1", "ERR", ex.getMessage())))
                .collectList()
                .block();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).payload()).containsEntry("delta", "partial");
        assertThat(events.get(1).type()).isEqualTo("run.failed");
    }

    @Test
    void coalescedDeltaKeepsOnlyStandardPayloadFields() {
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setDeltaCoalesceWindow(Duration.ofSeconds(5));
        ChatDeltaCoalescer coalescer = new ChatDeltaCoalescer(properties);

        List<ChatEvent> events = coalescer.coalesce(Flux.just(
                new MessageDeltaEvent("run1", "session1", 0, Instant.now(), "a",
                        Map.of("delta", "a", "runtimeSessionId", "runtime-1", "raw", "must-not-leak")),
                MessageDeltaEvent.of("run1", "session1", "b"),
                MessageCompletedEvent.of("run1", "session1")
        )).collectList().block();

        assertThat(events).hasSize(2);
        assertThat(events.getFirst().payload())
                .containsEntry("delta", "ab")
                .containsEntry("runtimeSessionId", "runtime-1")
                .doesNotContainKey("raw");
    }

    @Test
    void runtimeEventFlushesBufferedDeltaAndIsNotMerged() {
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setDeltaCoalesceWindow(Duration.ofSeconds(5));
        ChatDeltaCoalescer coalescer = new ChatDeltaCoalescer(properties);
        RuntimeEvent runtimeEvent = RuntimeEvent.relay("run1", "session1", "project_home",
                "event", "runtime", "runtime", null, Map.of("project_home", "/tmp/xxx"));

        List<ChatEvent> events = coalescer.coalesce(Flux.just(
                MessageDeltaEvent.of("run1", "session1", "a"),
                runtimeEvent,
                MessageDeltaEvent.of("run1", "session1", "b"),
                MessageCompletedEvent.of("run1", "session1")
        )).collectList().block();

        assertThat(events).hasSize(4);
        assertThat(events.get(0).payload()).containsEntry("delta", "a");
        assertThat(events.get(1).type()).isEqualTo("runtime.event");
        assertThat(events.get(2).payload()).containsEntry("delta", "b");
        assertThat(events.get(3).type()).isEqualTo("message.completed");
    }

    @Test
    void snapshotFlushesBufferedDeltaAndIsNotMerged() {
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setDeltaCoalesceWindow(Duration.ofSeconds(5));
        ChatDeltaCoalescer coalescer = new ChatDeltaCoalescer(properties);

        List<ChatEvent> events = coalescer.coalesce(Flux.just(
                MessageDeltaEvent.of("run1", "session1", "草稿"),
                MessageSnapshotEvent.of("run1", "session1", "最终\n正文"),
                MessageCompletedEvent.of("run1", "session1")
        )).collectList().block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0).type()).isEqualTo("message.delta");
        assertThat(events.get(0).payload()).containsEntry("delta", "草稿");
        assertThat(events.get(1).type()).isEqualTo("message.snapshot");
        assertThat(events.get(1).payload()).containsEntry("content", "最终\n正文");
        assertThat(events.get(2).type()).isEqualTo("message.completed");
    }
}
