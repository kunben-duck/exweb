package com.huawei.finance.front.one.infrastructure.domainagent;

import com.huawei.finance.front.one.application.config.DomainAgentProperties;
import com.huawei.finance.front.one.application.integration.agent.DomainAgentClient;
import com.huawei.finance.front.one.application.integration.agent.DomainAgentRequest;
import com.huawei.finance.front.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 配置化 DomainAgent HTTP adapter。
 *
 * <p>DomainAgent 是前端显式选择的财经领域 Agent。ChatService 对外使用 {@code domainAgentId}，
 * 但下游 wire contract 仍要求字段名 {@code skillId}，该差异只在本 adapter 内部转换。</p>
 */
@Component
@EnableConfigurationProperties(DomainAgentProperties.class)
public class ConfiguredDomainAgentClient implements DomainAgentClient {
    private static final Logger log = LoggerFactory.getLogger(ConfiguredDomainAgentClient.class);

    private final WebClient.Builder webClientBuilder;
    private final DomainAgentProperties properties;
    private final DomainAgentChatRequestMapper requestMapper;
    private final DomainAgentResponseNormalizer responseNormalizer;

    public ConfiguredDomainAgentClient(WebClient.Builder webClientBuilder,
                                       DomainAgentProperties properties,
                                       DomainAgentChatRequestMapper requestMapper,
                                       DomainAgentResponseNormalizer responseNormalizer) {
        this.webClientBuilder = webClientBuilder;
        this.properties = properties;
        this.requestMapper = requestMapper;
        this.responseNormalizer = responseNormalizer;
    }

    @Override
    public Flux<ChatEvent> query(DomainAgentRequest request) {
        validate(request);
        Map<String, Object> body = requestMapper.toWireRequest(request);
        return enforceDomainAgentDeadline(Flux.defer(() -> {
            DomainAgentResponseNormalizer.DomainAgentStreamState streamState = responseNormalizer.newStreamState();
            return webClientBuilder.build()
                    .post()
                    .uri(fullUrl(properties.getChatPath()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_NDJSON, MediaType.APPLICATION_JSON)
                    .headers(headers -> applyForwardedCookie(headers, request.forwardHeaders()))
                    .bodyValue(body)
                    .retrieve()
                    /*
                     * DomainAgent 使用非标准的 "message: {...}" 私有 eventStream 帧。WebClient 在
                     * text/event-stream 下按标准 SSE 解码 String 时只认 data 行，可能吞掉 message 行。
                     * 因此这里读取原始 DataBuffer，再交给 DomainAgentResponseNormalizer 兼容 message/data/plain JSON。
                     */
                    .bodyToFlux(DataBuffer.class)
                    .map(this::readUtf8)
                    .timeout(properties.getTimeout())
                    .flatMapIterable(chunk -> responseNormalizer.normalize(
                            request.runId(), request.sessionId(), chunk, streamState))
                    .concatWith(Flux.defer(() -> Flux.fromIterable(
                            responseNormalizer.finish(request.runId(), request.sessionId(), streamState))))
                    /*
                     * DomainAgent 的 endFlag=true 已经映射为 message.completed。收到后主动闭合本轮流，
                     * 避免下游 HTTP 连接未关闭时持续占用本机 bulkhead 和 WebClient 资源。
                     */
                    .takeUntil(event -> "message.completed".equals(event.type()));
        }));
    }

    @Override
    public Mono<Void> cancel(DomainAgentCancelRequest request) {
        if (!properties.isEnabled() || properties.getStopPath() == null || properties.getStopPath().isBlank()) {
            return Mono.empty();
        }
        Map<String, Object> body = Map.of(
                "runId", request.runId(),
                "sessionId", request.sessionId(),
                "skillId", request.domainAgentId() == null ? "" : request.domainAgentId(),
                "reason", request.reason() == null ? "" : request.reason()
        );
        return webClientBuilder.build()
                .post()
                .uri(fullUrl(properties.getStopPath()))
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyForwardedCookie(headers, request.forwardHeaders()))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(properties.getTimeout())
                .onErrorResume(ex -> {
                    log.warn("DomainAgent cancel failed. runId={}, reason={}", request.runId(), ex.getMessage());
                    return Mono.empty();
                });
    }

    private void validate(DomainAgentRequest request) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("DOMAIN_AGENT_DISABLED: DomainAgent 服务未启用");
        }
        if (!properties.domainAgentAllowed(request.domainAgentId())) {
            throw new IllegalArgumentException("非法或未授权 domainAgentId: " + request.domainAgentId());
        }
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("DOMAIN_AGENT_BASE_URL_MISSING: DomainAgent 服务地址未配置");
        }
    }

    private void applyForwardedCookie(HttpHeaders headers, RuntimeForwardHeaders forwardHeaders) {
        if (forwardHeaders == null || !forwardHeaders.hasCookie()) {
            return;
        }
        /*
         * Cookie 只作为 DomainAgent 出站 HTTP 请求头透传。DomainAgent wire body 由
         * DomainAgentChatRequestMapper 生成，不包含 forwardHeaders，避免企业登录态落入请求体、
         * metadata、事件 payload 或日志。
         */
        headers.set(HttpHeaders.COOKIE, forwardHeaders.cookieHeader());
    }

    private String fullUrl(String path) {
        String baseUrl = properties.getBaseUrl().trim();
        String nextPath = path == null ? "" : path.trim();
        if (nextPath.startsWith("http://") || nextPath.startsWith("https://")) {
            return nextPath;
        }
        if (baseUrl.endsWith("/") && nextPath.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + nextPath;
        }
        if (!baseUrl.endsWith("/") && !nextPath.startsWith("/")) {
            return baseUrl + "/" + nextPath;
        }
        return baseUrl + nextPath;
    }

    private String readUtf8(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private Flux<ChatEvent> enforceDomainAgentDeadline(Flux<ChatEvent> source) {
        Duration timeout = properties.getTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return source;
        }
        return Flux.create(sink -> {
            AtomicBoolean terminated = new AtomicBoolean(false);
            var timer = Schedulers.parallel().schedule(() -> {
                if (terminated.compareAndSet(false, true) && !sink.isCancelled()) {
                    sink.error(new TimeoutException("DomainAgent stream timed out after " + timeout));
                }
            }, Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
            var upstream = source.subscribe(
                    event -> {
                        if (!terminated.get() && !sink.isCancelled()) {
                            sink.next(event);
                        }
                    },
                    error -> {
                        if (terminated.compareAndSet(false, true) && !sink.isCancelled()) {
                            timer.dispose();
                            sink.error(error);
                        }
                    },
                    () -> {
                        if (terminated.compareAndSet(false, true) && !sink.isCancelled()) {
                            timer.dispose();
                            sink.complete();
                        }
                    });
            sink.onDispose(() -> {
                terminated.set(true);
                timer.dispose();
                upstream.dispose();
            });
        });
    }
}
