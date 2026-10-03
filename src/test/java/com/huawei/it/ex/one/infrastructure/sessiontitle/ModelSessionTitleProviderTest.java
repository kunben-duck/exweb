/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.sessiontitle;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.SessionTitleProperties;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ModelSessionTitleProviderTest {
    private static final String VALID_RESPONSE = "{\"choices\":[{\"message\":{\"content\":\"经营分析\"}}]}";
    private final SessionTitleProperties properties = properties();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOnlyModelMessagesAndStreamWithStaticAuthentication() throws Exception {
        startServer(VALID_RESPONSE);
        ModelSessionTitleProvider provider = new ModelSessionTitleProvider(WebClient.builder(), properties);

        assertThat(provider.generate(request(List.of(" DTS123进展 ", "第二问", "第三问", "不发送的第四问"), " EN_us "))
                .block(Duration.ofSeconds(5))).isEqualTo("经营分析");

        JsonNode body = new ObjectMapper().readTree(requestBody.get());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.path("model").asText()).isEqualTo("GLM-V5");
        assertThat(body.path("stream").isBoolean()).isTrue();
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("messages").size()).isEqualTo(1);
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("user");
        assertThat(prompt(body)).contains("in English", "Maximum 50 characters", "preserve them exactly as-is",
                "user: DTS123进展\nuser: 第二问\nuser: 第三问").doesNotContain("第四问", "tenant-private", "session-private");
        assertThat(authorization).hasValue("Bearer test-secret");
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"zh_CN,Chinese", "ZH_cn,Chinese", "en_US,English", "EN_us,English", "fr_FR,Chinese"})
    void mapsModelLanguageWithoutChangingRequest(String language, String expected) throws Exception {
        startServer(VALID_RESPONSE);
        SessionTitleRequest request = request(List.of("问题"), language);
        new ModelSessionTitleProvider(WebClient.builder(), properties).generate(request).block(Duration.ofSeconds(5));

        assertThat(prompt(new ObjectMapper().readTree(requestBody.get()))).contains("in " + expected);
        assertThat(request.language()).isEqualTo(language);
    }

    @Test
    void truncatesEachQueryByCodePointAndUsesConfiguredTitleLength() throws Exception {
        startServer(VALID_RESPONSE);
        properties.setMaxTitleLength(42);
        properties.setDefaultLanguage("en_US");
        String exact = "\uD83D\uDE00".repeat(500);
        new ModelSessionTitleProvider(WebClient.builder(), properties)
                .generate(request(List.of(exact, exact + "尾部"), null)).block(Duration.ofSeconds(5));

        String prompt = prompt(new ObjectMapper().readTree(requestBody.get()));
        assertThat(prompt).contains("in English", "Maximum 42 characters",
                "user: " + exact + "\nuser: " + exact + "...").doesNotContain("尾部");
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503})
    void retriesTransientHttpFailureExactlyOnce(int status) {
        ModelSessionTitleProvider provider = mockProvider(() -> response(status, "private-error-body"));

        StepVerifier.withVirtualTime(() -> provider.generate(request(List.of("问题"), null)))
                .thenAwait(Duration.ofSeconds(2))
                .expectErrorSatisfies(error -> assertThat(error.getMessage())
                        .contains("HTTP " + status).doesNotContain("private-error-body", "test-secret"))
                .verify(Duration.ofSeconds(3));
        assertThat(calls).hasValue(2);
    }

    @Test
    void retriesNetworkFailureAndReturnsSecondResponse() {
        ModelSessionTitleProvider provider = mockProvider(() -> calls.get() == 1
                ? Mono.error(new WebClientRequestException(new IOException("connection reset"),
                        HttpMethod.POST, URI.create(properties.getModel().normalizedEndpoint()), new HttpHeaders()))
                : response(200, VALID_RESPONSE));

        StepVerifier.withVirtualTime(() -> provider.generate(request(List.of("问题"), null)))
                .thenAwait(Duration.ofSeconds(2)).expectNext("经营分析").verifyComplete();
        assertThat(calls).hasValue(2);
    }

    @Test
    void retriesConnectionLossAfterResponseHeaders() {
        ModelSessionTitleProvider provider = mockProvider(() -> calls.get() == 1
                ? Mono.just(ClientResponse.create(HttpStatusCode.valueOf(200))
                        .header("Content-Type", "application/json")
                        .body(Flux.error(new IOException("connection reset during body"))).build())
                : response(200, VALID_RESPONSE));

        StepVerifier.withVirtualTime(() -> provider.generate(request(List.of("问题"), null)))
                .thenAwait(Duration.ofSeconds(2)).expectNext("经营分析").verifyComplete();
        assertThat(calls).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422})
    void doesNotRetryPermanentHttpFailure(int status) {
        ModelSessionTitleProvider provider = mockProvider(() -> response(status, "private-error-body"));
        StepVerifier.create(provider.generate(request(List.of("问题"), null)))
                .expectErrorMatches(error -> error.getMessage().equals("Session title model returned HTTP " + status))
                .verify();
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{}", "{\"choices\":[]}",
            "{\"choices\":[{\"message\":{\"reasoning_content\":\"not a title\"}}]}",
            "{\"choices\":[{\"message\":{\"content\":null}}]}",
            "{\"choices\":[{\"message\":{\"content\":123}}]}",
            "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}"})
    void rejectsInvalidResponsesWithoutRetry(String body) {
        ModelSessionTitleProvider provider = mockProvider(() -> response(200, body));
        StepVerifier.create(provider.generate(request(List.of("问题"), null))).expectError().verify();
        assertThat(calls).hasValue(1);
    }

    @Test
    void capsRealResponseBodyWithoutChangingSharedBuilder() throws Exception {
        startServer("{\"choices\":[{\"message\":{\"content\":\"" + "x".repeat(70 * 1024) + "\"}}]}");
        WebClient.Builder builder = WebClient.builder();
        ModelSessionTitleProvider provider = new ModelSessionTitleProvider(builder, properties);
        StepVerifier.create(provider.generate(request(List.of("问题"), null)))
                .expectError().verify(Duration.ofSeconds(5));
        assertThat(calls).hasValue(1);
        assertThat(builder.build().get().uri(properties.getModel().normalizedEndpoint())
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5))).hasSizeGreaterThan(64 * 1024);
    }

    @Test
    void totalDeadlineCancelsRetryBackoff() {
        properties.setTimeout("100ms");
        ModelSessionTitleProvider provider = mockProvider(() -> response(503, ""));
        StepVerifier.withVirtualTime(() -> provider.generate(request(List.of("问题"), null)))
                .thenAwait(Duration.ofMillis(101)).expectError(TimeoutException.class).verify();
        assertThat(calls).hasValue(1);
    }

    @Test
    void sixtySecondBudgetAllowsResponseAfterFortyFiveSeconds() {
        properties.setTimeout("60s");
        ModelSessionTitleProvider provider = mockProvider(() -> Mono.delay(Duration.ofSeconds(45))
                .then(response(200, VALID_RESPONSE)));

        StepVerifier.withVirtualTime(() -> provider.generate(request(List.of("问题"), null)))
                .expectSubscription().expectNoEvent(Duration.ofSeconds(44))
                .thenAwait(Duration.ofSeconds(1)).expectNext("经营分析").verifyComplete();
        assertThat(calls).hasValue(1);
    }

    @Test
    void sixtySecondDeadlineCancelsSecondAttemptWithoutResettingBudget() {
        properties.setTimeout("60s");
        AtomicBoolean cancelled = new AtomicBoolean();
        ModelSessionTitleProvider provider = mockProvider(() -> calls.get() == 1
                ? Mono.delay(Duration.ofSeconds(40)).then(response(503, ""))
                : Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true)));

        StepVerifier.withVirtualTime(() -> provider.generate(request(List.of("问题"), null)))
                .expectSubscription().expectNoEvent(Duration.ofSeconds(59))
                .thenAwait(Duration.ofSeconds(1)).expectError(TimeoutException.class).verify();
        assertThat(cancelled).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    void totalDeadlineIncludesTimeSpentInBothAttempts() {
        properties.setTimeout("1s");
        ModelSessionTitleProvider provider = mockProvider(() -> calls.get() == 1
                ? Mono.delay(Duration.ofMillis(200)).then(response(503, "")) : Mono.never());
        StepVerifier.withVirtualTime(() -> provider.generate(request(List.of("问题"), null)))
                .thenAwait(Duration.ofSeconds(1)).expectError(TimeoutException.class).verify();
        assertThat(calls).hasValue(2);
    }

    @Test
    void cancellationDisposesNetworkWaitWithoutRetry() {
        AtomicBoolean cancelled = new AtomicBoolean();
        ModelSessionTitleProvider provider = mockProvider(() -> Mono.<ClientResponse>never()
                .doOnCancel(() -> cancelled.set(true)));
        var subscription = provider.generate(request(List.of("问题"), null)).subscribe();
        assertThat(calls).hasValue(1);
        subscription.dispose();
        assertThat(cancelled).isTrue();
        assertThat(calls).hasValue(1);
    }

    @Test
    void emptyQueriesFailBeforeHttp() {
        ModelSessionTitleProvider provider = mockProvider(() -> response(200, VALID_RESPONSE));
        StepVerifier.create(provider.generate(request(List.of(" "), null)))
                .expectError(IllegalArgumentException.class).verify();
        assertThat(calls).hasValue(0);
    }

    private ModelSessionTitleProvider mockProvider(java.util.function.Supplier<Mono<ClientResponse>> exchange) {
        return new ModelSessionTitleProvider(WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return exchange.get();
        }), properties);
    }

    private Mono<ClientResponse> response(int status, String body) {
        return Mono.just(ClientResponse.create(HttpStatusCode.valueOf(status))
                .header("Content-Type", "application/json").body(body).build());
    }

    private void startServer(String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        properties.getModel().setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
    }

    private String prompt(JsonNode body) {
        return body.path("messages").path(0).path("content").asText();
    }

    private SessionTitleRequest request(List<String> queries, String language) {
        return new SessionTitleRequest("tenant-private", "user-private", "session-private", queries, language);
    }

    private static SessionTitleProperties properties() {
        SessionTitleProperties properties = new SessionTitleProperties();
        properties.setTimeout("3s");
        properties.getModel().setEndpoint("https://model.example.test/v1/chat/completions");
        properties.getModel().setName(" GLM-V5 ");
        properties.getModel().setApiKey(" test-secret ");
        return properties;
    }
}
