package com.huawei.finance.front.one.infrastructure.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.AgentRuntimeForwardCookieProperties;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class RelayWebSocketRuntimeAdapterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void newRuntimeSessionSendsConfigThenUserMessageAndCompletesOnIdleState() throws Exception {
        FakeWebSocketClient client = new FakeWebSocketClient(List.of(
                "{\"type\":\"relay-start\",\"content\":\"initializing\"}",
                "{\"type\":\"relay-progress\",\"content\":\"loading modes\"}",
                "{\"type\":\"project_home\",\"project_home\":\"/tmp/relay\"}",
                "{\"type\":\"available-modes\",\"modes\":[]}",
                "{\"type\":\"relay-end\",\"content\":\"ready\"}",
                "{\"type\":\"session-ready\",\"session_id\":\"relay-session-1\",\"session_mode\":\"new\"}",
                "{\"type\":\"agent\",\"content\":\"你好\",\"is_streaming\":true,\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"session-state\",\"state\":\"idle\",\"session_id\":\"relay-session-1\"}"
        ));
        RelayWebSocketRuntimeAdapter adapter = adapter(client);

        StepVerifier.create(adapter.query(request(null, RuntimeForwardHeaders.empty())))
                .assertNext(this::assertSessionReadyMetadata)
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo("message.delta");
                    assertThat(event.payload())
                            .containsEntry("delta", "你好")
                            .containsEntry("runtimeSessionId", "relay-session-1");
                })
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo("runtime.metadata");
                    assertThat(event.payload()).containsEntry("state", "idle");
                })
                .assertNext(event -> assertThat(event.type()).isEqualTo("message.completed"))
                .verifyComplete();

        assertThat(client.uri()).hasToString("ws://relay.test/ws/run1");
        assertThat(client.sent()).hasSize(2);
        JsonNode config = objectMapper.readTree(client.sent().get(0));
        JsonNode userMessage = objectMapper.readTree(client.sent().get(1));
        assertThat(config.path("type").asText()).isEqualTo("config");
        assertThat(config.path("config").path("sessionMode").asText()).isEqualTo("new");
        assertThat(config.path("config").path("sessionId").asText()).isEqualTo("run1");
        assertThat(config.path("config").path("uid").asText()).isEqualTo("user1");
        assertThat(config.path("config").path("appMode").asText()).isEqualTo("delegate");
        assertThat(userMessage.path("type").asText()).isEqualTo("user-message");
        assertThat(userMessage.path("content").asText()).isEqualTo("hello");
    }

    @Test
    void existingRuntimeSessionUsesResumeModeAndForwardsCookieWhenAllowed() throws Exception {
        FakeWebSocketClient client = new FakeWebSocketClient(List.of(
                "{\"type\":\"session-ready\",\"session_id\":\"relay-session-old\",\"session_mode\":\"resume\"}",
                "{\"type\":\"agent\",\"content\":\"继续\",\"session_id\":\"relay-session-old\"}",
                "{\"type\":\"session-state\",\"state\":\"completed\",\"session_id\":\"relay-session-old\"}"
        ));
        RelayWebSocketRuntimeAdapter adapter = adapter(client);

        StepVerifier.create(adapter.query(request("relay-session-old",
                        RuntimeForwardHeaders.fromCookieHeader("sid=abc", 8192))))
                .assertNext(event -> assertThat(event.payload())
                        .containsEntry("metadataType", "relay_session_ready")
                        .containsEntry("runtimeSessionId", "relay-session-old"))
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "继续"))
                .expectNextCount(2)
                .verifyComplete();

        JsonNode config = objectMapper.readTree(client.sent().get(0));
        assertThat(config.path("config").path("sessionMode").asText()).isEqualTo("resume");
        assertThat(config.path("config").path("sessionId").asText()).isEqualTo("relay-session-old");
        assertThat(config.path("config").path("supports_incremental_recovery").asBoolean()).isTrue();
        assertThat(client.headers().getFirst(HttpHeaders.COOKIE)).isEqualTo("sid=abc");
    }

    @Test
    void sessionReadyCompletesConfigHandshakeAndPublishesRuntimeSessionMetadata() {
        FakeWebSocketClient client = new FakeWebSocketClient(List.of(
                "{\"type\":\"system\",\"content\":\"Connected\"}",
                "{\"type\":\"session-id\",\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"session-ready\",\"session_id\":\"relay-session-1\",\"session_mode\":\"new\"}",
                "{\"type\":\"agent\",\"content\":\"回答\",\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"session-state\",\"state\":\"completed\",\"session_id\":\"relay-session-1\"}"
        ));
        RelayWebSocketRuntimeAdapter adapter = adapter(client);

        StepVerifier.create(adapter.query(request(null, RuntimeForwardHeaders.empty())))
                .assertNext(this::assertSessionReadyMetadata)
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo("message.delta");
                    assertThat(event.payload()).containsEntry("delta", "回答");
                })
                .expectNextCount(2)
                .verifyComplete();

        assertThat(client.sent()).hasSize(2);
        assertThat(client.sent().get(1)).contains("\"type\":\"user-message\"");
    }

    @Test
    void configSuccessResponseDoesNotCompleteHandshake() {
        FakeWebSocketClient client = new FakeWebSocketClient(List.of(
                "{\"type\":\"config\",\"ready\":true,\"session_id\":\"relay-session-1\"}"
        ));
        RelayWebSocketRuntimeAdapter adapter = adapter(client);

        StepVerifier.create(adapter.query(request(null, RuntimeForwardHeaders.empty())))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(RelayRuntimeProtocolException.class)
                        .hasMessageContaining("closed before config handshake completed"))
                .verify();

        assertThat(client.sent()).hasSize(1);
        assertThat(client.sent().getFirst()).contains("\"type\":\"config\"");
    }

    @Test
    void lateConfigFrameAfterUserMessageIsIgnored() {
        FakeWebSocketClient client = new FakeWebSocketClient(List.of(
                "{\"type\":\"session-ready\",\"session_id\":\"relay-session-1\",\"session_mode\":\"new\"}",
                "{\"type\":\"agent\",\"content\":\"A\",\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"config\",\"status\":\"success\"}",
                "{\"type\":\"agent\",\"content\":\"B\",\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"session-state\",\"state\":\"completed\",\"session_id\":\"relay-session-1\"}"
        ));
        RelayWebSocketRuntimeAdapter adapter = adapter(client);

        StepVerifier.create(adapter.query(request(null, RuntimeForwardHeaders.empty())))
                .assertNext(this::assertSessionReadyMetadata)
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "A"))
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "B"))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void sessionStateBeforeRelayStartAfterUserMessageIsIgnored() {
        FakeWebSocketClient client = new FakeWebSocketClient(List.of(
                "{\"type\":\"session-ready\",\"session_id\":\"relay-session-1\",\"session_mode\":\"new\"}",
                "{\"type\":\"session-state\",\"state\":\"agent_thinking\",\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"relay-start\",\"content\":\"processing\",\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"agent\",\"content\":\"A\",\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"session-state\",\"state\":\"completed\",\"session_id\":\"relay-session-1\"}"
        ));
        RelayWebSocketRuntimeAdapter adapter = adapter(client);

        StepVerifier.create(adapter.query(request(null, RuntimeForwardHeaders.empty())))
                .assertNext(this::assertSessionReadyMetadata)
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo("runtime.progress");
                    assertThat(event.payload())
                            .containsEntry("sourceType", "relay-start")
                            .containsEntry("text", "processing");
                })
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "A"))
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo("runtime.metadata");
                    assertThat(event.payload()).containsEntry("state", "completed");
                })
                .assertNext(event -> assertThat(event.type()).isEqualTo("message.completed"))
                .verifyComplete();
    }

    @Test
    void idleAndCompletedBeforeRelayStartAfterUserMessageDoNotCloseEmptyAnswer() {
        FakeWebSocketClient client = new FakeWebSocketClient(List.of(
                "{\"type\":\"session-ready\",\"session_id\":\"relay-session-1\",\"session_mode\":\"new\"}",
                "{\"type\":\"session-state\",\"state\":\"idle\",\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"session-state\",\"state\":\"completed\",\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"relay-start\",\"content\":\"processing\",\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"agent\",\"content\":\"A\",\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"session-state\",\"state\":\"completed\",\"session_id\":\"relay-session-1\"}"
        ));
        RelayWebSocketRuntimeAdapter adapter = adapter(client);

        StepVerifier.create(adapter.query(request(null, RuntimeForwardHeaders.empty())))
                .assertNext(this::assertSessionReadyMetadata)
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo("runtime.progress");
                    assertThat(event.payload()).containsEntry("sourceType", "relay-start");
                })
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "A"))
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo("runtime.metadata");
                    assertThat(event.payload()).containsEntry("state", "completed");
                })
                .assertNext(event -> assertThat(event.type()).isEqualTo("message.completed"))
                .verifyComplete();
    }

    @Test
    void businessFrameStartsResponseWhenRelayStartIsMissingAndWaitingUserInputTerminates() {
        FakeWebSocketClient client = new FakeWebSocketClient(List.of(
                "{\"type\":\"session-ready\",\"session_id\":\"relay-session-1\",\"session_mode\":\"new\"}",
                "{\"type\":\"session-state\",\"state\":\"agent_thinking\",\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"agent\",\"content\":\"A\",\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"session-state\",\"state\":\"waiting_user_input\",\"detail\":\"Waiting\","
                        + "\"session_id\":\"relay-session-1\"}"
        ));
        RelayWebSocketRuntimeAdapter adapter = adapter(client);

        StepVerifier.create(adapter.query(request(null, RuntimeForwardHeaders.empty())))
                .assertNext(this::assertSessionReadyMetadata)
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "A"))
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo("runtime.metadata");
                    assertThat(event.payload())
                            .containsEntry("state", "waiting_user_input")
                            .containsEntry("detail", "Waiting");
                })
                .assertNext(event -> assertThat(event.type()).isEqualTo("message.completed"))
                .verifyComplete();
    }

    @Test
    void waitingUserInputCanStartAndTerminateResponse() {
        FakeWebSocketClient client = new FakeWebSocketClient(List.of(
                "{\"type\":\"session-ready\",\"session_id\":\"relay-session-1\",\"session_mode\":\"new\"}",
                "{\"type\":\"session-state\",\"state\":\"waiting_user_input\",\"detail\":\"Waiting\","
                        + "\"session_id\":\"relay-session-1\"}"
        ));
        RelayWebSocketRuntimeAdapter adapter = adapter(client);

        StepVerifier.create(adapter.query(request(null, RuntimeForwardHeaders.empty())))
                .assertNext(this::assertSessionReadyMetadata)
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo("runtime.metadata");
                    assertThat(event.payload())
                            .containsEntry("state", "waiting_user_input")
                            .containsEntry("detail", "Waiting");
                })
                .assertNext(event -> assertThat(event.type()).isEqualTo("message.completed"))
                .verifyComplete();
    }

    @Test
    void pausedCanStartAndTerminateResponse() {
        FakeWebSocketClient client = new FakeWebSocketClient(List.of(
                "{\"type\":\"session-ready\",\"session_id\":\"relay-session-1\",\"session_mode\":\"new\"}",
                "{\"type\":\"session-state\",\"state\":\"paused\",\"detail\":\"Interrupted\","
                        + "\"session_id\":\"relay-session-1\"}"
        ));
        RelayWebSocketRuntimeAdapter adapter = adapter(client);

        StepVerifier.create(adapter.query(request(null, RuntimeForwardHeaders.empty())))
                .assertNext(this::assertSessionReadyMetadata)
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo("runtime.metadata");
                    assertThat(event.payload())
                            .containsEntry("state", "paused")
                            .containsEntry("detail", "Interrupted");
                })
                .assertNext(event -> assertThat(event.type()).isEqualTo("message.completed"))
                .verifyComplete();
    }

    @Test
    void generateResponseAfterDeltaBecomesSnapshotBeforeTerminalState() {
        FakeWebSocketClient client = new FakeWebSocketClient(List.of(
                "{\"type\":\"session-ready\",\"session_id\":\"relay-session-1\",\"session_mode\":\"new\"}",
                "{\"type\":\"relay-start\",\"content\":\"processing\",\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"agent\",\"content\":\"草稿\",\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"generate-response\",\"content\":\"完整总结\",\"is_final\":true,"
                        + "\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"agent-call\",\"is_start\":false,\"agent_name\":\"delegate\","
                        + "\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"session-state\",\"state\":\"idle\",\"session_id\":\"relay-session-1\"}"
        ));
        RelayWebSocketRuntimeAdapter adapter = adapter(client);

        StepVerifier.create(adapter.query(request(null, RuntimeForwardHeaders.empty())))
                .assertNext(this::assertSessionReadyMetadata)
                .assertNext(event -> assertThat(event.type()).isEqualTo("runtime.progress"))
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "草稿"))
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo("message.snapshot");
                    assertThat(event.payload())
                            .containsEntry("content", "完整总结")
                            .containsEntry("sourceType", "generate-response");
                })
                .assertNext(event -> assertThat(event.type()).isEqualTo("runtime.agent"))
                .assertNext(event -> assertThat(event.type()).isEqualTo("runtime.metadata"))
                .assertNext(event -> assertThat(event.type()).isEqualTo("message.completed"))
                .verifyComplete();
    }

    @Test
    void configHandshakeTimeoutFailsBeforeSendingUserMessage() {
        FakeWebSocketClient client = new FakeWebSocketClient(Flux.never());
        RelayWebSocketRuntimeAdapter adapter = adapter(client, Duration.ofMillis(5));

        StepVerifier.create(adapter.query(request(null, RuntimeForwardHeaders.empty())))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(RelayRuntimeProtocolException.class)
                        .hasMessageContaining("RELAY_WS_CONFIG_TIMEOUT"))
                .verify();

        assertThat(client.sent()).hasSize(1);
        assertThat(client.sent().getFirst()).contains("\"type\":\"config\"");
    }

    @Test
    void configStageErrorFailsBeforeSendingUserMessage() {
        assertConfigHandshakeFails(
                "{\"type\":\"error\",\"message\":\"Initialization failed\"}",
                "error: Initialization failed");
    }

    @Test
    void configStageClearSessionFailsBeforeSendingUserMessage() {
        assertConfigHandshakeFails(
                "{\"type\":\"clear-session\",\"reason\":\"session_not_found\","
                        + "\"message\":\"Session not found\"}",
                "clear-session: Session not found");
    }

    @Test
    void configStageSessionMismatchFailsBeforeSendingUserMessage() {
        assertConfigHandshakeFails(
                "{\"type\":\"session-mismatch\",\"expected\":\"/a\",\"got\":\"/b\"}",
                "session-mismatch");
    }

    @Test
    void shortConnectionReconnectsAndSendsConfigForEachRun() {
        ReusableFakeWebSocketClient client = new ReusableFakeWebSocketClient();
        RelayWebSocketRuntimeAdapter adapter = adapter(client, Duration.ofSeconds(10));

        StepVerifier.create(adapter.query(request("relay-session-1", RuntimeSessionMode.NEW, "run1")))
                .then(() -> client.emit("{\"type\":\"session-ready\",\"session_id\":\"relay-session-1\"}"))
                .then(() -> client.emit("{\"type\":\"agent\",\"content\":\"A\",\"session_id\":\"relay-session-1\"}"))
                .then(() -> client.emit("{\"type\":\"session-state\",\"state\":\"completed\",\"session_id\":\"relay-session-1\"}"))
                .assertNext(this::assertSessionReadyMetadata)
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "A"))
                .expectNextCount(2)
                .verifyComplete();

        StepVerifier.create(adapter.query(request("relay-session-1", RuntimeSessionMode.RESUME, "run2")))
                .then(() -> client.emit("{\"type\":\"session-ready\",\"session_id\":\"relay-session-1\"}"))
                .then(() -> client.emit("{\"type\":\"agent\",\"content\":\"B\",\"session_id\":\"relay-session-1\"}"))
                .then(() -> client.emit("{\"type\":\"session-state\",\"state\":\"completed\",\"session_id\":\"relay-session-1\"}"))
                .assertNext(this::assertSessionReadyMetadata)
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "B"))
                .expectNextCount(2)
                .verifyComplete();

        assertThat(client.executeCount()).isEqualTo(2);
        assertThat(client.sent()).hasSize(4);
        assertThat(client.sent().get(0)).contains("\"type\":\"config\"");
        assertThat(client.sent().get(1)).contains("\"type\":\"user-message\"").contains("hello-run1");
        assertThat(client.sent().get(2)).contains("\"type\":\"config\"").contains("\"sessionMode\":\"resume\"");
        assertThat(client.sent().get(3)).contains("\"type\":\"user-message\"").contains("hello-run2");
    }

    @Test
    void shortConnectionCancelSendsInterruptFrame() {
        ReusableFakeWebSocketClient client = new ReusableFakeWebSocketClient();
        RelayWebSocketRuntimeAdapter adapter = adapter(client, Duration.ofSeconds(10));

        StepVerifier.create(adapter.query(request("relay-session-1", RuntimeSessionMode.RESUME, "run1")))
                .then(() -> client.emit("{\"type\":\"session-ready\",\"session_id\":\"relay-session-1\"}"))
                .thenAwait(Duration.ofMillis(20))
                .then(() -> adapter.cancel(cancelRequest("run1")).block())
                .thenCancel()
                .verify();

        assertThat(client.executeCount()).isEqualTo(1);
        assertThat(client.sent().get(0)).contains("\"type\":\"config\"");
        assertThat(client.sent()).contains("{\"type\":\"interrupt\"}");
    }

    @Test
    void cancelWithoutActiveExchangeOpensTemporaryResumeConnectionAndSendsInterrupt() throws Exception {
        FakeWebSocketClient client = new FakeWebSocketClient(List.of(
                "{\"type\":\"session-ready\",\"session_id\":\"relay-session-1\"}",
                "{\"type\":\"session-state\",\"state\":\"paused\",\"session_id\":\"relay-session-1\"}"
        ));
        RelayWebSocketRuntimeAdapter adapter = adapter(client);

        StepVerifier.create(adapter.cancel(cancelRequest("run1")))
                .verifyComplete();

        assertThat(client.uri().toString()).isNotEqualTo("ws://relay.test/ws/run1");
        assertThat(client.uri().toString()).startsWith("ws://relay.test/ws/run1-interrupt-");
        assertThat(client.sent()).hasSize(2);
        JsonNode config = objectMapper.readTree(client.sent().get(0));
        assertThat(config.path("type").asText()).isEqualTo("config");
        assertThat(config.path("config").path("sessionMode").asText()).isEqualTo("resume");
        assertThat(config.path("config").path("sessionId").asText()).isEqualTo("relay-session-1");
        assertThat(config.path("config").path("uid").asText()).isEqualTo("user1");
        assertThat(config.path("config").path("supports_incremental_recovery").asBoolean()).isTrue();
        assertThat(client.sent().get(1)).isEqualTo("{\"type\":\"interrupt\"}");
    }

    @Test
    void temporaryInterruptAckTimeoutDoesNotFailCancel() {
        FakeWebSocketClient client = new FakeWebSocketClient(Flux.concat(
                Mono.just("{\"type\":\"session-ready\",\"session_id\":\"relay-session-1\"}"),
                Flux.never()));
        RelayWebSocketRuntimeAdapter adapter = adapter(client, Duration.ofSeconds(10), Duration.ofMillis(5));

        StepVerifier.create(adapter.cancel(cancelRequest("run1")))
                .verifyComplete();

        assertThat(client.sent()).hasSize(2);
        assertThat(client.sent().getFirst()).contains("\"type\":\"config\"");
        assertThat(client.sent().get(1)).isEqualTo("{\"type\":\"interrupt\"}");
    }

    @Test
    void cancelWithoutRuntimeSessionDoesNotOpenTemporaryConnection() {
        ReusableFakeWebSocketClient client = new ReusableFakeWebSocketClient();
        RelayWebSocketRuntimeAdapter adapter = adapter(client, Duration.ofSeconds(10));

        StepVerifier.create(adapter.cancel(cancelRequest("run1", null)))
                .verifyComplete();

        assertThat(client.executeCount()).isZero();
        assertThat(client.sent()).isEmpty();
    }

    @Test
    void temporaryInterruptConfigFailureDoesNotSendInterrupt() {
        FakeWebSocketClient client = new FakeWebSocketClient(List.of(
                "{\"type\":\"session-mismatch\",\"message\":\"bad session\"}"
        ));
        RelayWebSocketRuntimeAdapter adapter = adapter(client);

        StepVerifier.create(adapter.cancel(cancelRequest("run1")))
                .verifyComplete();

        assertThat(client.sent()).hasSize(1);
        assertThat(client.sent().getFirst()).contains("\"type\":\"config\"");
    }

    @Test
    void temporaryInterruptConfigTimeoutDoesNotFailCancel() {
        FakeWebSocketClient client = new FakeWebSocketClient(Flux.never());
        RelayWebSocketRuntimeAdapter adapter = adapter(client, Duration.ofMillis(5));

        StepVerifier.create(adapter.cancel(cancelRequest("run1")))
                .verifyComplete();

        assertThat(client.sent()).hasSize(1);
        assertThat(client.sent().getFirst()).contains("\"type\":\"config\"");
    }

    private void assertConfigHandshakeFails(String configFrame, String expectedMessage) {
        FakeWebSocketClient client = new FakeWebSocketClient(List.of(configFrame));
        RelayWebSocketRuntimeAdapter adapter = adapter(client);

        StepVerifier.create(adapter.query(request(null, RuntimeForwardHeaders.empty())))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(RelayRuntimeProtocolException.class)
                        .hasMessageContaining("Relay WebSocket config handshake failed")
                        .hasMessageContaining(expectedMessage))
                .verify();

        assertThat(client.sent()).hasSize(1);
        assertThat(client.sent().getFirst()).contains("\"type\":\"config\"");
    }

    private void assertSessionReadyMetadata(com.huawei.finance.front.one.domain.chat.ChatEvent event) {
        assertThat(event.type()).isEqualTo("runtime.metadata");
        assertThat(event.payload())
                .containsEntry("metadataType", "relay_session_ready")
                .containsEntry("runtimeSessionId", "relay-session-1");
    }

    private RelayWebSocketRuntimeAdapter adapter(FakeWebSocketClient client) {
        return adapter(client, Duration.ofSeconds(10));
    }

    private RelayWebSocketRuntimeAdapter adapter(FakeWebSocketClient client, Duration configHandshakeTimeout) {
        return adapter((WebSocketClient) client, configHandshakeTimeout);
    }

    private RelayWebSocketRuntimeAdapter adapter(WebSocketClient client, Duration configHandshakeTimeout) {
        return adapter(client, configHandshakeTimeout, Duration.ofSeconds(5));
    }

    private RelayWebSocketRuntimeAdapter adapter(WebSocketClient client, Duration configHandshakeTimeout,
                                                Duration interruptAckTimeout) {
        RelayAgentProperties properties = new RelayAgentProperties();
        properties.getRelay().getWebsocket().setUrl("ws://relay.test/ws");
        properties.getRelay().getWebsocket().setIdleTimeout(Duration.ofSeconds(5));
        properties.getRelay().getWebsocket().setConfigHandshakeTimeout(configHandshakeTimeout);
        properties.getRelay().getWebsocket().setInterruptAckTimeout(interruptAckTimeout);
        return new RelayWebSocketRuntimeAdapter(
                objectMapper,
                properties,
                new AgentRuntimeForwardCookieProperties(),
                new RelayRuntimeResponseNormalizer(objectMapper),
                client);
    }

    private AgentRuntimeRequest request(String runtimeSessionId, RuntimeForwardHeaders forwardHeaders) {
        return request(runtimeSessionId, runtimeSessionId == null ? RuntimeSessionMode.NEW : RuntimeSessionMode.RESUME,
                "run1", "hello", forwardHeaders);
    }

    private AgentRuntimeRequest request(String runtimeSessionId, RuntimeSessionMode sessionMode, String runId) {
        return request(runtimeSessionId, sessionMode, runId, "hello-" + runId, RuntimeForwardHeaders.empty());
    }

    private AgentRuntimeRequest request(String runtimeSessionId, RuntimeSessionMode sessionMode, String runId,
                                        String message, RuntimeForwardHeaders forwardHeaders) {
        return new AgentRuntimeRequest(
                "tenant1",
                "user1",
                "session1",
                runId,
                runtimeSessionId,
                sessionMode,
                message,
                List.of(),
                MemoryContext.empty(),
                null,
                null,
                Map.of(),
                forwardHeaders
        );
    }

    private AgentRuntimeCancelRequest cancelRequest(String runId) {
        return cancelRequest(runId, "relay-session-1");
    }

    private AgentRuntimeCancelRequest cancelRequest(String runId, String runtimeSessionId) {
        return new AgentRuntimeCancelRequest(
                "tenant1",
                "user1",
                "session1",
                runId,
                runtimeSessionId,
                "relay",
                "USER_STOP",
                Map.of(),
                RuntimeForwardHeaders.empty()
        );
    }

    private static final class ReusableFakeWebSocketClient implements WebSocketClient {
        private final java.util.ArrayList<String> sent = new java.util.ArrayList<>();
        private ReusableFakeWebSocketSession session;
        private URI uri;
        private int executeCount;

        @Override
        public Mono<Void> execute(URI url, WebSocketHandler handler) {
            return execute(url, new HttpHeaders(), handler);
        }

        @Override
        public Mono<Void> execute(URI url, HttpHeaders requestHeaders, WebSocketHandler handler) {
            executeCount++;
            this.uri = url;
            this.session = new ReusableFakeWebSocketSession(sent);
            return handler.handle(session);
        }

        private void emit(String frame) {
            if (session == null) {
                throw new IllegalStateException("WebSocket session has not been opened");
            }
            session.emit(frame);
        }

        private List<String> sent() {
            return sent;
        }

        private int executeCount() {
            return executeCount;
        }

        private URI uri() {
            return uri;
        }
    }

    private static final class ReusableFakeWebSocketSession implements WebSocketSession {
        private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();
        private final Sinks.Many<String> inbound = Sinks.many().replay().all();
        private final java.util.List<String> sent;

        private ReusableFakeWebSocketSession(java.util.List<String> sent) {
            this.sent = sent;
        }

        @Override
        public String getId() {
            return "fake-reusable-session";
        }

        @Override
        public HandshakeInfo getHandshakeInfo() {
            return null;
        }

        @Override
        public DataBufferFactory bufferFactory() {
            return bufferFactory;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return new HashMap<>();
        }

        @Override
        public Flux<WebSocketMessage> receive() {
            return inbound.asFlux().map(this::textMessage);
        }

        @Override
        public Mono<Void> send(Publisher<WebSocketMessage> messages) {
            return Flux.from(messages)
                    .map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(sent::add)
                    .then();
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public Mono<Void> close(CloseStatus status) {
            inbound.tryEmitComplete();
            return Mono.empty();
        }

        @Override
        public Mono<CloseStatus> closeStatus() {
            return Mono.just(CloseStatus.NORMAL);
        }

        @Override
        public WebSocketMessage textMessage(String payload) {
            DataBuffer buffer = bufferFactory.wrap(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new WebSocketMessage(WebSocketMessage.Type.TEXT, buffer);
        }

        @Override
        public WebSocketMessage binaryMessage(java.util.function.Function<DataBufferFactory, DataBuffer> payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.BINARY, payloadFactory.apply(bufferFactory));
        }

        @Override
        public WebSocketMessage pingMessage(java.util.function.Function<DataBufferFactory, DataBuffer> payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.PING, payloadFactory.apply(bufferFactory));
        }

        @Override
        public WebSocketMessage pongMessage(java.util.function.Function<DataBufferFactory, DataBuffer> payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.PONG, payloadFactory.apply(bufferFactory));
        }

        private void emit(String frame) {
            inbound.tryEmitNext(frame);
        }

        private List<String> sent() {
            return sent;
        }
    }

    private static final class FakeWebSocketClient implements WebSocketClient {
        private final Flux<String> inboundFrames;
        private final FakeWebSocketSession session;
        private URI uri;
        private HttpHeaders headers;

        private FakeWebSocketClient(List<String> inboundFrames) {
            this(Flux.fromIterable(inboundFrames));
        }

        private FakeWebSocketClient(Flux<String> inboundFrames) {
            this.inboundFrames = inboundFrames;
            this.session = new FakeWebSocketSession(inboundFrames);
        }

        @Override
        public Mono<Void> execute(URI url, WebSocketHandler handler) {
            return execute(url, new HttpHeaders(), handler);
        }

        @Override
        public Mono<Void> execute(URI url, HttpHeaders requestHeaders, WebSocketHandler handler) {
            this.uri = url;
            this.headers = requestHeaders;
            return handler.handle(session);
        }

        private URI uri() {
            return uri;
        }

        private HttpHeaders headers() {
            return headers;
        }

        private List<String> sent() {
            return session.sent();
        }
    }

    private static final class FakeWebSocketSession implements WebSocketSession {
        private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();
        private final Flux<String> inboundFrames;
        private final java.util.ArrayList<String> sent = new java.util.ArrayList<>();

        private FakeWebSocketSession(Flux<String> inboundFrames) {
            this.inboundFrames = inboundFrames;
        }

        @Override
        public String getId() {
            return "fake-session";
        }

        @Override
        public HandshakeInfo getHandshakeInfo() {
            return null;
        }

        @Override
        public DataBufferFactory bufferFactory() {
            return bufferFactory;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return new HashMap<>();
        }

        @Override
        public Flux<WebSocketMessage> receive() {
            return inboundFrames.map(this::textMessage);
        }

        @Override
        public Mono<Void> send(Publisher<WebSocketMessage> messages) {
            return Flux.from(messages)
                    .map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(sent::add)
                    .then();
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public Mono<Void> close(CloseStatus status) {
            return Mono.empty();
        }

        @Override
        public Mono<CloseStatus> closeStatus() {
            return Mono.just(CloseStatus.NORMAL);
        }

        @Override
        public WebSocketMessage textMessage(String payload) {
            DataBuffer buffer = bufferFactory.wrap(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new WebSocketMessage(WebSocketMessage.Type.TEXT, buffer);
        }

        @Override
        public WebSocketMessage binaryMessage(java.util.function.Function<DataBufferFactory, DataBuffer> payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.BINARY, payloadFactory.apply(bufferFactory));
        }

        @Override
        public WebSocketMessage pingMessage(java.util.function.Function<DataBufferFactory, DataBuffer> payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.PING, payloadFactory.apply(bufferFactory));
        }

        @Override
        public WebSocketMessage pongMessage(java.util.function.Function<DataBufferFactory, DataBuffer> payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.PONG, payloadFactory.apply(bufferFactory));
        }

        private List<String> sent() {
            return sent;
        }
    }
}
