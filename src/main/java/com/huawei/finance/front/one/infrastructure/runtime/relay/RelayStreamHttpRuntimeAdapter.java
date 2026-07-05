package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.huawei.finance.front.one.application.config.AgentRuntimeForwardCookieProperties;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 真实 Relay streamable-http API adapter。
 *
 * <p>该 adapter 是 Relay provider 的 HTTP 防腐层：请求体使用 Relay 专用 wire DTO，
 * 响应先归一化为 ChatService 标准 ChatEvent，再交给应用层持久化和推送。前端不会看到
 * Relay 原始 chunk。</p>
 */
@Component
@EnableConfigurationProperties({RelayAgentProperties.class, AgentRuntimeForwardCookieProperties.class})
@ConditionalOnExpression("'${financeex.agent-runtime.provider:relay}' == 'relay'")
public class RelayStreamHttpRuntimeAdapter implements RelayRuntimeProtocolAdapter {
    private static final Logger log = LoggerFactory.getLogger(RelayStreamHttpRuntimeAdapter.class);

    private final WebClient.Builder webClientBuilder;
    private final RelayAgentProperties properties;
    private final AgentRuntimeForwardCookieProperties forwardCookieProperties;
    private final RelayRuntimeResponseNormalizer responseNormalizer;
    private final ExchangeStrategies relayExchangeStrategies;

    @Autowired
    public RelayStreamHttpRuntimeAdapter(WebClient.Builder webClientBuilder, RelayAgentProperties properties,
                                         AgentRuntimeForwardCookieProperties forwardCookieProperties,
                                         RelayRuntimeResponseNormalizer responseNormalizer) {
        this.webClientBuilder = webClientBuilder;
        this.properties = properties;
        this.forwardCookieProperties = forwardCookieProperties;
        this.responseNormalizer = responseNormalizer;
        this.relayExchangeStrategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(relayMaxInMemoryBytes()))
                .build();
    }

    @Override
    public Set<String> adapterNames() {
        return Set.of("relay-stream-http");
    }

    @Override
    public Flux<ChatEvent> query(AgentRuntimeRequest request) {
        WebClient.RequestBodySpec spec = relayWebClient()
                .build()
                .post()
                .uri(properties.getStreamPath());
        applyForwardedCookie(spec, request.forwardHeaders());
        AtomicBoolean completed = new AtomicBoolean(false);
        Flux<String> chunks = spec.bodyValue(RelayRuntimeWireRequestMapper.toQueryWireRequest(request))
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(properties.getTimeout());
        Flux<ChatEvent> normalized = chunks
                .concatMap(chunk -> Flux.fromIterable(responseNormalizer.normalize(
                        request.runId(), request.sessionId(), chunk)))
                // 下游一旦声明消息完成，本轮 Runtime 流即可闭合；后续异常帧不再进入前端事件流。
                .takeUntil(event -> "message.completed".equals(event.type()))
                .doOnNext(event -> {
                    if ("message.completed".equals(event.type())) {
                        completed.set(true);
                    }
                });
        return enforceRuntimeDeadline(normalized)
                .concatWith(Mono.defer(() -> completed.get()
                        ? Mono.empty()
                        : Mono.just(MessageCompletedEvent.of(request.runId(), request.sessionId()))));
    }

    @Override
    public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
        if (!properties.isCancelSupported() || properties.getStopPath() == null || properties.getStopPath().isBlank()) {
            return Mono.empty();
        }
        String path = properties.getStopPath().replace("{runId}", request.runId() == null ? "" : request.runId());
        WebClient.RequestBodySpec spec = relayWebClient()
                .build()
                .post()
                .uri(path);
        applyForwardedCookie(spec, request.forwardHeaders());
        return spec.bodyValue(RelayRuntimeWireRequestMapper.toCancelWireRequest(request))
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(properties.getTimeout())
                .onErrorResume(ex -> {
                    log.warn("Relay stream-http cancel failed, runId={}, reason={}", request.runId(), ex.getMessage());
                    return Mono.empty();
                });
    }

    private void applyForwardedCookie(WebClient.RequestHeadersSpec<?> spec, RuntimeForwardHeaders forwardHeaders) {
        if (!forwardCookieProperties.isAdapterAllowed("relay-stream-http")
                || forwardHeaders == null || !forwardHeaders.hasCookie()) {
            return;
        }
        /*
         * Cookie 只进入出站请求头；请求体由 RelayRuntimeWireRequestMapper 映射为 Relay 专用 DTO，
         * 因此不会进入 Relay 请求体、事件 payload 或持久化 metadata。
         */
        spec.headers(headers -> headers.set(HttpHeaders.COOKIE, forwardHeaders.cookieHeader()));
    }

    private Flux<ChatEvent> enforceRuntimeDeadline(Flux<ChatEvent> source) {
        Duration timeout = properties.getTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return source;
        }
        return Flux.create(sink -> {
            AtomicBoolean terminated = new AtomicBoolean(false);
            var timer = Schedulers.parallel().schedule(() -> {
                if (terminated.compareAndSet(false, true) && !sink.isCancelled()) {
                    sink.error(new TimeoutException("Relay runtime stream timed out after " + timeout));
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

    private WebClient.Builder relayWebClient() {
        return webClientBuilder.clone()
                .baseUrl(properties.getBaseUrl())
                .exchangeStrategies(relayExchangeStrategies);
    }

    private int relayMaxInMemoryBytes() {
        DataSize size = properties.getMaxInMemorySize();
        if (size == null || size.toBytes() <= 0) {
            throw new IllegalArgumentException("financeex.agent-runtime.max-in-memory-size must be greater than 0");
        }
        if (size.toBytes() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "financeex.agent-runtime.max-in-memory-size must not exceed " + Integer.MAX_VALUE + " bytes");
        }
        return (int) size.toBytes();
    }
}
