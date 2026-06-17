package com.huawei.finance.front.one.infrastructure.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.intent.TaskComplexity;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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

    private FinEurekaIntentService withServer(String response) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/recognize", exchange -> {
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
        return new FinEurekaIntentService(WebClient.builder(), properties, new IntentServiceRequestMapper(),
                new IntentServiceResponseMapper(objectMapper));
    }

    private ChatCommand command() {
        return new ChatCommand("cmd1", "tenant1", "user1", "session1", null, "web",
                "今年库户的总利润是多少", List.of(), Map.of());
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "Alice");
    }
}
