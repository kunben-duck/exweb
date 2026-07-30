package com.huawei.it.ex.one.infrastructure.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.IntegrationAuthProperties;
import com.huawei.it.ex.one.application.integration.intent.IntentRecognitionResult;
import com.huawei.it.ex.one.application.integration.intent.IntentRetryContext;
import com.huawei.it.ex.one.application.integration.intent.IntentRetryPolicy;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.memory.RouteMemoryContext;
import com.huawei.it.ex.one.infrastructure.auth.NoopAuthHeaderProvider;
import com.huawei.it.ex.one.infrastructure.auth.SgovAuthHeaderProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class FinEurekaIntentServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsInternalCommandToIntentWireRequest() {
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setAccessName("eureka2_260718");
        IntentRecognizeRequest request = new IntentServiceRequestMapper(properties)
                .toWireRequest(command(), MemoryContext.empty(), user());

        assertThat(request.accessName()).isEqualTo("eureka2_260718");
        assertThat(request.query()).isEqualTo("今年库户的总利润是多少");
        assertThat(request.userId()).isEqualTo("user1");
        assertThat(request.conversationContext())
                .containsEntry("routeTrigger", "first_turn")
                .containsEntry("lastIntentRejectReason", Map.of())
                .containsEntry("history", List.of());
        assertThat(request.options()).containsEntry("trace", false);
    }

    @Test
    void mapsRouteMemoryContextToIntentConversationContext() {
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setAccessName("eureka2_260718");
        RouteMemoryContext routeMemory = new RouteMemoryContext(
                "domain_reject",
                List.of(Map.of("type", "route", "query", "支付成功率口径",
                                "intent", "财经智能问数;财经知识助手"),
                        Map.of("type", "clarify", "query", "看下方案",
                                "clarifyQuestion", "你想看处理方案还是项目方案？",
                                "clarificationType", "AMBIGUOUS_ROUTE")),
                Map.of("lastIntent", "财经深度研究", "domainRejectMessage", "不属于当前领域"));

        IntentRecognizeRequest request = new IntentServiceRequestMapper(properties)
                .toWireRequest(command(), MemoryContext.empty().withRouteMemory(routeMemory), user());

        assertThat(request.conversationContext())
                .containsEntry("routeTrigger", "domain_reject")
                .containsEntry("lastIntentRejectReason",
                        Map.of("lastIntent", "财经深度研究", "domainRejectMessage", "不属于当前领域"));
        assertThat(request.conversationContext().get("history")).isEqualTo(routeMemory.history());
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
                          "accessName": "FIN-SKL-88888888",
                          "routeAction": "ignored-item-field",
                          "resourceInstruction": {
                            "resourceId": "FIN-SKL-88888888"
                          },
                          "score": null,
                          "source": "llm"
                        }
                      ],
                      "routeAction": "ROUTE_SINGLE",
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
        assertThat(decision.candidateDomainAgentId()).isEqualTo("FIN-SKL-88888888");
        assertThat(decision.slots()).containsEntry("intentId", "98989898dffd888df88789")
                .containsEntry("accessName", "FIN-SKL-88888888")
                .containsEntry("resourceId", "FIN-SKL-88888888")
                .containsEntry("source", "llm");
        assertThat(decision.raw()).containsKey("selectedItem")
                .containsEntry("resultMessage", "[用户问题]使用财经智能问数的能力，查询今年库户的总利润是多少\n[识别结果]匹配成功: 财经智能问答;\n");
    }

    @Test
    void missingRouteActionDoesNotSelectByConfidence() throws Exception {
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

        assertThat(decision.intentCode()).isEqualTo("finance.runtime.intent_error");
        assertThat(decision.candidateDomainAgentId()).isNull();
        assertThat(decision.confidence()).isZero();
        assertThat(decision.raw()).containsEntry("reason", "routeAction missing");
    }

    @Test
    void routeSingleUsesFirstItemAccessNameAsDomainAgentId() throws Exception {
        String response = """
                {
                  "code": 200,
                  "status": "success",
                  "data": {
                    "result": {
                      "routeAction": "ROUTE_SINGLE",
                      "items": [
                        {
                          "confidence": 0.51,
                          "intentId": "intent_as_skill",
                          "intentName": "指定领域能力",
                          "accessName": "domain_skill",
                          "resourceInstruction": {"resourceId": "legacy-resource"}
                        }
                      ],
                      "clarification": null
                    }
                  }
                }
                """;

        IntentDecision decision = withServer(response).recognize(command(), MemoryContext.empty(), user());

        assertThat(decision.intentCode()).isEqualTo("intent_as_skill");
        assertThat(decision.candidateDomainAgentId()).isEqualTo("domain_skill");
        assertThat(decision.slots()).containsEntry("accessName", "domain_skill")
                .containsEntry("resourceId", "legacy-resource");
    }

    @Test
    void routeMultiAndNoMatchEnterAgentRuntime() throws Exception {
        IntentDecision routeMulti = withServer("""
                {"code":200,"status":"success","data":{"result":{"routeAction":"ROUTE_MULTI","items":[
                  {"confidence":0.91,"intentId":"a","intentName":"A"},
                  {"confidence":0.89,"intentId":"b","intentName":"B"}
                ],"clarification":null}}}
                """).recognize(command(), MemoryContext.empty(), user());
        IntentDecision noMatch = withServer("""
                {"code":200,"status":"success","data":{"result":{"routeAction":"NO_MATCH","items":[],"clarification":null}}}
                """).recognize(command(), MemoryContext.empty(), user());

        assertThat(routeMulti.complexity()).isEqualTo(TaskComplexity.COMPLEX);
        assertThat(routeMulti.candidateDomainAgentId()).isNull();
        assertThat(routeMulti.slots())
                .containsEntry("routeAction", "ROUTE_MULTI")
                .containsEntry("candidateIntentNames", List.of("A", "B"));
        assertThat(routeMulti.raw()).containsEntry("reason", "routeAction=ROUTE_MULTI");
        assertThat(noMatch.complexity()).isEqualTo(TaskComplexity.COMPLEX);
        assertThat(noMatch.intentCode()).isEqualTo("finance.runtime.no_intent");
        assertThat(noMatch.intentName())
                .isEqualTo("未识别到可用意图，进入 FIN Supervisor Agent");
        assertThat(noMatch.candidateDomainAgentId()).isNull();
        assertThat(noMatch.slots()).containsEntry("routeAction", "NO_MATCH");
        assertThat(noMatch.raw()).containsEntry("reason", "routeAction=NO_MATCH");
    }

    @Test
    void clarifyRouteActionReturnsWaitingClarification() throws Exception {
        IntentRecognitionResult result = withServer("""
                {
                  "code": 200,
                  "status": "success",
                  "data": {
                    "result": {
                      "routeAction": "CLARIFY",
                      "items": [],
                      "intentSessionId": "intent-session-1",
                      "intentRequestId": "intent-request-1",
                      "clarification": {
                        "type": "AMBIGUOUS_ROUTE",
                        "clarifyQuestion": "你想看处理方案还是项目方案？",
                        "candidateIntents": [
                          {
                            "intentId":"deep_analysis",
                            "intentName":"财经深度研究",
                            "confidence":0.72,
                            "accessName":"domain_agent_deep_analysis"
                          }
                        ]
                      }
                    }
                  }
                }
                """).recognizeForRouting(command(), MemoryContext.empty(), user());

        assertThat(result.waitingClarification()).isTrue();
        assertThat(result.intentSessionId()).isEqualTo("intent-session-1");
        assertThat(result.intentRequestId()).isEqualTo("intent-request-1");
        assertThat(result.clarificationPayload())
                .containsEntry("routeAction", "CLARIFY")
                .containsEntry("type", "AMBIGUOUS_ROUTE")
                .containsEntry("clarifyQuestion", "你想看处理方案还是项目方案？");
        assertThat(result.clarificationPayload().get("candidateIntents"))
                .isEqualTo(List.of(Map.of(
                        "intentId", "deep_analysis",
                        "intentName", "财经深度研究",
                        "confidence", 0.72,
                        "accessName", "domain_agent_deep_analysis",
                        "skillId", "domain_agent_deep_analysis")));
        assertThat(result.clarificationPayload()).doesNotContainKey("rawIntentResponse");
    }

    @Test
    void degradesWhenIntentServiceReturnsFailureWrapper() throws Exception {
        String response = """
                {"code":500,"data":{"status":"failed","message":"down"}}
                """;

        IntentDecision decision = withServer(response).recognize(command(), MemoryContext.empty(), user());

        assertThat(decision.complexity()).isEqualTo(TaskComplexity.COMPLEX);
        assertThat(decision.simpleTask()).isFalse();
        assertThat(decision.candidateDomainAgentId()).isNull();
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
                {"code":200,"data":{"status":"success","result":{"routeAction":"ROUTE_SINGLE","items":[
                  {"confidence":0.91,"intentId":"high","intentName":"高置信","accessName":"high-skill","resourceInstruction":{"resourceId":"HIGH"}}
                ]}}}
                """);

        try {
            IntentDecision decision = fixture.service().recognize(command(), MemoryContext.empty(), user());

            assertThat(decision.intentCode()).isEqualTo("high");
            assertThat(decision.candidateDomainAgentId()).isEqualTo("high-skill");
            assertThat(attempts.get()).isEqualTo(3);
        } finally {
            fixture.close();
        }
    }

    @Test
    void retriesProtocolFailureUntilValidRouteArrives() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        IntentServerFixture fixture = withServerSequence(attempts,
                """
                {"code":200,"status":"success","data":{"result":{"routeAction":"ROUTE_SINGLE","items":[
                  {"intentId":"broken","intentName":"缺少目标"}
                ]}}}
                """,
                """
                {"code":200,"status":"success","data":{"result":{"routeAction":"ROUTE_SINGLE","items":[
                  {"intentId":"valid","intentName":"有效目标","accessName":"valid-skill"}
                ]}}}
                """);

        try {
            IntentDecision decision = fixture.service().recognize(command(), MemoryContext.empty(), user());

            assertThat(decision.intentCode()).isEqualTo("valid");
            assertThat(decision.candidateDomainAgentId()).isEqualTo("valid-skill");
            assertThat(attempts.get()).isEqualTo(2);
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
                {"code":200,"data":{"status":"success","result":{"routeAction":"NO_MATCH","items":[]}}}
                """,
                """
                {"code":200,"data":{"status":"success","result":{"items":[
                  {"confidence":0.91,"intentId":"unexpected","intentName":"不应重试","resourceInstruction":{"resourceId":"UNEXPECTED"}}
                ]}}}
                """);

        try {
            IntentDecision decision = fixture.service().recognize(command(), MemoryContext.empty(), user());

            assertThat(decision.intentCode()).isEqualTo("finance.runtime.no_intent");
            assertThat(decision.candidateDomainAgentId()).isNull();
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
                new IntentServiceResponseMapper(objectMapper, new IntentServiceHttpProperties()).degraded("down"), 1, 1);

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
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setAccessName("eureka2_260718");
        return new IntentServiceWireMapper(new IntentServiceRequestMapper(properties),
                new IntentServiceResponseMapper(objectMapper, properties));
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
