package com.huawei.it.ex.one.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.IntegrationAuthProperties;
import com.huawei.it.ex.one.application.config.RegionalAccessProperties;
import com.huawei.it.ex.one.application.integration.auth.AuthHeaderProvider;
import com.huawei.it.ex.one.application.integration.auth.AuthHeaderRequest;
import com.huawei.it.ex.one.application.integration.security.RegionalLocationResult;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class SgovLocationProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsHrAndIpResponsesAndUsesDedicatedAuthServiceCodes() throws IOException {
        AtomicReference<String> hrQuery = new AtomicReference<>();
        AtomicReference<String> ipQuery = new AtomicReference<>();
        AtomicReference<String> hrAuth = new AtomicReference<>();
        AtomicReference<String> ipAuth = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hr-app/getBatchPersonInfo", exchange -> {
            hrQuery.set(exchange.getRequestURI().getRawQuery());
            hrAuth.set(exchange.getRequestHeaders().getFirst("X-Service-Auth"));
            respond(exchange, "{\"code\":200,\"data\":{\"result\":[{\"location_country\":\"France\"}]}}");
        });
        server.createContext("/f/idata/common/getAddressByIP", exchange -> {
            ipQuery.set(exchange.getRequestURI().getRawQuery());
            ipAuth.set(exchange.getRequestHeaders().getFirst("X-Service-Auth"));
            respond(exchange, "{\"code\":200,\"data\":[{\"country\":\"Germany\"}]}");
        });
        server.start();

        RegionalAccessProperties properties = new RegionalAccessProperties();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.setHrBaseUrl(baseUrl);
        properties.setHrAppId("hr-app");
        properties.setIpBaseUrl(baseUrl);
        AuthHeaderProviderRegistry authHeaders = authHeaders();

        RegionalLocationResult hrResult = new SgovEmployeeLocationProvider(
                WebClient.builder(), properties, authHeaders).findCountry(user(), "EMP-001").block();
        RegionalLocationResult ipResult = new SgovIpLocationProvider(
                WebClient.builder(), properties, authHeaders).findCountry(user(), "203.0.113.40").block();

        assertThat(hrResult).isEqualTo(RegionalLocationResult.found("France"));
        assertThat(ipResult).isEqualTo(RegionalLocationResult.found("Germany"));
        assertThat(hrQuery.get()).contains("employee_number=EMP-001").contains("lang=zh_CN");
        assertThat(ipQuery.get()).contains("ip=203.0.113.40").contains("full=1");
        assertThat(hrAuth.get()).isEqualTo("regional-hr-location");
        assertThat(ipAuth.get()).isEqualTo("regional-ip-location");
    }

    @Test
    void missingEndpointConfigurationIsUnavailableWithoutNetworkCall() {
        RegionalAccessProperties properties = new RegionalAccessProperties();
        AuthHeaderProviderRegistry authHeaders = authHeaders();

        assertThat(new SgovEmployeeLocationProvider(WebClient.builder(), properties, authHeaders)
                .findCountry(user(), "EMP-001").block()).isEqualTo(RegionalLocationResult.unavailable());
        assertThat(new SgovIpLocationProvider(WebClient.builder(), properties, authHeaders)
                .findCountry(user(), "203.0.113.40").block()).isEqualTo(RegionalLocationResult.unavailable());
    }

    private AuthHeaderProviderRegistry authHeaders() {
        IntegrationAuthProperties properties = new IntegrationAuthProperties();
        properties.setEnabled(true);
        AuthHeaderProvider provider = new AuthHeaderProvider() {
            @Override
            public String providerCode() {
                return "sgov";
            }

            @Override
            public Map<String, String> headers(AuthHeaderRequest request) {
                return Map.of("X-Service-Auth", request.serviceCode());
            }
        };
        return new AuthHeaderProviderRegistry(properties, List.of(provider));
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
