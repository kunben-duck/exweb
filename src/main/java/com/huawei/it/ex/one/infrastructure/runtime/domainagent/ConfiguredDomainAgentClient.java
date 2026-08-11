package com.huawei.it.ex.one.infrastructure.runtime.domainagent;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentClient;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.chat.ChatEvent;

import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 配置化 DomainAgent HTTP adapter。
 *
 * <p>DomainAgent 是前端显式选择的财经领域 Agent。ChatService 只负责路由、鉴权和附件引用校验；
 * 下游 chat body 使用前端 {@code metadata} 的安全副本，不在后端重组业务字段。</p>
 */
@Component
@EnableConfigurationProperties(DomainAgentProperties.class)
public class ConfiguredDomainAgentClient implements DomainAgentClient {
    private static final AppLogger log = AppLoggerFactory.getLogger(ConfiguredDomainAgentClient.class);

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
        Flux<ChatEvent> source = Flux.defer(() -> {
            DomainAgentResponseNormalizer.DomainAgentStreamState streamState = responseNormalizer.newStreamState();
            DomainAgentUtf8StreamDecoder utf8Decoder = new DomainAgentUtf8StreamDecoder();
            return webClientBuilder.build()
                    .post()
                    .uri(fullUrl(properties.getChatPath()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_NDJSON, MediaType.APPLICATION_JSON)
                    .headers(headers -> applyOutboundHeaders(headers, request.forwardHeaders()))
                    .bodyValue(body)
                    .retrieve()
                    /*
                     * DomainAgent 使用非标准的 "message: {...}" 私有 eventStream 帧。WebClient 在
                     * text/event-stream 下按标准 SSE 解码 String 时只认 data 行，可能吞掉 message 行。
                     * 因此这里读取原始 DataBuffer，再交给 DomainAgentResponseNormalizer 兼容 message/data/plain JSON。
                     */
                    .bodyToFlux(DataBuffer.class)
                    .timeout(properties.getStreamIdleTimeout(), Flux.error(domainAgentTimeout(
                            "IDLE", properties.getStreamIdleTimeout())))
                    .map(utf8Decoder::decode)
                    .concatWith(Mono.fromSupplier(utf8Decoder::finish))
                    .flatMapIterable(chunk -> responseNormalizer.normalize(
                            request.runId(), request.sessionId(), chunk, streamState))
                    .concatWith(Flux.defer(() -> Flux.fromIterable(
                            responseNormalizer.finish(request.runId(), request.sessionId(), streamState))))
                    /*
                     * DomainAgent 的 endFlag=true 已经映射为 message.completed。收到后主动闭合本轮流，
                     * 避免下游 HTTP 连接未关闭时持续占用本机 bulkhead 和 WebClient 资源。
                     */
                    .takeUntil(event -> "message.completed".equals(event.type()));
        });
        return enforceDomainAgentTotalDeadline(source)
                .doOnError(ex -> log.warn(SystemErrorLogEntry.builder(classifyDomainAgentFailure(ex),
                                "DomainAgent response stream failed")
                        .runId(request.runId())
                        .sessionId(request.sessionId())
                        .operation("domain-agent.query")
                        .build(), ex));
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
                .headers(headers -> applyOutboundHeaders(headers, request.forwardHeaders()))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(properties.getTimeout())
                .onErrorResume(ex -> {
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DOMAIN_AGENT_CANCEL_FAILED,
                                    "DomainAgent cancellation request failed")
                            .runId(request.runId())
                            .sessionId(request.sessionId())
                            .operation("domain-agent.cancel")
                            .build(), ex);
                    return Mono.empty();
                });
    }

    private void validate(DomainAgentRequest request) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("DOMAIN_AGENT_DISABLED: DomainAgent 服务未启用");
        }
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("DOMAIN_AGENT_BASE_URL_MISSING: DomainAgent 服务地址未配置");
        }
    }

    private void applyOutboundHeaders(HttpHeaders headers, RuntimeForwardHeaders forwardHeaders) {
        String referer = properties.normalizedReferer();
        if (!referer.isBlank()) {
            headers.set(HttpHeaders.REFERER, referer);
        }
        if (forwardHeaders == null || !forwardHeaders.hasCookie()) {
            return;
        }
        /*
         * Cookie 只作为 DomainAgent 出站 HTTP 请求头透传。DomainAgent wire body 来自
         * 已校验的前端 metadata，不包含 forwardHeaders，避免企业登录态落入请求体、
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

    private Flux<ChatEvent> enforceDomainAgentTotalDeadline(Flux<ChatEvent> source) {
        Duration timeout = properties.getStreamTotalTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return source;
        }
        return Flux.create(sink -> {
            AtomicBoolean terminated = new AtomicBoolean(false);
            var timer = Schedulers.parallel().schedule(() -> {
                if (terminated.compareAndSet(false, true) && !sink.isCancelled()) {
                    sink.error(domainAgentTimeout("TOTAL", timeout));
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

    private TimeoutException domainAgentTimeout(String type, Duration timeout) {
        return new TimeoutException("DomainAgent stream " + type + " timeout after " + timeout);
    }

    private SystemErrorCode classifyDomainAgentFailure(Throwable failure) {
        Throwable cause = Exceptions.unwrap(failure);
        if (cause instanceof TimeoutException) {
            return SystemErrorCode.DOMAIN_AGENT_TIMEOUT;
        }
        if (cause instanceof DomainAgentProtocolException) {
            return SystemErrorCode.PROTOCOL_INVALID;
        }
        if (cause instanceof WebClientResponseException response) {
            if (response.getStatusCode().value() == 429) {
                return SystemErrorCode.DOMAIN_AGENT_RATE_LIMITED;
            }
            return response.getStatusCode().is5xxServerError()
                    ? SystemErrorCode.DOMAIN_AGENT_HTTP_SERVER_ERROR
                    : SystemErrorCode.DOMAIN_AGENT_HTTP_CLIENT_ERROR;
        }
        if (cause instanceof WebClientRequestException) {
            return SystemErrorCode.DOMAIN_AGENT_UNAVAILABLE;
        }
        return SystemErrorCode.DOMAIN_AGENT_STREAM_FAILED;
    }
}
