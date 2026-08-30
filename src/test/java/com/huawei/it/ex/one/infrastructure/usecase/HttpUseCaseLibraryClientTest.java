/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.IntegrationAuthProperties;
import com.huawei.it.ex.one.application.integration.usecase.UseCaseMatchRequest;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;
import com.huawei.it.ex.one.domain.usecase.UseCaseMatchResult;
import com.huawei.it.ex.one.infrastructure.auth.NoopAuthHeaderProvider;
import com.huawei.it.ex.one.infrastructure.auth.SgovAuthHeaderProvider;

import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

class HttpUseCaseLibraryClientTest {
    @Test
    void appliesConfiguredOutboundAuthorizationHeader() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body("""
                                    {"matched":false,"score":0.0,"domainAgentId":null,"reason":"none","slots":{},"raw":{}}
                                    """)
                            .build());
                });
        HttpUseCaseLibraryClient client = new HttpUseCaseLibraryClient(
                builder, "http://usecase.test", "/match", Duration.ofSeconds(1), authHeaders());

        UseCaseMatchResult result = client.match(new UseCaseMatchRequest(
                "tenant1", "user1", "session1", "hello", List.of(), null, Map.of()));

        assertThat(result.matched()).isFalse();
        assertThat(captured.get().headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer usecase-token");
    }

    private AuthHeaderProviderRegistry authHeaders() {
        IntegrationAuthProperties properties = new IntegrationAuthProperties();
        properties.setEnabled(true);
        return new AuthHeaderProviderRegistry(properties, List.of(
                new NoopAuthHeaderProvider(),
                new SgovAuthHeaderProvider(properties, (request, appId, secret) -> Optional.of("Bearer usecase-token"))
        ));
    }
}
