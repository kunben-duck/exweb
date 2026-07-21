package com.huawei.it.ex.one.intent.compat.caselibrary.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.security.infrastructure.config.IntegrationAuthProperties;
import com.huawei.it.ex.one.intent.compat.caselibrary.model.UseCaseMatchRequest;
import com.huawei.it.ex.one.security.application.auth.AuthHeaderProviderRegistry;
import com.huawei.it.ex.one.intent.compat.caselibrary.model.UseCaseMatchResult;
import com.huawei.it.ex.one.security.infrastructure.auth.NoopAuthHeaderProvider;
import com.huawei.it.ex.one.security.infrastructure.auth.SgovAuthHeaderProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

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
