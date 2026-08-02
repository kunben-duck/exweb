package com.huawei.it.ex.one.infrastructure.sessiontitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.SessionTitleProperties;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleRequest;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;

import com.sun.net.httpserver.HttpServer;

import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class DefaultSessionTitleProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsExactRequestAndIntegrationAuthenticationHeaders() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/session_title", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"title\":\"经营情况分析\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        SessionTitleProperties properties = new SessionTitleProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTimeout("2s");
        AuthHeaderProviderRegistry authHeaders = mock(AuthHeaderProviderRegistry.class);
        when(authHeaders.headers(any())).thenReturn(Map.of("Authorization", "Bearer test-token"));
        DefaultSessionTitleProvider provider = new DefaultSessionTitleProvider(
                WebClient.builder(), properties, authHeaders, Schedulers.immediate());

        String title = provider.generate(new SessionTitleRequest(
                        "tenant-1", "user-1", "session-1", List.of("问题一", "问题二"), "zh-CN"))
                .block(Duration.ofSeconds(3));

        assertThat(title).isEqualTo("经营情况分析");
        assertThat(authHeader.get()).isEqualTo("Bearer test-token");
        assertThat(requestBody.get()).isEqualTo(
                "{\"session_id\":\"session-1\",\"queries\":[\"问题一\",\"问题二\"],\"language\":\"zh-CN\"}");
    }
}
