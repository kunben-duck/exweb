package com.huawei.it.ex.one.infrastructure.sessiontitle;

import com.huawei.it.ex.one.application.config.SessionTitleProperties;
import com.huawei.it.ex.one.application.integration.auth.AuthHeaderRequest;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleProvider;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleRequest;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;

import com.fasterxml.jackson.annotation.JsonProperty;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/** 默认会话标题 HTTP Provider。 */
public class DefaultSessionTitleProvider implements SessionTitleProvider {
    private static final String SERVICE_CODE = "session-title";
    private static final String OPERATION = "getTitle";

    private final WebClient webClient;
    private final SessionTitleProperties properties;
    private final AuthHeaderProviderRegistry authHeaders;
    private final Scheduler ioScheduler;

    public DefaultSessionTitleProvider(WebClient.Builder webClientBuilder,
                                       SessionTitleProperties properties,
                                       AuthHeaderProviderRegistry authHeaders,
                                       Scheduler ioScheduler) {
        this.webClient = webClientBuilder.baseUrl(properties.normalizedBaseUrl()).build();
        this.properties = properties;
        this.authHeaders = authHeaders;
        this.ioScheduler = ioScheduler;
    }

    @Override
    public Mono<String> generate(SessionTitleRequest request) {
        if (properties.normalizedTimeout() == null) {
            return Mono.error(new IllegalStateException("Session title timeout is not configured"));
        }
        return Mono.fromCallable(() -> resolveAuthHeaders(request))
                .subscribeOn(ioScheduler)
                .flatMap(headers -> requestTitle(request, headers))
                .timeout(properties.normalizedTimeout());
    }

    private Map<String, String> resolveAuthHeaders(SessionTitleRequest request) {
        return authHeaders.headers(new AuthHeaderRequest(
                request.tenantId(),
                request.userId(),
                SERVICE_CODE,
                OPERATION,
                properties.normalizedBaseUrl(),
                properties.normalizedPath(),
                null));
    }

    private Mono<String> requestTitle(SessionTitleRequest request, Map<String, String> headers) {
        SessionTitleWireRequest body = new SessionTitleWireRequest(
                request.sessionId(), request.queries(), request.language());
        return webClient.post()
                .uri(properties.normalizedPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(httpHeaders -> headers.forEach(httpHeaders::set))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(SessionTitleWireResponse.class)
                .switchIfEmpty(Mono.error(new IllegalStateException("Session title response is empty")))
                .map(SessionTitleWireResponse::title);
    }

    private record SessionTitleWireRequest(
            @JsonProperty("session_id") String sessionId,
            List<String> queries,
            String language
    ) {
    }

    private record SessionTitleWireResponse(String title) {
    }
}
