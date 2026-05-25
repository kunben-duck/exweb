package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.huawei.finance.front.one.application.config.AgentRuntimeForwardCookieProperties;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 真实 Relay streamable-http API adapter。
 *
 * <p>该 adapter 使用 FinanceEX 内部 Relay HTTP 协议：请求体直接是标准
 * {@link AgentRuntimeRequest}，下游响应按字符串 delta 流返回。它是正式上线默认 adapter。</p>
 */
@Component
@EnableConfigurationProperties({RelayAgentProperties.class, AgentRuntimeForwardCookieProperties.class})
@ConditionalOnExpression("'${financeex.agent-runtime.provider:relay}' == 'relay'")
public class RelayStreamHttpRuntimeAdapter implements RelayRuntimeProtocolAdapter {
    private static final Logger log = LoggerFactory.getLogger(RelayStreamHttpRuntimeAdapter.class);

    private final WebClient.Builder webClientBuilder;
    private final RelayAgentProperties properties;
    private final AgentRuntimeForwardCookieProperties forwardCookieProperties;

    public RelayStreamHttpRuntimeAdapter(WebClient.Builder webClientBuilder, RelayAgentProperties properties,
                                         AgentRuntimeForwardCookieProperties forwardCookieProperties) {
        this.webClientBuilder = webClientBuilder;
        this.properties = properties;
        this.forwardCookieProperties = forwardCookieProperties;
    }

    @Override
    public Set<String> adapterNames() {
        return Set.of("relay-stream-http");
    }

    @Override
    public Flux<ChatEvent> query(AgentRuntimeRequest request) {
        WebClient.RequestBodySpec spec = webClientBuilder.baseUrl(properties.getBaseUrl())
                .build()
                .post()
                .uri(properties.getStreamPath());
        applyForwardedCookie(spec, request.forwardHeaders());
        Flux<String> deltas = spec.bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(properties.getTimeout());
        return deltas
                .map(delta -> (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), delta))
                .concatWithValues(MessageCompletedEvent.of(request.runId(), request.sessionId()));
    }

    @Override
    public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
        if (!properties.isCancelSupported() || properties.getStopPath() == null || properties.getStopPath().isBlank()) {
            return Mono.empty();
        }
        String path = properties.getStopPath().replace("{runId}", request.runId() == null ? "" : request.runId());
        WebClient.RequestBodySpec spec = webClientBuilder.baseUrl(properties.getBaseUrl())
                .build()
                .post()
                .uri(path);
        applyForwardedCookie(spec, request.forwardHeaders());
        return spec.bodyValue(request)
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
         * Cookie 只进入出站请求头，AgentRuntimeRequest.forwardHeaders 已被 @JsonIgnore 标记，
         * 因此不会进入 Relay 请求体、事件 payload 或持久化 metadata。
         */
        spec.headers(headers -> headers.set(HttpHeaders.COOKIE, forwardHeaders.cookieHeader()));
    }
}
