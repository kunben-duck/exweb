package com.huawei.it.ex.one.infrastructure.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.IntegrationAuthProperties;
import com.huawei.it.ex.one.application.integration.intent.IntentDecisionStreamFrame;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.infrastructure.auth.NoopAuthHeaderProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class FinEurekaIntentStreamClientTest {
    private static final String ROUTE_SINGLE_RESULT = """
            event: result
            data: {"status":"success","code":200,"message":"success","data":{"result":{"routeAction":"ROUTE_SINGLE","items":[{"intentId":"knowledge","intentName":"知识问答","accessName":"knowledge-agent","confidence":0.95}],"clarification":null}}}

            """;
    private static final String NO_MATCH_RESULT = """
            event: result
            data: {"status":"success","code":200,"message":"success","data":{"result":{"routeAction":"NO_MATCH","items":[],"clarification":null}}}

            """;
    private static final String ROUTE_MULTI_RESULT = """
            event: result
            data: {"status":"success","code":200,"message":"success","data":{"result":{"routeAction":"ROUTE_MULTI","items":[{"intentName":"知识问答"},{"intentName":"财经问数"}],"clarification":null}}}

            """;
    private static final String CLARIFY_RESULT = """
            event: result
            data: {"status":"success","code":200,"message":"success","data":{"result":{"routeAction":"CLARIFY","items":[],"clarification":{"type":"AMBIGUOUS_ROUTE","clarifyQuestion":"请补充问题范围"}}}}

            """;

    @Test
    void emitsProgressDeltaAndFinalResultInOrder() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        String response = """
                event: progress
                data: {"stage":"ES_SEARCHING","stageMessage":"ES检索中"}

                event: delta
                data: {"index":1,"content":"partial"}

                """ + ROUTE_SINGLE_RESULT;
        try (StreamServerFixture fixture = server(requestBody, accept, exchange -> write(exchange, response))) {
            FinEurekaIntentStreamClient client = client(fixture, properties(0));

            List<IntentDecisionStreamFrame> frames = client
                    .recognize(command(), MemoryContext.empty(), user())
                    .collectList()
                    .block();

            assertThat(frames).hasSize(3);
            assertThat(frames.get(0).type()).isEqualTo(IntentDecisionStreamFrame.Type.PROGRESS);
            assertThat(frames.get(0).payload())
                    .containsEntry("stage", "ES_SEARCHING")
                    .containsEntry("stageMessage", "ES检索中");
            assertThat(frames.get(1).type()).isEqualTo(IntentDecisionStreamFrame.Type.DELTA);
            assertThat(frames.get(1).payload())
                    .containsEntry("index", 1L)
                    .containsEntry("content", "partial");
            assertThat(frames.get(2).type()).isEqualTo(IntentDecisionStreamFrame.Type.RESULT);
            assertThat(frames.get(2).recognitionResult().decision().intentCode()).isEqualTo("knowledge");
            assertThat(frames.get(2).recognitionResult().decision().candidateDomainAgentId())
                    .isEqualTo("knowledge-agent");
            assertThat(requestBody.get()).contains("\"query\":\"用户问题\"");
            assertThat(accept.get()).contains("text/event-stream");
        }
    }

    @Test
    void retriesTerminalErrorAndPreservesAttemptNumbers() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (StreamServerFixture fixture = server(null, null, exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                write(exchange, """
                        event: progress
                        data: {"stage":"ES_SEARCHING","stageMessage":"ES检索中"}

                        event: error
                        data: {"status":"fail","code":504,"message":"timeout","data":null}

                        """);
                return;
            }
            write(exchange, """
                    event: progress
                    data: {"stage":"ES_HIT","stageMessage":"ES已命中"}

                    """ + ROUTE_SINGLE_RESULT);
        })) {
            FinEurekaIntentStreamClient client = client(fixture, properties(1));

            List<IntentDecisionStreamFrame> frames = client
                    .recognize(command(), MemoryContext.empty(), user())
                    .collectList()
                    .block();

            assertThat(attempts.get()).isEqualTo(2);
            assertThat(frames).hasSize(3);
            assertThat(frames.get(0).attempt()).isEqualTo(1);
            assertThat(frames.get(1).attempt()).isEqualTo(2);
            assertThat(frames.get(2).attempt()).isEqualTo(2);
            assertThat(frames.get(2).recognitionResult().decision().intentCode()).isEqualTo("knowledge");
        }
    }

    @Test
    void retriesHttpFailureWithoutCallingAnotherEndpoint() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (StreamServerFixture fixture = server(null, null, exchange -> {
            if (attempts.incrementAndGet() == 1) {
                writeStatus(exchange, 503, "application/json", "{\"message\":\"unavailable\"}");
                return;
            }
            write(exchange, ROUTE_SINGLE_RESULT);
        })) {
            FinEurekaIntentStreamClient client = client(fixture, properties(1));

            IntentDecisionStreamFrame result = client
                    .recognize(command(), MemoryContext.empty(), user())
                    .blockLast();

            assertThat(attempts.get()).isEqualTo(2);
            assertThat(result.recognitionResult().decision().intentCode()).isEqualTo("knowledge");
        }
    }

    @Test
    void retriesMalformedResultEvent() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (StreamServerFixture fixture = server(null, null, exchange -> {
            if (attempts.incrementAndGet() == 1) {
                write(exchange, "event: result\ndata: not-json\n\n");
                return;
            }
            write(exchange, ROUTE_SINGLE_RESULT);
        })) {
            FinEurekaIntentStreamClient client = client(fixture, properties(1));

            IntentDecisionStreamFrame result = client
                    .recognize(command(), MemoryContext.empty(), user())
                    .blockLast();

            assertThat(attempts.get()).isEqualTo(2);
            assertThat(result.recognitionResult().decision().intentCode()).isEqualTo("knowledge");
        }
    }

    @Test
    void doesNotRetryValidNoMatchResult() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (StreamServerFixture fixture = server(null, null, exchange -> {
            attempts.incrementAndGet();
            write(exchange, NO_MATCH_RESULT);
        })) {
            FinEurekaIntentStreamClient client = client(fixture, properties(3));

            IntentDecisionStreamFrame result = client
                    .recognize(command(), MemoryContext.empty(), user())
                    .blockLast();

            assertThat(attempts.get()).isEqualTo(1);
            assertThat(result.recognitionResult().decision().complexity()).isEqualTo(TaskComplexity.COMPLEX);
            assertThat(result.recognitionResult().decision().intentCode())
                    .isEqualTo("finance.runtime.no_intent");
        }
    }

    @Test
    void delegatesRouteMultiAndClarificationToTheExistingResultMapper() throws Exception {
        try (StreamServerFixture routeMulti = server(
                null, null, exchange -> write(exchange, ROUTE_MULTI_RESULT));
             StreamServerFixture clarify = server(
                     null, null, exchange -> write(exchange, CLARIFY_RESULT))) {
            IntentDecisionStreamFrame routeMultiResult = client(routeMulti, properties(0))
                    .recognize(command(), MemoryContext.empty(), user())
                    .blockLast();
            IntentDecisionStreamFrame clarificationResult = client(clarify, properties(0))
                    .recognize(command(), MemoryContext.empty(), user())
                    .blockLast();

            assertThat(routeMultiResult.recognitionResult().decision().slots())
                    .containsEntry("routeAction", "ROUTE_MULTI")
                    .containsEntry("candidateIntentNames", List.of("知识问答", "财经问数"));
            assertThat(clarificationResult.recognitionResult().waitingClarification()).isTrue();
            assertThat(clarificationResult.recognitionResult().clarificationPayload())
                    .containsEntry("clarifyQuestion", "请补充问题范围");
        }
    }

    @Test
    void degradesWhenSuccessfulResponseIsNotSse() throws Exception {
        try (StreamServerFixture fixture = server(null, null, exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        })) {
            FinEurekaIntentStreamClient client = client(fixture, properties(0));

            IntentDecisionStreamFrame result = client
                    .recognize(command(), MemoryContext.empty(), user())
                    .blockLast();

            assertThat(result.type()).isEqualTo(IntentDecisionStreamFrame.Type.RESULT);
            assertThat(result.recognitionResult().decision().intentCode())
                    .isEqualTo("finance.runtime.degraded");
        }
    }

    @Test
    void ignoresMalformedProcessEventButKeepsFinalResult() throws Exception {
        try (StreamServerFixture fixture = server(null, null, exchange -> write(exchange, """
                event: extension
                data: {"raw":"ignored"}

                event: progress
                data: not-json

                event: delta
                data: {"index":1}

                """ + ROUTE_SINGLE_RESULT))) {
            FinEurekaIntentStreamClient client = client(fixture, properties(0));

            List<IntentDecisionStreamFrame> frames = client
                    .recognize(command(), MemoryContext.empty(), user())
                    .collectList()
                    .block();

            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).recognitionResult().decision().intentCode()).isEqualTo("knowledge");
        }
    }

    @Test
    void treatsStreamCloseWithoutTerminalEventAsFailure() throws Exception {
        try (StreamServerFixture fixture = server(
                null, null, exchange -> write(exchange, "event: progress\ndata: {\"stage\":\"ES_SEARCHING\"}\n\n"))) {
            FinEurekaIntentStreamClient client = client(fixture, properties(0));

            List<IntentDecisionStreamFrame> frames = client
                    .recognize(command(), MemoryContext.empty(), user())
                    .collectList()
                    .block();

            assertThat(frames).hasSize(2);
            assertThat(frames.get(0).type()).isEqualTo(IntentDecisionStreamFrame.Type.PROGRESS);
            assertThat(frames.get(1).recognitionResult().decision().intentCode())
                    .isEqualTo("finance.runtime.degraded");
        }
    }

    @Test
    void pingResetsIdleTimeoutWithoutProducingFrames() throws Exception {
        IntentServiceHttpProperties properties = properties(0);
        properties.setStreamFirstEventTimeout(Duration.ofMillis(500));
        properties.setStreamIdleTimeout(Duration.ofMillis(80));
        properties.setStreamTotalTimeout(Duration.ofSeconds(2));
        try (StreamServerFixture fixture = server(null, null, exchange -> writeChunks(
                exchange,
                List.of(": ping\n\n", ": ping\n\n", ": ping\n\n", ROUTE_SINGLE_RESULT),
                Duration.ofMillis(40)))) {
            FinEurekaIntentStreamClient client = client(fixture, properties);

            List<IntentDecisionStreamFrame> frames = client
                    .recognize(command(), MemoryContext.empty(), user())
                    .collectList()
                    .block();

            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).recognitionResult().decision().intentCode()).isEqualTo("knowledge");
        }
    }

    @Test
    void idleTimeoutEndsAttemptWhenNoNetworkFrameArrives() throws Exception {
        IntentServiceHttpProperties properties = properties(0);
        properties.setStreamFirstEventTimeout(Duration.ofSeconds(1));
        properties.setStreamIdleTimeout(Duration.ofMillis(100));
        properties.setStreamTotalTimeout(Duration.ofSeconds(2));
        try (StreamServerFixture fixture = server(null, null, exchange -> writeChunks(
                exchange,
                List.of("event: progress\ndata: {\"stage\":\"ES_SEARCHING\"}\n\n", ROUTE_SINGLE_RESULT),
                Duration.ofMillis(250)))) {
            FinEurekaIntentStreamClient client = client(fixture, properties);

            List<IntentDecisionStreamFrame> frames = client
                    .recognize(command(), MemoryContext.empty(), user())
                    .collectList()
                    .block();

            assertThat(frames).hasSize(2);
            assertThat(frames.get(0).type()).isEqualTo(IntentDecisionStreamFrame.Type.PROGRESS);
            assertThat(frames.get(1).recognitionResult().decision().intentCode())
                    .isEqualTo("finance.runtime.degraded");
        }
    }

    @Test
    void pingDoesNotExtendFirstBusinessEventTimeout() throws Exception {
        IntentServiceHttpProperties properties = properties(0);
        properties.setStreamFirstEventTimeout(Duration.ofMillis(100));
        properties.setStreamIdleTimeout(Duration.ofMillis(200));
        properties.setStreamTotalTimeout(Duration.ofSeconds(2));
        try (StreamServerFixture fixture = server(null, null, exchange -> writeChunks(
                exchange,
                List.of(": ping\n\n", ": ping\n\n", ": ping\n\n", ROUTE_SINGLE_RESULT),
                Duration.ofMillis(60)))) {
            FinEurekaIntentStreamClient client = client(fixture, properties);

            IntentDecisionStreamFrame result = client
                    .recognize(command(), MemoryContext.empty(), user())
                    .blockLast();

            assertThat(result.recognitionResult().decision().intentCode())
                    .isEqualTo("finance.runtime.degraded");
        }
    }

    @Test
    void firstEventTimeoutAlsoCoversDelayedResponseHeaders() throws Exception {
        IntentServiceHttpProperties properties = properties(0);
        properties.setStreamFirstEventTimeout(Duration.ofMillis(100));
        properties.setStreamIdleTimeout(Duration.ofSeconds(1));
        properties.setStreamTotalTimeout(Duration.ofSeconds(2));
        try (StreamServerFixture fixture = server(null, null, exchange -> {
            sleep(Duration.ofMillis(250));
            write(exchange, ROUTE_SINGLE_RESULT);
        })) {
            FinEurekaIntentStreamClient client = client(fixture, properties);

            IntentDecisionStreamFrame result = client
                    .recognize(command(), MemoryContext.empty(), user())
                    .blockLast();

            assertThat(result.recognitionResult().decision().intentCode())
                    .isEqualTo("finance.runtime.degraded");
        }
    }

    @Test
    void totalTimeoutEndsStreamEvenWhenPingContinues() throws Exception {
        IntentServiceHttpProperties properties = properties(0);
        properties.setStreamFirstEventTimeout(Duration.ofSeconds(2));
        properties.setStreamIdleTimeout(Duration.ofMillis(200));
        properties.setStreamTotalTimeout(Duration.ofMillis(150));
        try (StreamServerFixture fixture = server(null, null, exchange -> writeChunks(
                exchange,
                List.of(": ping\n\n", ": ping\n\n", ": ping\n\n", ": ping\n\n", ROUTE_SINGLE_RESULT),
                Duration.ofMillis(60)))) {
            FinEurekaIntentStreamClient client = client(fixture, properties);

            IntentDecisionStreamFrame result = client
                    .recognize(command(), MemoryContext.empty(), user())
                    .blockLast();

            assertThat(result.recognitionResult().decision().intentCode())
                    .isEqualTo("finance.runtime.degraded");
        }
    }

    private FinEurekaIntentStreamClient client(StreamServerFixture fixture,
                                                IntentServiceHttpProperties properties) {
        properties.setBaseUrl(fixture.baseUrl());
        properties.setRecognizeStreamPath("/stream");
        ObjectMapper objectMapper = new ObjectMapper();
        IntentServiceWireMapper wireMapper = new IntentServiceWireMapper(
                new IntentServiceRequestMapper(properties),
                new IntentServiceResponseMapper(objectMapper, properties));
        AuthHeaderProviderRegistry authHeaders = new AuthHeaderProviderRegistry(
                new IntegrationAuthProperties(), List.of(new NoopAuthHeaderProvider()));
        return new FinEurekaIntentStreamClient(
                WebClient.builder(), objectMapper, properties, wireMapper, authHeaders,
                new DefaultIntentRetryPolicy());
    }

    private IntentServiceHttpProperties properties(int maxRetries) {
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setAccessName("intent-entry");
        properties.setMaxRetries(maxRetries);
        return properties;
    }

    private StreamServerFixture server(AtomicReference<String> requestBody,
                                       AtomicReference<String> accept,
                                       ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/stream", exchange -> {
            if (requestBody != null) {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            if (accept != null) {
                accept.set(exchange.getRequestHeaders().getFirst(HttpHeaders.ACCEPT));
            }
            handler.handle(exchange);
        });
        server.start();
        return new StreamServerFixture(server);
    }

    private void write(HttpExchange exchange, String response) throws IOException {
        exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private void writeStatus(HttpExchange exchange, int status, String contentType, String response)
            throws IOException {
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private void writeChunks(HttpExchange exchange, List<String> chunks, Duration interval) throws IOException {
        exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            for (String chunk : chunks) {
                output.write(chunk.getBytes(StandardCharsets.UTF_8));
                output.flush();
                sleep(interval);
            }
        } catch (IOException ignored) {
            // Expected when the client timeout cancels the streaming response.
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private ChatCommand command() {
        return new ChatCommand(
                "command1", "tenant1", "user1", "session1", null, "web", "用户问题", List.of(), Map.of());
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "Alice");
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record StreamServerFixture(HttpServer server) implements AutoCloseable {
        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
