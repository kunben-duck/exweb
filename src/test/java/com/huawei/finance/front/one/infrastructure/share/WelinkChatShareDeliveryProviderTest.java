package com.huawei.finance.front.one.infrastructure.share;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.ChatShareDeliveryProperties;
import com.huawei.finance.front.one.application.config.IntegrationAuthProperties;
import com.huawei.finance.front.one.application.integration.share.ChatShareProviderDeliveryRequest;
import com.huawei.finance.front.one.application.integration.share.ChatShareProviderDeliveryResult;
import com.huawei.finance.front.one.application.service.auth.AuthHeaderProviderRegistry;
import com.huawei.finance.front.one.infrastructure.auth.NoopAuthHeaderProvider;
import com.huawei.finance.front.one.infrastructure.auth.SgovAuthHeaderProvider;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class WelinkChatShareDeliveryProviderTest {
    @Test
    void status200ResponseMeansSuccess() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WelinkChatShareDeliveryProvider provider = provider(captured, HttpStatus.OK, "{\"status\":\"200\"}");

        ChatShareProviderDeliveryResult result = provider.deliver(request());

        assertThat(result.success()).isTrue();
        assertThat(result.providerResponse()).containsEntry("status", "200");
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers().getFirst(HttpHeaders.CONTENT_TYPE)).contains("application/json");
    }

    @Test
    void nonSuccessProviderStatusIsFailedEvenWhenHttpIs2xx() {
        WelinkChatShareDeliveryProvider provider = provider(new AtomicReference<>(), HttpStatus.OK, "{\"status\":\"500\"}");

        ChatShareProviderDeliveryResult result = provider.deliver(request());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("WELINK_STATUS");
        assertThat(result.providerResponse()).containsEntry("status", "500");
    }

    @Test
    void non2xxHttpStatusIsFailed() {
        WelinkChatShareDeliveryProvider provider = provider(new AtomicReference<>(),
                HttpStatus.INTERNAL_SERVER_ERROR, "{\"status\":\"200\"}");

        ChatShareProviderDeliveryResult result = provider.deliver(request());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("WELINK_HTTP_STATUS");
    }

    @Test
    void appliesConfiguredOutboundAuthorizationHeader() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WelinkChatShareDeliveryProvider provider = providerWithAuth(captured, "Bearer sgov-token");

        ChatShareProviderDeliveryResult result = provider.deliver(request());

        assertThat(result.success()).isTrue();
        assertThat(captured.get().headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sgov-token");
        assertThat(captured.get().headers().toString()).doesNotContain("app-secret");
    }

    private WelinkChatShareDeliveryProvider provider(AtomicReference<ClientRequest> captured,
                                                    HttpStatus status,
                                                    String responseBody) {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(status)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body(responseBody)
                            .build());
                });
        ChatShareDeliveryProperties properties = new ChatShareDeliveryProperties();
        ChatShareDeliveryProperties.Welink welink = properties.getDelivery().getProviders().getWelink();
        welink.setEnabled(true);
        welink.setBaseUrl("http://welink.test");
        welink.setSendPath("/share/send");
        welink.setTimeout(Duration.ofSeconds(1));
        AuthHeaderProviderRegistry authHeaders = new AuthHeaderProviderRegistry(
                new IntegrationAuthProperties(), List.of(new NoopAuthHeaderProvider()));
        return new WelinkChatShareDeliveryProvider(builder, properties, new ObjectMapper(), authHeaders);
    }

    private WelinkChatShareDeliveryProvider providerWithAuth(AtomicReference<ClientRequest> captured, String token) {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body("{\"status\":\"200\"}")
                            .build());
                });
        ChatShareDeliveryProperties properties = new ChatShareDeliveryProperties();
        ChatShareDeliveryProperties.Welink welink = properties.getDelivery().getProviders().getWelink();
        welink.setEnabled(true);
        welink.setBaseUrl("http://welink.test");
        welink.setSendPath("/share/send");
        IntegrationAuthProperties authProperties = new IntegrationAuthProperties();
        authProperties.setEnabled(true);
        authProperties.getSgov().setAppId("app-id");
        authProperties.getSgov().setSecret("app-secret");
        AuthHeaderProviderRegistry authHeaders = new AuthHeaderProviderRegistry(authProperties, List.of(
                new NoopAuthHeaderProvider(),
                new SgovAuthHeaderProvider(authProperties, (request, appId, secret) -> java.util.Optional.of(token))
        ));
        return new WelinkChatShareDeliveryProvider(builder, properties, new ObjectMapper(), authHeaders);
    }

    private ChatShareProviderDeliveryRequest request() {
        return new ChatShareProviderDeliveryRequest(
                "tenant1",
                "user1",
                "分享标题",
                "https://finex.example.com/share/share1",
                "摘要",
                "a,b",
                "g1",
                "zh_CN"
        );
    }
}
