package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatStreamTopics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * 当前服务实例内的 run topic 在线事件发布器。
 *
 * <p>openGauss 是事件事实源，本发布器只负责把刚落库的事件推给当前 JVM 在线 WebSocket 连接。
 * 跨实例实时推送由 Redis Pub/Sub 完成，断线恢复仍以 {@code afterSeq} 从 openGauss 补发为准。</p>
 */
@Component
public class LocalChatEventStreamRegistry {
    private static final Logger log = LoggerFactory.getLogger(LocalChatEventStreamRegistry.class);

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
        Sinks.EmitResult nextResult = topic.sink().tryEmitNext(event);
        if (nextResult.isFailure()) {
            log.warn("本机 run topic 事件投递失败，topicId={}, seq={}, result={}",
                    topicId, event.sequence(), nextResult);
            /*
             * live sink 只负责实时投递，不能为了慢客户端保留历史事件。溢出或投递失败时主动
             * 通知订阅侧进入恢复流程；可靠补发始终由 openGauss + Event Resume 完成。
             */
            topic.sink().tryEmitError(new IllegalStateException(
                    "run topic live sink emit failed, seq=" + event.sequence() + ", result=" + nextResult));
        }
        if (terminal) {
            Sinks.EmitResult completeResult = topic.sink().tryEmitComplete();
            if (completeResult.isFailure()) {
                log.warn("本机 run topic 结束失败，topicId={}, result={}", topicId, completeResult);
            }
            if (topic.subscribers().get() <= 0) {
                topicSinks.remove(topicId, topic);
            }
        }
    }

    private boolean terminal(ChatEvent event) {
        return "run.completed".equals(event.type()) || "run.failed".equals(event.type()) || "run.cancelled".equals(event.type());
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
    }
}
