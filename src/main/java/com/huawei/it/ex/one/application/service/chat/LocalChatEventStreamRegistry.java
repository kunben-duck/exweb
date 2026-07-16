package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatStreamTopics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * 当前服务实例内的 run topic 在线事件发布器。
 *
 * <p>数据库是事件事实源，本发布器只负责把刚落库的事件推给当前 JVM 在线 WebSocket 连接。
 * 跨实例实时推送由 Redis Pub/Sub 完成，断线恢复仍以 {@code afterSeq} 从数据库补发为准。</p>
 */
@Component
public class LocalChatEventStreamRegistry {
    private static final AppLogger log = AppLoggerFactory.getLogger(LocalChatEventStreamRegistry.class);

    private final Map<String, TopicSink> topicSinks = new ConcurrentHashMap<>();

    /**
     * 发布一个已经落库的事件。
     *
     * @param event 带持久化 sequence 的聊天事件。
     */
    public void publish(ChatEvent event) {
        if (event == null || event.runId() == null || event.runId().isBlank()) {
            return;
        }
        publishTopic(ChatStreamTopics.runTopic(event.runId()), event, terminal(event));
    }

    /**
     * 订阅 run 级 stream topic。
     *
     * @param topicId run 级 stream topic。
     * @param afterSeq 只推送大于该序号的事件。
     * @return 在线事件流。
     */
    public Flux<ChatEvent> subscribeRunTopic(String topicId, long afterSeq) {
        TopicSink topic = topicSinks.computeIfAbsent(topicId, ignored -> new TopicSink());
        topic.subscribers().incrementAndGet();
        return topic.sink().asFlux()
                .filter(event -> event.sequence() > afterSeq)
                .doFinally(signalType -> {
                    if (topic.subscribers().decrementAndGet() <= 0) {
                        topicSinks.remove(topicId, topic);
                    }
                });
    }

    private void publishTopic(String topicId, ChatEvent event, boolean terminal) {
        TopicSink topic = topicSinks.get(topicId);
        if (topic == null) {
            return;
        }
        Sinks.EmitResult nextResult = topic.emitNext(event);
        if (nextResult.isFailure()) {
            handleEmitFailure(topicId, topic, event, nextResult);
            return;
        }
        if (terminal) {
            Sinks.EmitResult completeResult = topic.emitComplete();
            if (completeResult.isFailure()) {
                logEmitFailure("LOCAL_EMIT_COMPLETE_FAILED", topicId, event, completeResult);
            }
            if (topic.subscribers().get() <= 0) {
                topicSinks.remove(topicId, topic);
            }
        }
    }

    private void handleEmitFailure(String topicId, TopicSink topic, ChatEvent event, Sinks.EmitResult result) {
        logEmitFailure("LOCAL_EMIT_NEXT_FAILED", topicId, event, result);
        if (result == Sinks.EmitResult.FAIL_TERMINATED || result == Sinks.EmitResult.FAIL_CANCELLED) {
            if (topic.subscribers().get() <= 0) {
                topicSinks.remove(topicId, topic);
            }
            return;
        }
        /*
         * live sink 只负责实时投递，不能为了慢客户端保留历史事件。溢出或投递失败时主动
         * 通知订阅侧进入恢复流程；可靠补发始终由数据库 + Event Resume 完成。
         */
        Sinks.EmitResult errorResult = topic.emitError(new IllegalStateException(
                "run topic live sink emit failed, seq=" + event.sequence() + ", result=" + result));
        if (errorResult.isFailure()) {
            logEmitFailure("LOCAL_EMIT_ERROR_FAILED", topicId, event, errorResult);
        }
    }

    private void logEmitFailure(String reason, String topicId, ChatEvent event, Sinks.EmitResult result) {
        if (result == Sinks.EmitResult.FAIL_TERMINATED || result == Sinks.EmitResult.FAIL_CANCELLED) {
            log.debug("本机 run topic 已结束，reason={}, topicId={}, seq={}, result={}",
                    reason, topicId, event.sequence(), result);
            return;
        }
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.WEBSOCKET_SEND_FAILED,
                        "Local run topic event delivery failed")
                .runId(event.runId())
                .sessionId(event.sessionId())
                .operation("chat-event.publish.local-topic")
                .attribute("failureReason", reason)
                .attribute("topicId", topicId)
                .attribute("sequence", event.sequence())
                .attribute("emitResult", result)
                .build());
    }

    private boolean terminal(ChatEvent event) {
        return "run.completed".equals(event.type())
                || "run.failed".equals(event.type())
                || "run.cancelled".equals(event.type())
                || "run.waiting_user".equals(event.type());
    }

    private static final class TopicSink {
        private final Sinks.Many<ChatEvent> sink = Sinks.many().multicast()
                .onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);
        private final AtomicInteger subscribers = new AtomicInteger();

        private Sinks.Many<ChatEvent> sink() {
            return sink;
        }

        private AtomicInteger subscribers() {
            return subscribers;
        }

        private synchronized Sinks.EmitResult emitNext(ChatEvent event) {
            return sink.tryEmitNext(event);
        }

        private synchronized Sinks.EmitResult emitComplete() {
            return sink.tryEmitComplete();
        }

        private synchronized Sinks.EmitResult emitError(Throwable error) {
            return sink.tryEmitError(error);
        }
    }
}
