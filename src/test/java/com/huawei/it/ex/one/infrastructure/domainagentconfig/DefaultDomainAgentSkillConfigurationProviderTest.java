/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationException;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationQuery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

class DefaultDomainAgentSkillConfigurationProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsSingleSkillIdAndOnlyForwardsCookie() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        startServer(200, response(item("skill-1", "N")), Duration.ZERO, captured);
        RuntimeForwardHeaders forwardHeaders = RuntimeForwardHeaders.fromCookieHeader(
                "SESSION=test-cookie; tenant=tenant-1", 8192);

        DomainAgentSkillConfiguration configuration = resolve(provider("2s"), "skill-1", forwardHeaders);

        assertThat(configuration)
                .isEqualTo(new DomainAgentSkillConfiguration("skill-1", Boolean.FALSE));
        assertThat(captured.get().method()).isEqualTo("POST");
        assertThat(captured.get().path()).isEqualTo("/skill-config");
        assertThat(captured.get().body()).isEqualTo("[\"skill-1\"]");
        assertThat(captured.get().contentType()).startsWith("application/json");
        assertThat(captured.get().accept()).contains("application/json");
        assertThat(captured.get().cookie()).isEqualTo("SESSION=test-cookie; tenant=tenant-1");
        assertThat(captured.get().authorization()).isNull();
        assertThat(forwardHeaders.toString()).doesNotContain("test-cookie", "tenant=tenant-1");
    }

    @Test
    void callsEndpointWithoutCookieWhenRequestHasNoCookie() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        startServer(200, response(item("skill-1", "Y")), Duration.ZERO, captured);

        DomainAgentSkillConfiguration configuration = resolve(
                provider("2s"), "skill-1", RuntimeForwardHeaders.empty());

        assertThat(configuration.saveSession()).isTrue();
        assertThat(captured.get().cookie()).isNull();
    }

    @Test
    void mapsSkillNameAndAttachmentTypeIntoTheSharedConfiguration() throws Exception {
        startServer(200, response(
                        "{\"skillId\":\"skill-1\",\"skillName\":\"专项计税\","
                                + "\"isSaveSession\":\"Y\","
                                + "\"attachmentType\":\".xlsx.xls;.rar;.zip\"}"),
                Duration.ZERO, new AtomicReference<>());

        DomainAgentSkillConfiguration configuration = resolve(
                provider("2s"), "skill-1", RuntimeForwardHeaders.empty());

        assertThat(configuration).isEqualTo(new DomainAgentSkillConfiguration(
                "skill-1", "专项计税", Boolean.TRUE, ".xlsx.xls;.rar;.zip"));
    }

    @Test
    void mapsBlankAndMissingConfigurationWithoutInventingDefaults() throws Exception {
        startServer(200, response(item("skill-1", "  ")), Duration.ZERO, new AtomicReference<>());
        assertThat(resolve(provider("2s"), "skill-1", RuntimeForwardHeaders.empty()).saveSession())
                .isNull();

        stopServer();
        startServer(200, response(item("another-skill", "N")), Duration.ZERO, new AtomicReference<>());
        assertThat(resolve(provider("2s"), "skill-1", RuntimeForwardHeaders.empty()))
                .isEqualTo(DomainAgentSkillConfiguration.unconfigured("skill-1"));
    }

    @Test
    void rejectsUnknownOrConflictingSaveSessionValues() throws Exception {
        startServer(200, response(item("skill-1", "MAYBE")), Duration.ZERO, new AtomicReference<>());
        assertReason(provider("2s"), DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID);

        stopServer();
        startServer(200, response(item("skill-1", "Y"), item("skill-1", "N")),
                Duration.ZERO, new AtomicReference<>());
        assertReason(provider("2s"), DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID);

        stopServer();
        startServer(200, response(item("skill-1", " "), item("skill-1", "Y")),
                Duration.ZERO, new AtomicReference<>());
        assertReason(provider("2s"), DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID);
    }

    @Test
    void distinguishesProtocolHttpAndTimeoutFailures() throws Exception {
        startServer(200, "{invalid-json", Duration.ZERO, new AtomicReference<>());
        assertReason(provider("2s"), DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID);

        stopServer();
        startServer(503, "{}", Duration.ZERO, new AtomicReference<>());
        assertReason(provider("2s"), DomainAgentSkillConfigurationException.Reason.UNAVAILABLE);

        stopServer();
        startServer(200, response(item("skill-1", "Y")), Duration.ofMillis(200), new AtomicReference<>());
        assertReason(provider("10ms"), DomainAgentSkillConfigurationException.Reason.TIMEOUT);
    }

    @Test
    void rejectsInvalidQueriesAndUsesTwoSecondDefaultTimeout() {
        assertThat(new DomainAgentSkillConfigurationProperties().normalizedTimeout())
                .isEqualTo(Duration.ofSeconds(2));
        assertThatThrownBy(() -> provider("2s").findBySkillId(null).block())
                .isInstanceOfSatisfying(DomainAgentSkillConfigurationException.class,
                        error -> assertThat(error.reason())
                                .isEqualTo(DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID));
    }

    private DomainAgentSkillConfiguration resolve(
            DefaultDomainAgentSkillConfigurationProvider provider,
            String skillId,
            RuntimeForwardHeaders forwardHeaders) {
        return provider.findBySkillId(new DomainAgentSkillConfigurationQuery(
                        "tenant-1", "user-1", skillId, forwardHeaders))
                .block(Duration.ofSeconds(3));
    }

    private void assertReason(
            DefaultDomainAgentSkillConfigurationProvider provider,
            DomainAgentSkillConfigurationException.Reason expected) {
        assertThatThrownBy(() -> resolve(provider, "skill-1", RuntimeForwardHeaders.empty()))
                .isInstanceOfSatisfying(DomainAgentSkillConfigurationException.class,
                        error -> assertThat(error.reason()).isEqualTo(expected));
    }

    private DefaultDomainAgentSkillConfigurationProvider provider(String timeout) {
        DomainAgentSkillConfigurationProperties properties = new DomainAgentSkillConfigurationProperties();
        properties.setBaseUrl(server == null
                ? "http://127.0.0.1:1"
                : "http://127.0.0.1:" + server.getAddress().getPort());
        properties.setQueryPath("/skill-config");
        properties.setTimeout(timeout);
        return new DefaultDomainAgentSkillConfigurationProvider(WebClient.builder(), properties);
    }

    private void startServer(
            int status,
            String responseBody,
            Duration delay,
            AtomicReference<CapturedRequest> captured) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/skill-config", exchange -> respond(
                exchange, status, responseBody, delay, captured));
        server.start();
    }

    private void respond(
            HttpExchange exchange,
            int status,
            String responseBody,
            Duration delay,
            AtomicReference<CapturedRequest> captured) throws IOException {
        captured.set(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                exchange.getRequestHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                exchange.getRequestHeaders().getFirst(HttpHeaders.ACCEPT),
                exchange.getRequestHeaders().getFirst(HttpHeaders.COOKIE),
                exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION)));
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaTypeValues.APPLICATION_JSON);
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private String response(String... items) {
        return "{\"status\":\"success\",\"data\":[" + String.join(",", items) + "]}";
    }

    private String item(String skillId, String isSaveSession) {
        return "{\"skillId\":\"" + skillId + "\",\"isSaveSession\":\""
                + isSaveSession + "\"}";
    }

    private record CapturedRequest(
            String method,
            String path,
            String body,
            String contentType,
            String accept,
            String cookie,
            String authorization) {
    }

    private static final class MediaTypeValues {
        private static final String APPLICATION_JSON = "application/json";

        private MediaTypeValues() {
        }
    }
}
