package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.MessageCompletedEvent;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ChatDeltaCoalescerTest {
    @Test
    void passesEventsThroughWithoutMergingEvenWhenCoalescingConfigIsEnabled() {
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setDeltaCoalesceEnabled(true);
        ChatDeltaCoalescer coalescer = new ChatDeltaCoalescer(properties);

        List<ChatEvent> events = coalescer.coalesce(Flux.just(
                MessageDeltaEvent.of("run1", "session1", "你"),
                MessageDeltaEvent.of("run1", "session1", "好"),
                MessageCompletedEvent.of("run1", "session1")
        )).collectList().block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0).payload()).containsEntry("delta", "你");
        assertThat(events.get(1).payload()).containsEntry("delta", "好");
        assertThat(events.get(2).type()).isEqualTo("message.completed");
    }

    @Test
    void preservesRuntimeBoundariesAndPayloads() {
        ChatDeltaCoalescer coalescer = new ChatDeltaCoalescer(new ChatStreamProperties());
        ChatEvent firstDelta = MessageDeltaEvent.of("run1", "session1", "a");
        ChatEvent secondDelta = MessageDeltaEvent.of("run1", "session1", "b");
        RuntimeEvent runtimeEvent = RuntimeEvent.relay("run1", "session1", new RuntimeEvent.FallbackPayload(
                "relay", "project_home", "event", "runtime", "runtime", null, Map.of("project_home", "/tmp/xxx")));

        List<ChatEvent> events = coalescer.coalesce(Flux.just(
                firstDelta,
                runtimeEvent,
                secondDelta
        )).collectList().block();

        assertThat(events).containsExactly(firstDelta, runtimeEvent, secondDelta);
    }

    @Test
    void highFrequencyEventsDoNotFailInsideChatServiceCoalescer() {
        ChatDeltaCoalescer coalescer = new ChatDeltaCoalescer(new ChatStreamProperties());

        List<ChatEvent> events = coalescer.coalesce(Flux.fromStream(IntStream.range(0, 10_000)
                        .mapToObj(index -> MessageDeltaEvent.of("run1", "session1", String.valueOf(index)))))
                .collectList()
                .block();

        assertThat(events).hasSize(10_000);
        assertThat(events.getFirst().payload()).containsEntry("delta", "0");
        assertThat(events.getLast().payload()).containsEntry("delta", "9999");
    }
}
