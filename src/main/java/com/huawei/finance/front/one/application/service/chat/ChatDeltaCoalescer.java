package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.application.config.ChatStreamProperties;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

/**
 * 将连续 {@code message.delta} 合并为较粗粒度事件的流式降压组件。
 *
 * <p>下游 Relay 可能按 token 甚至字符级输出。如果每个 token 都写 openGauss、更新 run、
 * 发布 Redis 和投递 WebSocket，高并发时会产生明显写放大。该组件只合并连续 delta；
 * 遇到 run.started、message.completed、run.completed、run.failed、run.cancelled 等边界事件时会先
 * flush 已缓存文本，再原样输出边界事件，保证前端协议和事件顺序不变。</p>
 */
@Service
public class ChatDeltaCoalescer {
    private static final Set<String> DELTA_PAYLOAD_ALLOWLIST = Set.of(
            "runtimeSessionId",
            "agentSessionId",
            "agentName",
            "sourceType",
            "timestamp",
            "finishReason"
    );

    private final ChatStreamProperties properties;

    public ChatDeltaCoalescer(ChatStreamProperties properties) {
        this.properties = properties;
    }

    /**
     * 对事件流应用 delta 合并。
     *
     * @param source 原始事件流。
     * @return 合并后的事件流；关闭配置时原样返回。
     */
    public Flux<ChatEvent> coalesce(Flux<ChatEvent> source) {
        if (!properties.isDeltaCoalesceEnabled()) {
            return source;
        }
        return Flux.create(sink -> {
            CoalescingState state = new CoalescingState(properties.normalizedDeltaCoalesceWindow(),
                    properties.normalizedDeltaCoalesceMaxChars(), sink);
            Disposable subscription = source.subscribe(
                    state::onNext,
                    error -> {
                        state.fail(error);
                    },
                    () -> {
                        state.complete();
                    }
            );
            sink.onDispose(() -> {
                state.close();
                subscription.dispose();
            });
        }, FluxSink.OverflowStrategy.ERROR);
    }

    private static boolean isDelta(ChatEvent event) {
        return event != null && "message.delta".equals(event.type());
    }

    private static String deltaText(ChatEvent event) {
        if (event == null || event.payload() == null) {
            return "";
        }
        Object value = event.payload().get("delta");
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 单次订阅的 delta 合并状态。
     *
     * <p>timer 与上游事件可能来自不同线程，因此所有状态变更都通过 synchronized 保护。</p>
     */
    private static final class CoalescingState {
        private final Duration window;
        private final int maxChars;
        private final FluxSink<ChatEvent> sink;
        private final StringBuilder delta = new StringBuilder();
        private final Map<String, Object> extraPayload = new LinkedHashMap<>();
        private String runId;
        private String sessionId;
        private Instant createdAt;
        private Disposable timer;
        private boolean closed;

        private CoalescingState(Duration window, int maxChars, FluxSink<ChatEvent> sink) {
            this.window = window;
            this.maxChars = maxChars;
            this.sink = sink;
        }

        private synchronized void onNext(ChatEvent event) {
            if (closed || sink.isCancelled()) {
                return;
            }
            if (!isDelta(event)) {
                flush();
                safeNext(event);
                return;
            }
            appendDelta(event);
            if (delta.length() >= maxChars || window.isZero()) {
                flush();
            } else if (timer == null || timer.isDisposed()) {
                timer = Schedulers.parallel().schedule(this::flush,
                        Math.max(1L, window.toMillis()), TimeUnit.MILLISECONDS);
            }
        }

        private void appendDelta(ChatEvent event) {
            if (!delta.isEmpty() && belongsToDifferentRun(event)) {
                /*
                 * 正常后台流一轮只对应一个 run。这里仍做防御性边界切分，避免异常上游把不同
                 * run/session 的 delta 连在一起时被合并成同一个前端事件。
                 */
                flush();
            }
            if (delta.isEmpty()) {
                runId = event.runId();
                sessionId = event.sessionId();
                createdAt = event.createdAt() == null ? Instant.now() : event.createdAt();
            }
            delta.append(deltaText(event));
            if (event.payload() != null) {
                event.payload().forEach((key, value) -> {
                    /*
                     * delta 合并只能保留 ChatService 标准 payload 字段。下游 Runtime 的原始
                     * JSON chunk 不允许在这里被“顺手”带到前端事件里。
                     */
                    if (DELTA_PAYLOAD_ALLOWLIST.contains(key) && value != null) {
                        extraPayload.put(key, value);
                    }
                });
            }
        }

        private boolean belongsToDifferentRun(ChatEvent event) {
            return event != null && (!equals(runId, event.runId()) || !equals(sessionId, event.sessionId()));
        }

        private boolean equals(String left, String right) {
            return left == null ? right == null : left.equals(right);
        }

        private synchronized void flush() {
            if (closed || sink.isCancelled()) {
                clear();
                return;
            }
            if (delta.isEmpty()) {
                cancelTimer();
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>(extraPayload);
            payload.put("delta", delta.toString());
            safeNext(new MessageDeltaEvent(runId, sessionId, 0,
                    createdAt == null ? Instant.now() : createdAt,
                    delta.toString(), Map.copyOf(payload)));
            clear();
        }

        private synchronized void complete() {
            if (closed) {
                return;
            }
            flush();
            closed = true;
            cancelTimer();
            if (!sink.isCancelled()) {
                sink.complete();
            }
        }

        private synchronized void fail(Throwable error) {
            if (closed) {
                return;
            }
            /*
             * 上游错误发生前已经收到的 delta 仍是用户可见事实，先 flush 再传播错误。
             * 外层会把错误转换成 run.failed，最终事件顺序为 delta* -> run.failed。
             */
            flush();
            closed = true;
            cancelTimer();
            if (!sink.isCancelled()) {
                sink.error(error);
            }
        }

        private synchronized void close() {
            closed = true;
            cancelTimer();
            delta.setLength(0);
            extraPayload.clear();
        }

        private void safeNext(ChatEvent event) {
            if (!closed && !sink.isCancelled()) {
                sink.next(event);
            }
        }

        private synchronized void cancelTimer() {
            if (timer != null && !timer.isDisposed()) {
                timer.dispose();
            }
            timer = null;
        }

        private void clear() {
            cancelTimer();
            delta.setLength(0);
            extraPayload.clear();
            runId = null;
            sessionId = null;
            createdAt = null;
        }
    }
}
