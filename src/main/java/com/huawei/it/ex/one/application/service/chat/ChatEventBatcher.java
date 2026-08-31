/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.domain.chat.ChatEvent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 将同一 run 的普通 Runtime 事件按条数、等待时间和序列化字节数组成有序处理批次。
 *
 * <p>调用方负责提供可批量事件判定。控制事件会立即关闭当前缓冲并作为单事件批次输出，
 * 因此 run 准入、Interaction、拒答和终态事件仍可沿用原来的单事件事务。</p>
 */
@Component
public class ChatEventBatcher {
    private final boolean enabled;
    private final int maxSize;
    private final long maxBytes;
    private final Duration maxWait;
    private final ObjectMapper objectMapper;
    private final Scheduler timerScheduler;

    @Autowired
    public ChatEventBatcher(ChatStreamProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Schedulers.parallel());
    }

    ChatEventBatcher(ChatStreamProperties properties, ObjectMapper objectMapper, Scheduler timerScheduler) {
        ChatStreamProperties safeProperties = properties == null ? new ChatStreamProperties() : properties;
        this.enabled = safeProperties.isEventBatchEnabled();
        this.maxSize = safeProperties.requiredEventBatchMaxSize();
        this.maxWait = safeProperties.requiredEventBatchMaxWait();
        this.maxBytes = safeProperties.requiredEventBatchMaxBytes();
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.timerScheduler = timerScheduler == null ? Schedulers.parallel() : timerScheduler;
    }

    /**
     * 按三个阈值生成有序处理批次。
     *
     * @param source 已完成 run/session 身份校验的事件流。
     * @param batchable 判断事件是否属于 Relay/DomainAgent 普通运行事件。
     * @return 保持原始事件顺序的批次流。
     */
    public Flux<Batch> batch(Flux<ChatEvent> source, Predicate<ChatEvent> batchable) {
        if (source == null) {
            return Flux.empty();
        }
        Predicate<ChatEvent> safeBatchable = batchable == null ? ignored -> false : batchable;
        if (!enabled) {
            return source.map(event -> Batch.single(event, false));
        }
        return source.windowUntilChanged(safeBatchable::test)
                .concatMap(window -> window.switchOnFirst((signal, sameWindow) -> {
                    if (!signal.hasValue()) {
                        return Flux.empty();
                    }
                    if (!safeBatchable.test(signal.get())) {
                        return sameWindow.map(event -> Batch.single(event, false));
                    }
                    return batchRuntimeWindow(sameWindow);
                }), 0);
    }

    /** Splits an already materialized callback event segment using the normal event batch limits. */
    List<Batch> partitionImmediately(List<ChatEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        if (!enabled) {
            return events.stream()
                    .map(event -> Batch.single(event, false))
                    .toList();
        }
        return partition(events, ignored -> true);
    }

    private Flux<Batch> batchRuntimeWindow(Flux<ChatEvent> window) {
        return Flux.defer(() -> {
            AtomicLong bufferedBytes = new AtomicLong();
            return window.windowUntil(event -> reachesByteThreshold(event, bufferedBytes))
                    .concatMap(byteWindow -> byteWindow.bufferTimeout(maxSize, maxWait, timerScheduler, true)
                            .concatMapIterable(events -> partition(events, ignored -> true)), 0);
        });
    }

    private boolean reachesByteThreshold(ChatEvent event, AtomicLong bufferedBytes) {
        long eventBytes = serializedBytes(event);
        long nextBytes = saturatedAdd(bufferedBytes.get(), eventBytes);
        if (nextBytes >= maxBytes) {
            bufferedBytes.set(0L);
            return true;
        }
        bufferedBytes.set(nextBytes);
        return false;
    }

    private List<Batch> partition(List<ChatEvent> events, Predicate<ChatEvent> batchable) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<Batch> result = new ArrayList<>();
        List<ChatEvent> current = new ArrayList<>(Math.min(maxSize, events.size()));
        long currentBytes = 0L;
        for (ChatEvent event : events) {
            if (!batchable.test(event)) {
                addBatch(result, current);
                current = new ArrayList<>(Math.min(maxSize, events.size()));
                currentBytes = 0L;
                result.add(Batch.single(event, false));
                continue;
            }
            long eventBytes = serializedBytes(event);
            if (!current.isEmpty() && (current.size() >= maxSize
                    || saturatedAdd(currentBytes, eventBytes) > maxBytes)) {
                addBatch(result, current);
                current = new ArrayList<>(Math.min(maxSize, events.size()));
                currentBytes = 0L;
            }
            current.add(event);
            currentBytes = saturatedAdd(currentBytes, eventBytes);
            if (current.size() >= maxSize || currentBytes >= maxBytes) {
                addBatch(result, current);
                current = new ArrayList<>(Math.min(maxSize, events.size()));
                currentBytes = 0L;
            }
        }
        addBatch(result, current);
        return List.copyOf(result);
    }

    private void addBatch(List<Batch> result, List<ChatEvent> events) {
        if (events != null && !events.isEmpty()) {
            result.add(new Batch(events, true));
        }
    }

    private long serializedBytes(ChatEvent event) {
        Map<String, Object> wireEvent = new LinkedHashMap<>();
        wireEvent.put("runId", event == null ? null : event.runId());
        wireEvent.put("sessionId", event == null ? null : event.sessionId());
        wireEvent.put("sequence", Long.MAX_VALUE);
        wireEvent.put("type", event == null ? null : event.type());
        wireEvent.put("createdAt", event == null || event.createdAt() == null
                ? null : event.createdAt().toString());
        wireEvent.put("payload", event == null || event.payload() == null ? Map.of() : event.payload());
        try {
            return objectMapper.writeValueAsBytes(wireEvent).length;
        } catch (JsonProcessingException ex) {
            // 让不可估算的事件立即走单事件持久化，由既有 payload 序列化失败链路统一收口 run.failed。
            return maxBytes;
        }
    }

    private long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    /**
     * 一个事件处理单元。batchable=false 表示必须沿用原单事件业务路径。
     */
    public record Batch(List<ChatEvent> events, boolean batchable) {
        public Batch {
            events = events == null ? List.of() : List.copyOf(events);
        }

        static Batch single(ChatEvent event, boolean batchable) {
            return new Batch(List.of(event), batchable);
        }

        public boolean databaseBatch() {
            return batchable && events.size() > 1;
        }
    }
}
