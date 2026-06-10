package com.huawei.finance.front.one.application.service.runtime;

import com.huawei.finance.front.one.application.config.RuntimeRawStreamLogProperties;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.conversation.RuntimeRawStreamLogPublisher;
import com.huawei.finance.front.one.domain.chat.RuntimeRawStreamChunk;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Runtime 原始流响应捕获服务。
 *
 * <p>该服务位于 Relay normalizer 之前，但它只做极轻量的 raw chunk 发布：不做合并、
 * 脱敏、hash、分片或数据库写入。所有重处理都交给 MQ 消费端完成，避免诊断日志拖慢
 * ChatEvent 入库和 WebSocket 实时推送。</p>
 */
@Service
public class RuntimeRawStreamLogService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeRawStreamLogService.class);

    private final RuntimeRawStreamLogProperties properties;
    private final RuntimeRawStreamLogPublisher publisher;

    public RuntimeRawStreamLogService(RuntimeRawStreamLogProperties properties,
                                      RuntimeRawStreamLogPublisher publisher) {
        this.properties = properties;
        this.publisher = publisher;
    }

    /**
     * 捕获 Runtime 原始响应，并原样透传给 normalizer。
     *
     * <p>发布失败会被吞掉并记录 warn。raw log 是排障旁路，不能改变 Runtime 主响应流。</p>
     *
     * @param source 下游返回的原始文本流。
     * @param request 本轮 Runtime 请求，提供 run/session/owner 归属。
     * @param runtimeProvider Runtime provider，例如 relay。
     * @param apiAdapter API adapter，例如 relay-stream-http。
     * @return 原样透传的文本流。
     */
    public Flux<String> capture(Flux<String> source, AgentRuntimeRequest request,
                                String runtimeProvider, String apiAdapter) {
        if (!properties.isEnabled() || properties.isDisabledTransport() || request == null || publisher == null) {
            return source;
        }
        return Flux.defer(() -> {
            AtomicLong chunkIndex = new AtomicLong();
            return source.doOnNext(chunk -> publishSafely(request, runtimeProvider, apiAdapter,
                    chunkIndex.incrementAndGet(), chunk));
        });
    }

    private void publishSafely(AgentRuntimeRequest request, String runtimeProvider, String apiAdapter,
                               long chunkIndex, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        try {
            RuntimeRawStreamChunk rawChunk = new RuntimeRawStreamChunk(
                    request.tenantId(),
                    request.userId(),
                    request.sessionId(),
                    request.runId(),
                    runtimeProvider,
                    apiAdapter,
                    chunkIndex,
                    chunk,
                    chunk.length(),
                    false,
                    RuntimeRawStreamTerminalDetector.isTerminalChunk(chunk),
                    Instant.now()
            );
            publisher.publish(rawChunk);
        } catch (RuntimeException ex) {
            log.warn("Runtime raw stream chunk publish failed. runId={}, chunkIndex={}, reason={}",
                    request.runId(), chunkIndex, ex.getMessage());
        }
    }
}
