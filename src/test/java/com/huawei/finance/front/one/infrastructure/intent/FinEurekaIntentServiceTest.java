package com.huawei.finance.front.one.infrastructure.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.IntegrationAuthProperties;
import com.huawei.finance.front.one.application.integration.intent.IntentRetryContext;
import com.huawei.finance.front.one.application.integration.intent.IntentRetryPolicy;
import com.huawei.finance.front.one.application.service.auth.AuthHeaderProviderRegistry;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.intent.TaskComplexity;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.infrastructure.auth.NoopAuthHeaderProvider;
import com.huawei.finance.front.one.infrastructure.auth.SgovAuthHeaderProvider;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

class FinEurekaIntentServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsInternalCommandToIntentWireRequest() {
        IntentRecognizeRequest request = new IntentServiceRequestMapper()
                .toWireRequest(command(), MemoryContext.empty(), user());

        assertThat(request.tenantId()).isEqualTo("tenant1");
        assertThat(request.userId()).isEqualTo("user1");
        assertThat(request.sessionId()).isEqualTo("session1");
        assertThat(request.message()).isEqualTo("今年库户的总利润是多少");
        assertThat(request.attachments()).isEmpty();
        assertThat(request.metadata()).isEmpty();
        assertThat(request.memory()).isEqualTo(MemoryContext.empty());
    }

    @Test
    void mapsWrappedIntentResponseToInternalDecision() throws Exception {
        String response = """
                {
                  "code": 200,
                  "data": {
                    "result": {
                      "items": [
                        {
                          "confidence": 0.95,
                          "intentId": "98989898dffd888df88789",
                          "intentName": "财经智能问答",
                          "resourceInstruction": {
                            "resourceId": "FIN-SKL-88888888"
                          },
                          "score": null,
                          "source": "llm"
                        }
                      ],
                      "message": "[用户问题]使用财经智能问数的能力，查询今年库户的总利润是多少\\n[识别结果]匹配成功: 财经智能问答;\\n"
                    },
                    "message": "success",
                    "status": "success"
                  }
                }
                """;

        IntentDecision decision = withServer(response).recognize(command(), MemoryContext.empty(), user());

        assertThat(decision.intentCode()).isEqualTo("98989898dffd888df88789");
        assertThat(decision.intentName()).isEqualTo("财经智能问答");
        assertThat(decision.complexity()).isEqualTo(TaskComplexity.SIMPLE);
        assertThat(decision.confidence()).isEqualTo(0.95);
        assertThat(decision.simpleTask()).isTrue();
        assertThat(decision.candidateSubAgentCode()).isEqualTo("FIN-SKL-88888888");
        assertThat(decision.slots()).containsEntry("resourceId", "FIN-SKL-88888888")
                .containsEntry("source", "llm");
        assertThat(decision.raw()).containsKey("selectedItem")
                .containsEntry("resultMessage", "[用户问题]使用财经智能问数的能力，查询今年库户的总利润是多少\n[识别结果]匹配成功: 财经智能问答;\n");
    }

    @Test
    void selectsHighestConfidenceItem() throws Exception {
        String response = """
                {
                  "code": 200,
                  "data": {
                    "status": "success",
                    "result": {
                      "items": [
                        {"confidence": 0.65, "intentId": "low", "intentName": "低置信", "resourceInstruction": {"resourceId": "LOW"}},
                        {"confidence": 0.91, "intentId": "high", "intentName": "高置信", "resourceInstruction": {"resourceId": "HIGH"}}
                      ]
                    }
                  }
                }
                """;

        IntentDecision decision = withServer(response).recognize(command(), MemoryContext.empty(), user());

        assertThat(decision.intentCode()).isEqualTo("high");
        assertThat(decision.candidateSubAgentCode()).isEqualTo("HIGH");
        assertThat(decision.confidence()).isEqualTo(0.91);
    }

    @Test
    void degradesWhenIntentServiceReturnsFailureWrapper() throws Exception {
        String response = """
                {"code":500,"data":{"status":"failed","message":"down"}}
                """;

        IntentDecision decision = withServer(response).recognize(command(), MemoryContext.empty(), user());

        assertThat(decision.complexity()).isEqualTo(TaskComplexity.COMPLEX);
        assertThat(decision.simpleTask()).isFalse();
        assertThat(decision.candidateSubAgentCode()).isNull();
        assertThat(decision.raw()).containsEntry("reason", "intent response code is not 200");
    }

    @Test
    void retriesFailedIntentCallsUntilSuccess() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        IntentServerFixture fixture = withServerSequence(attempts,
                """
                {"code":500,"data":{"status":"failed","message":"down"}}
                """,
                """
                {"code":500,"data":{"status":"failed","message":"still-down"}}
                """,
                """
                {"code":200,"data":{"status":"success","result":{"items":[
                  {"confidence":0.91,"intentId":"high","intentName":"高置信","resourceInstruction":{"resourceId":"HIGH"}}
                ]}}}
                """);

        try {
            IntentDecision decision = fixture.service().recognize(command(), MemoryContext.empty(), user());

            assertThat(decision.intentCode()).isEqualTo("high");
            assertThat(decision.candidateSubAgentCode()).isEqualTo("HIGH");
            assertThat(attempts.get()).isEqualTo(3);
        } finally {
            fixture.close();
        }
    }

    @Test
    void stopsAfterConfiguredIntentRetries() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        IntentServerFixture fixture = withServerSequence(attempts,
                """
                {"code":500,"data":{"status":"failed","message":"down-1"}}
                """,
                """
                {"code":500,"data":{"status":"failed","message":"down-2"}}
                """,
                """
                {"code":500,"data":{"status":"failed","message":"down-3"}}
                """,
                """
                {"code":500,"data":{"status":"failed","message":"down-4"}}
                """,
                """
                {"code":200,"data":{"status":"success","result":{"items":[
                  {"confidence":0.91,"intentId":"late","intentName":"迟到","resourceInstruction":{"resourceId":"LATE"}}
                ]}}}
                """);

        try {
            IntentDecision decision = fixture.service().recognize(command(), MemoryContext.empty(), user());

            assertThat(decision.intentCode()).isEqualTo("finance.runtime.intent_error");
            assertThat(attempts.get()).isEqualTo(4);
        } finally {
            fixture.close();
        }
    }

    @Test
    void doesNotRetryValidNoIntentResult() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        IntentServerFixture fixture = withServerSequence(attempts,
                """
                {"code":200,"data":{"status":"success","result":{"items":[]}}}
                """,
                """
                {"code":200,"data":{"status":"success","result":{"items":[
                  {"confidence":0.91,"intentId":"unexpected","intentName":"不应重试","resourceInstruction":{"resourceId":"UNEXPECTED"}}
                ]}}}
                """);

        try {
            IntentDecision decision = fixture.service().recognize(command(), MemoryContext.empty(), user());

            assertThat(decision.intentCode()).isEqualTo("finance.runtime.no_intent");
            assertThat(decision.candidateSubAgentCode()).isNull();
            assertThat(attempts.get()).isEqualTo(1);
        } finally {
            fixture.close();
        }
    }

    @Test
    void customRetryPolicyCanDisableRetryForFailedIntentResult() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        IntentRetryPolicy noRetry = context -> false;
        IntentServerFixture fixture = withServerSequence(attempts, noRetry,
                """
                {"code":500,"data":{"status":"failed","message":"down"}}
                """,
                """
                {"code":200,"data":{"status":"success","result":{"items":[
                  {"confidence":0.91,"intentId":"unexpected","intentName":"不应重试","resourceInstruction":{"resourceId":"UNEXPECTED"}}
                ]}}}
                """);

        try {
            IntentDecision decision = fixture.service().recognize(command(), MemoryContext.empty(), user());

            assertThat(decision.intentCode()).isEqualTo("finance.runtime.intent_error");
            assertThat(attempts.get()).isEqualTo(1);
        } finally {
            fixture.close();
        }
    }

    @Test
    void failingRetryPolicyDoesNotBreakIntentRecognition() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        IntentRetryPolicy brokenPolicy = context -> {
            throw new IllegalStateException("policy down");
        };
        IntentServerFixture fixture = withServerSequence(attempts, brokenPolicy,
                """
                {"code":500,"data":{"status":"failed","message":"down"}}
                """,
                """
                {"code":200,"data":{"status":"success","result":{"items":[
                  {"confidence":0.91,"intentId":"unexpected","intentName":"不应重试","resourceInstruction":{"resourceId":"UNEXPECTED"}}
                ]}}}
                """);

        try {
            IntentDecision decision = fixture.service().recognize(command(), MemoryContext.empty(), user());

            assertThat(decision.intentCode()).isEqualTo("finance.runtime.intent_error");
            assertThat(attempts.get()).isEqualTo(1);
        } finally {
            fixture.close();
        }
    }

    @Test
    void defaultRetryPolicyDoesNotRetryWhenAttemptsAreExhausted() {
        IntentRetryContext context = new IntentRetryContext(command(), MemoryContext.empty(), user(),
                new IntentServiceResponseMapper(objectMapper).degraded("down"), 1, 1);

        assertThat(new DefaultIntentRetryPolicy().shouldRetry(context)).isFalse();
    }

    @Test
    void normalizesConfiguredRetryCountToSafeRange() {
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();

        properties.setMaxRetries(-1);
        assertThat(properties.normalizedMaxRetries()).isZero();

        properties.setMaxRetries(100);
        assertThat(properties.normalizedMaxRetries()).isEqualTo(10);
    }

    @Test
    void appliesConfiguredOutboundAuthorizationHeader() throws Exception {
        AtomicReference<String> capturedAuthorization = new AtomicReference<>();
        IntentDecision decision = withServer("""
                {"code":500,"data":{"status":"failed","message":"down"}}
                """, capturedAuthorization, authHeaders(Optional.of("Bearer intent-token")))
                .recognize(command(), MemoryContext.empty(), user());

        assertThat(capturedAuthorization.get()).isEqualTo("Bearer intent-token");
        assertThat(decision.complexity()).isEqualTo(TaskComplexity.COMPLEX);
    }

    private FinEurekaIntentService withServer(String response) throws IOException {
        AuthHeaderProviderRegistry authHeaders = new AuthHeaderProviderRegistry(
                new IntegrationAuthProperties(), List.of(new NoopAuthHeaderProvider()));
        return withServer(response, new AtomicReference<>(), authHeaders);
    }

    private FinEurekaIntentService withServer(String response, AtomicReference<String> capturedAuthorization,
                                              AuthHeaderProviderRegistry authHeaders) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/recognize", exchange -> {
            capturedAuthorization.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            } finally {
                server.stop(0);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setBaseUrl(baseUrl);
        properties.setRecognizePath("/recognize");
        properties.setMaxRetries(0);
        return new FinEurekaIntentService(WebClient.builder(), properties, wireMapper(), authHeaders,
                new DefaultIntentRetryPolicy());
    }

    private IntentServerFixture withServerSequence(AtomicInteger attempts, String... responses) throws IOException {
        return withServerSequence(attempts, new DefaultIntentRetryPolicy(), responses);
    }

    private IntentServerFixture withServerSequence(AtomicInteger attempts, IntentRetryPolicy retryPolicy,
                                                   String... responses) throws IOException {
        AuthHeaderProviderRegistry authHeaders = new AuthHeaderProviderRegistry(
                new IntegrationAuthProperties(), List.of(new NoopAuthHeaderProvider()));
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/recognize", exchange -> {
            int attempt = attempts.incrementAndGet();
            String response = responses[Math.min(attempt, responses.length) - 1];
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setBaseUrl(baseUrl);
        properties.setRecognizePath("/recognize");
        properties.setMaxRetries(3);
        FinEurekaIntentService service = new FinEurekaIntentService(WebClient.builder(), properties,
                wireMapper(), authHeaders, retryPolicy);
        return new IntentServerFixture(service, server);
    }

    private IntentServiceWireMapper wireMapper() {
        return new IntentServiceWireMapper(new IntentServiceRequestMapper(),
                new IntentServiceResponseMapper(objectMapper));
    }

    private record IntentServerFixture(FinEurekaIntentService service, HttpServer server) implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }

    private AuthHeaderProviderRegistry authHeaders(Optional<String> token) {
        IntegrationAuthProperties properties = new IntegrationAuthProperties();
        properties.setEnabled(true);
        return new AuthHeaderProviderRegistry(properties, List.of(
                new NoopAuthHeaderProvider(),
                new SgovAuthHeaderProvider(properties, (request, appId, secret) -> token)
        ));
    }

    private ChatCommand command() {
        return new ChatCommand("cmd1", "tenant1", "user1", "session1", null, "web",
                "今年库户的总利润是多少", List.of(), Map.of());
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "Alice");
    }
}
