/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.IntegrationAuthProperties;
import com.huawei.it.ex.one.application.config.IntentCandidateProperties;
import com.huawei.it.ex.one.application.integration.auth.AuthHeaderProvider;
import com.huawei.it.ex.one.application.integration.auth.AuthHeaderRequest;
import com.huawei.it.ex.one.application.integration.intent.IntentCandidate;
import com.huawei.it.ex.one.application.integration.intent.IntentCandidateQueryException;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;
import com.huawei.it.ex.one.domain.auth.UserContext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

class FinEurekaIntentCandidateProviderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UserContext user = new UserContext("tenant1", "user1", "User One");
    private final List<Scheduler> schedulers = new ArrayList<>();

    @AfterEach
    void disposeSchedulers() {
        schedulers.forEach(Scheduler::dispose);
    }

    @Test
    void sendsOnlyMessageIdAndMapsCandidatesInOriginalOrder() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<AuthHeaderRequest> authRequest = new AtomicReference<>();
        String response = """
                {
                  "status":"success",
                  "code":200,
                  "data":{"confidence":{"topCandidates":[
                    {"intentId":"intent-1","accessName":" EX_EX_skill-1 ","intentName":"技能一","confidence":0.92},
                    {"intentId":"intent-2","accessName":"other-skill","intentName":"技能二","confidence":0.75},
                    {"intentId":"intent-3","accessName":"","intentName":"空入口","confidence":0.1}
                  ]}}
                }
                """;
        try (ServerFixture fixture = server((exchange, attempt) -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            send(exchange, 200, response);
        })) {
            FinEurekaIntentCandidateProvider provider = provider(
                    fixture.baseUrl(), 0, authHeaders(authRequest));

            List<IntentCandidate> candidates = provider.findCandidates(user, "msg-user").block();

            assertThat(objectMapper.readValue(requestBody.get(), new TypeReference<Map<String, Object>>() {}))
                    .containsExactly(Map.entry("messageId", "msg-user"));
            assertThat(authorization.get()).isEqualTo("Bearer intent-token");
            assertThat(authRequest.get().operationCode()).isEqualTo("confidence");
            assertThat(authRequest.get().path()).isEqualTo("/confidence");
            assertThat(candidates).containsExactly(
                    new IntentCandidate("intent-1", " EX_EX_skill-1 ", "EX_skill-1", "技能一", 0.92),
                    new IntentCandidate("intent-2", "other-skill", "other-skill", "技能二", 0.75),
                    new IntentCandidate("intent-3", "", null, "空入口", 0.1));
        }
    }

    @Test
    void retriesFailuresUsingConfiguredRetryCount() throws Exception {
        AtomicInteger authCalls = new AtomicInteger();
        try (ServerFixture fixture = server((exchange, attempt) -> {
            if (attempt < 3) {
                send(exchange, 503, "{\"message\":\"down\"}");
                return;
            }
            send(exchange, 200,
                    "{\"code\":200,\"data\":{\"confidence\":{\"topCandidates\":[]}}}");
        })) {
            FinEurekaIntentCandidateProvider provider = provider(
                    fixture.baseUrl(), 3, countingAuthHeaders(authCalls));

            assertThat(provider.findCandidates(user, "msg-user").block()).isEmpty();
            assertThat(fixture.attempts()).hasValue(3);
            assertThat(authCalls).hasValue(1);
        }
    }

    @Test
    void retriesHttp408AndThenSucceeds() throws Exception {
        try (ServerFixture fixture = server((exchange, attempt) -> {
            if (attempt == 1) {
                send(exchange, 408, "{\"message\":\"timeout\"}");
                return;
            }
            send(exchange, 200,
                    "{\"code\":200,\"data\":{\"confidence\":{\"topCandidates\":[]}}}");
        })) {
            FinEurekaIntentCandidateProvider provider = provider(
                    fixture.baseUrl(), 1, noAuthHeaders());

            assertThat(provider.findCandidates(user, "msg-user").block()).isEmpty();
            assertThat(fixture.attempts()).hasValue(2);
        }
    }

    @Test
    void mapsExhaustedAttemptTimeoutToDedicatedFailure() {
        IntentServiceHttpProperties properties = properties("http://intent.test", 0);
        properties.setTimeout(Duration.ofMillis(30));
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.never());
        FinEurekaIntentCandidateProvider provider = new FinEurekaIntentCandidateProvider(
                builder, properties, candidateProperties(), noAuthHeaders(), scheduler());

        StepVerifier.create(provider.findCandidates(user, "msg-user"))
                .expectErrorSatisfies(failure -> {
                    assertThat(failure).isInstanceOf(IntentCandidateQueryException.class);
                    assertThat(((IntentCandidateQueryException) failure).timeout()).isTrue();
                })
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void doesNotRetryProtocolFailure() throws Exception {
        try (ServerFixture fixture = server((exchange, attempt) ->
                send(exchange, 200, "{\"code\":200,\"data\":{\"confidence\":{}}}"))) {
            FinEurekaIntentCandidateProvider provider = provider(
                    fixture.baseUrl(), 2, noAuthHeaders());

            StepVerifier.create(provider.findCandidates(user, "msg-user"))
                    .expectErrorSatisfies(failure -> {
                        assertThat(failure).isInstanceOf(IntentCandidateQueryException.class);
                        assertThat(((IntentCandidateQueryException) failure).timeout()).isFalse();
                    })
                    .verify(Duration.ofSeconds(2));
            assertThat(fixture.attempts()).hasValue(1);
        }
    }

    @Test
    void doesNotRetryClientErrorsIncludingRateLimit() throws Exception {
        for (int status : List.of(400, 401, 403, 404, 429)) {
            try (ServerFixture fixture = server((exchange, attempt) ->
                    send(exchange, status, "{\"message\":\"rejected\"}"))) {
                FinEurekaIntentCandidateProvider provider = provider(
                        fixture.baseUrl(), 3, noAuthHeaders());

                StepVerifier.create(provider.findCandidates(user, "msg-user"))
                        .expectError(IntentCandidateQueryException.class)
                        .verify(Duration.ofSeconds(2));
                assertThat(fixture.attempts()).as("HTTP %s", status).hasValue(1);
            }
        }
    }

    @Test
    void doesNotRetryAuthenticationFailure() {
        AtomicInteger authCalls = new AtomicInteger();
        AuthHeaderProviderRegistry failingAuth = authHeaders(request -> {
            authCalls.incrementAndGet();
            throw new IllegalStateException("token unavailable");
        });
        FinEurekaIntentCandidateProvider provider = provider(
                "http://intent.test", 3, failingAuth);

        StepVerifier.create(provider.findCandidates(user, "msg-user"))
                .expectErrorSatisfies(failure -> {
                    assertThat(failure).isInstanceOf(IntentCandidateQueryException.class);
                    assertThat(((IntentCandidateQueryException) failure).retryable()).isFalse();
                })
                .verify(Duration.ofSeconds(2));
        assertThat(authCalls).hasValue(1);
    }

    @Test
    void mapsAuthenticationSchedulerRejectionToBusy() {
        Scheduler rejectedScheduler = Schedulers.newBoundedElastic(1, 1, "rejected-candidate-auth");
        rejectedScheduler.dispose();
        FinEurekaIntentCandidateProvider provider = new FinEurekaIntentCandidateProvider(
                WebClient.builder(), properties("http://intent.test", 3), candidateProperties(),
                noAuthHeaders(), rejectedScheduler);

        StepVerifier.create(provider.findCandidates(user, "msg-user"))
                .expectErrorSatisfies(failure -> {
                    assertThat(failure).isInstanceOf(IntentCandidateQueryException.class);
                    assertThat(((IntentCandidateQueryException) failure).isBusy()).isTrue();
                })
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void authenticationTimeoutIsNotRetriedOrMappedAsHttpTimeout() {
        AtomicInteger authCalls = new AtomicInteger();
        AtomicInteger httpCalls = new AtomicInteger();
        AtomicBoolean release = new AtomicBoolean(false);
        IntentServiceHttpProperties properties = properties("http://intent.test", 3);
        properties.setTimeout(Duration.ofMillis(30));
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            httpCalls.incrementAndGet();
            return Mono.never();
        });
        AuthHeaderProviderRegistry blockingAuth = authHeaders(request -> {
            authCalls.incrementAndGet();
            while (!release.get()) {
                LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
            }
            return Map.of();
        });
        FinEurekaIntentCandidateProvider provider = new FinEurekaIntentCandidateProvider(
                builder, properties, candidateProperties(), blockingAuth, scheduler());

        try {
            StepVerifier.create(provider.findCandidates(user, "msg-user"))
                    .expectErrorSatisfies(failure -> {
                        assertThat(failure).isInstanceOf(IntentCandidateQueryException.class);
                        IntentCandidateQueryException candidateFailure =
                                (IntentCandidateQueryException) failure;
                        assertThat(candidateFailure.timeout()).isFalse();
                        assertThat(candidateFailure.retryable()).isFalse();
                    })
                    .verify(Duration.ofSeconds(2));
            assertThat(authCalls).hasValue(1);
            assertThat(httpCalls).hasValue(0);
        } finally {
            release.set(true);
        }
    }

    private FinEurekaIntentCandidateProvider provider(String baseUrl,
                                                      int maxRetries,
                                                      AuthHeaderProviderRegistry authHeaders) {
        return new FinEurekaIntentCandidateProvider(
                WebClient.builder(), properties(baseUrl, maxRetries), candidateProperties(),
                authHeaders, scheduler());
    }

    private IntentServiceHttpProperties properties(String baseUrl, int maxRetries) {
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setBaseUrl(baseUrl);
        properties.setConfidencePath("/confidence");
        properties.setResponseAccessNamePrefix("EX_");
        properties.setMaxRetries(maxRetries);
        return properties;
    }

    private AuthHeaderProviderRegistry noAuthHeaders() {
        return new AuthHeaderProviderRegistry(new IntegrationAuthProperties(), List.of());
    }

    private AuthHeaderProviderRegistry authHeaders(AtomicReference<AuthHeaderRequest> captured) {
        return authHeaders(request -> {
            captured.set(request);
            return Map.of(HttpHeaders.AUTHORIZATION, "Bearer intent-token");
        });
    }

    private AuthHeaderProviderRegistry countingAuthHeaders(AtomicInteger calls) {
        return authHeaders(request -> {
            calls.incrementAndGet();
            return Map.of(HttpHeaders.AUTHORIZATION, "Bearer intent-token");
        });
    }

    private AuthHeaderProviderRegistry authHeaders(AuthCall authCall) {
        IntegrationAuthProperties properties = new IntegrationAuthProperties();
        properties.setEnabled(true);
        IntegrationAuthProperties.Service service = new IntegrationAuthProperties.Service();
        service.setProvider("test");
        properties.setServices(Map.of("intent-service", service));
        AuthHeaderProvider provider = new AuthHeaderProvider() {
            @Override
            public String providerCode() {
                return "test";
            }

            @Override
            public Map<String, String> headers(AuthHeaderRequest request) {
                return authCall.headers(request);
            }
        };
        return new AuthHeaderProviderRegistry(properties, List.of(provider));
    }

    private IntentCandidateProperties candidateProperties() {
        IntentCandidateProperties properties = new IntentCandidateProperties();
        properties.setRetryMinBackoff(Duration.ofMillis(1));
        properties.setRetryMaxBackoff(Duration.ofMillis(2));
        return properties;
    }

    private Scheduler scheduler() {
        Scheduler scheduler = Schedulers.newBoundedElastic(2, 16, "test-intent-candidate-auth");
        schedulers.add(scheduler);
        return scheduler;
    }

    private ServerFixture server(ResponseHandler handler) throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/confidence", exchange -> handler.handle(exchange, attempts.incrementAndGet()));
        server.start();
        return new ServerFixture(server, attempts);
    }

    private void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @FunctionalInterface
    private interface ResponseHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange, int attempt) throws IOException;
    }

    @FunctionalInterface
    private interface AuthCall {
        Map<String, String> headers(AuthHeaderRequest request);
    }

    private record ServerFixture(HttpServer server, AtomicInteger attempts) implements AutoCloseable {
        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
