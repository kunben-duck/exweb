/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.MessageCompletedEvent;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

class ChatEventBatcherTest {
    @Test
    void flushesWhenEventCountThresholdIsReached() {
        VirtualTimeScheduler timer = VirtualTimeScheduler.create();
        ChatEventBatcher batcher = batcher(3, Duration.ofSeconds(1), DataSize.ofMegabytes(1), timer);

        StepVerifier.create(batcher.batch(Flux.concat(
                                Flux.just(delta("a"), delta("b"), delta("c")), Mono.never()),
                        this::isDelta).take(1))
                .assertNext(batch -> {
                    assertThat(batch.databaseBatch()).isTrue();
                    assertThat(batch.events()).extracting(event -> event.payload().get("delta"))
                            .containsExactly("a", "b", "c");
                })
                .verifyComplete();
    }

    @Test
    void flushesWhenWaitThresholdIsReached() {
        VirtualTimeScheduler timer = VirtualTimeScheduler.create();
        ChatEventBatcher batcher = batcher(16, Duration.ofMillis(20), DataSize.ofMegabytes(1), timer);

        StepVerifier.withVirtualTime(() -> batcher.batch(
                                Flux.concat(Flux.just(delta("a"), delta("b")), Mono.never()),
                                this::isDelta).take(1), () -> timer, 1)
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(19))
                .thenAwait(Duration.ofMillis(1))
                .assertNext(batch -> assertThat(batch.events()).hasSize(2))
                .verifyComplete();
    }

    @Test
    void flushesOversizedSingleEventWithoutRejectingIt() {
        VirtualTimeScheduler timer = VirtualTimeScheduler.create();
        ChatEventBatcher batcher = batcher(16, Duration.ofSeconds(1), DataSize.ofBytes(128), timer);
        ChatEvent oversized = delta("x".repeat(512));

        StepVerifier.create(batcher.batch(Flux.concat(Flux.just(oversized), Mono.never()), this::isDelta).take(1))
                .assertNext(batch -> {
                    assertThat(batch.events()).containsExactly(oversized);
                    assertThat(batch.databaseBatch()).isFalse();
                    assertThat(batch.batchable()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void splitsBatchWhenCombinedSerializedBytesReachThreshold() {
        VirtualTimeScheduler timer = VirtualTimeScheduler.create();
        ChatEventBatcher batcher = batcher(16, Duration.ofSeconds(1), DataSize.ofBytes(600), timer);
        ChatEvent first = delta("x".repeat(220));
        ChatEvent second = delta("y".repeat(220));

        StepVerifier.create(batcher.batch(
                                Flux.concat(Flux.just(first, second), Mono.never()), this::isDelta).take(2))
                .assertNext(batch -> assertThat(batch.events()).containsExactly(first))
                .assertNext(batch -> assertThat(batch.events()).containsExactly(second))
                .verifyComplete();
    }

    @Test
    void flushesPendingRuntimeEventsBeforeControlEvent() {
        VirtualTimeScheduler timer = VirtualTimeScheduler.create();
        ChatEventBatcher batcher = batcher(16, Duration.ofSeconds(1), DataSize.ofMegabytes(1), timer);
        ChatEvent completed = MessageCompletedEvent.of("run1", "session1");

        StepVerifier.create(batcher.batch(Flux.concat(
                                Flux.just(delta("a"), delta("b"), completed), Mono.never()),
                        this::isDelta).take(2))
                .assertNext(batch -> assertThat(batch.events()).extracting(ChatEvent::type)
                        .containsExactly("message.delta", "message.delta"))
                .assertNext(batch -> {
                    assertThat(batch.events()).containsExactly(completed);
                    assertThat(batch.batchable()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void batchesIntentProcessEventsAndFlushesBeforeIntentResult() {
        VirtualTimeScheduler timer = VirtualTimeScheduler.create();
        ChatEventBatcher batcher = batcher(16, Duration.ofSeconds(1), DataSize.ofMegabytes(1), timer);
        ChatEvent progress = intentProcessEvent("intent-progress", false);
        ChatEvent delta = intentProcessEvent("intent-delta", true);
        ChatEvent result = intentProcessEvent("intent-result", false);

        StepVerifier.create(batcher.batch(
                                Flux.concat(Flux.just(progress, delta, result), Mono.never()),
                                ChatEventPipeline::batchableIntentProcessEvent).take(2))
                .assertNext(batch -> {
                    assertThat(batch.databaseBatch()).isTrue();
                    assertThat(batch.events()).containsExactly(progress, delta);
                })
                .assertNext(batch -> {
                    assertThat(batch.databaseBatch()).isFalse();
                    assertThat(batch.events()).containsExactly(result);
                })
                .verifyComplete();
    }

    @Test
    void disabledBatchingPreservesSingleEventPersistenceUnits() {
        ChatStreamProperties properties = properties(16, Duration.ofMillis(20), DataSize.ofKilobytes(256));
        properties.setEventBatchEnabled(false);
        ChatEventBatcher batcher = new ChatEventBatcher(properties, new ObjectMapper(),
                VirtualTimeScheduler.create());

        StepVerifier.create(batcher.batch(Flux.just(delta("a"), delta("b")), this::isDelta))
                .assertNext(batch -> assertThat(batch.events()).hasSize(1))
                .assertNext(batch -> assertThat(batch.events()).hasSize(1))
                .verifyComplete();
    }

    @Test
    void doesNotRequestPastImmediateControlEventBeforeDownstreamRequestsNextBatch() {
        VirtualTimeScheduler timer = VirtualTimeScheduler.create();
        ChatEventBatcher batcher = batcher(16, Duration.ofSeconds(1), DataSize.ofMegabytes(1), timer);
        AtomicBoolean nextStageSubscribed = new AtomicBoolean();
        ChatEvent control = com.huawei.it.ex.one.domain.chat.RunStartedEvent.of("run1", "session1");
        Flux<ChatEvent> source = Flux.concat(
                Flux.just(control),
                Flux.defer(() -> {
                    nextStageSubscribed.set(true);
                    return Flux.just(delta("after-control"));
                }));

        StepVerifier.create(batcher.batch(source, this::isDelta), 1)
                .assertNext(batch -> {
                    assertThat(batch.events()).containsExactly(control);
                    assertThat(nextStageSubscribed).isFalse();
                })
                .thenCancel()
                .verify();
    }

    private ChatEventBatcher batcher(int maxSize, Duration maxWait, DataSize maxBytes,
                                     VirtualTimeScheduler timer) {
        return new ChatEventBatcher(properties(maxSize, maxWait, maxBytes), new ObjectMapper(), timer);
    }

    private ChatStreamProperties properties(int maxSize, Duration maxWait, DataSize maxBytes) {
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setEventBatchMaxSize(maxSize);
        properties.setEventBatchMaxWait(maxWait);
        properties.setEventBatchMaxBytes(maxBytes);
        return properties;
    }

    private ChatEvent delta(String value) {
        return MessageDeltaEvent.of("run1", "session1", value);
    }

    private ChatEvent intentProcessEvent(String sourceType, boolean thinking) {
        Map<String, Object> payload = Map.of(
                "source", "intent-agent",
                "sourceType", sourceType,
                thinking ? "text" : "message", sourceType);
        return thinking
                ? RuntimeEvent.thinking("run1", "session1", payload)
                : RuntimeEvent.progress("run1", "session1", payload);
    }

    private boolean isDelta(ChatEvent event) {
        return event != null && "message.delta".equals(event.type());
    }
}
