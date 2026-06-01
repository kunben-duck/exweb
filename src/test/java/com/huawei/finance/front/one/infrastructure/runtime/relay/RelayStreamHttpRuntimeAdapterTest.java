package com.huawei.finance.front.one.infrastructure.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.AgentRuntimeForwardCookieProperties;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RelayStreamHttpRuntimeAdapterTest {

    @Test
    void forwardsCookieHeaderOnlyAsTrustedRelayHttpHeader() throws Exception {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "text/plain")
                            .body("hello")
                            .build());
                });
        RelayAgentProperties properties = new RelayAgentProperties();
        properties.setBaseUrl("http://relay.test");
        properties.setStreamPath("/v1/query");
        AgentRuntimeForwardCookieProperties forwardCookie = new AgentRuntimeForwardCookieProperties();
        RelayStreamHttpRuntimeAdapter adapter = adapter(builder, properties, forwardCookie);
        AgentRuntimeRequest request = request(RuntimeForwardHeaders.fromCookieHeader("sid=abc; theme=dark", 8192));

        StepVerifier.create(adapter.query(request))
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "hello"))
                .assertNext(event -> assertThat(event.type()).isEqualTo("message.completed"))
                .verifyComplete();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers().getFirst(HttpHeaders.COOKIE)).isEqualTo("sid=abc; theme=dark");
        String json = new ObjectMapper().writeValueAsString(
                RelayRuntimeWireRequestMapper.toQueryWireRequest(request));
        assertThat(json)
                .contains("\"query\":\"hello\"")
                .doesNotContain("sid=abc")
                .doesNotContain("forwardHeaders")
                .doesNotContain("cookieHeader")
                .doesNotContain("tenantId")
                .doesNotContain("userId")
                .doesNotContain("memoryContext")
                .doesNotContain("intentDecision")
                .doesNotContain("routeTarget");
    }

    @Test
    void doesNotForwardCookieWhenAdapterIsNotAllowed() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "text/plain")
                            .body("hello")
                            .build());
                });
        RelayAgentProperties properties = new RelayAgentProperties();
        properties.setBaseUrl("http://relay.test");
        AgentRuntimeForwardCookieProperties forwardCookie = new AgentRuntimeForwardCookieProperties();
        forwardCookie.setAllowedAdapters(List.of("relay-websocket"));
        RelayStreamHttpRuntimeAdapter adapter = adapter(builder, properties, forwardCookie);

        StepVerifier.create(adapter.query(request(RuntimeForwardHeaders.fromCookieHeader("sid=abc", 8192))))
                .expectNextCount(2)
                .verifyComplete();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers()).doesNotContainKey(HttpHeaders.COOKIE);
    }

    @Test
    void forwardsCookieHeaderOnRelayStopRequest() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                });
        RelayAgentProperties properties = new RelayAgentProperties();
        properties.setBaseUrl("http://relay.test");
        properties.setStopPath("/v1/runs/{runId}/stop");
        AgentRuntimeForwardCookieProperties forwardCookie = new AgentRuntimeForwardCookieProperties();
        RelayStreamHttpRuntimeAdapter adapter = adapter(builder, properties, forwardCookie);
        AgentRuntimeCancelRequest request = new AgentRuntimeCancelRequest(
                "tenant1",
                "user1",
                "session1",
                "run1",
                "runtimeSession1",
                "relay",
                "USER_STOP",
                Map.of(),
                RuntimeForwardHeaders.fromCookieHeader("sid=abc", 8192)
        );

        StepVerifier.create(adapter.cancel(request)).verifyComplete();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers().getFirst(HttpHeaders.COOKIE)).isEqualTo("sid=abc");
    }

    @Test
    void relayWireRequestKeepsOnlyAllowedMetadata() throws Exception {
        AgentRuntimeRequest request = new AgentRuntimeRequest(
                "tenant1",
                "user1",
                "session1",
                "run1",
                "runtimeSession1",
                "hello",
                List.of(),
                MemoryContext.empty(),
                null,
                null,
                Map.of(
                        "source", "web",
                        "authorization", "Bearer secret",
                        "cookie", "sid=abc",
                        "nested", Map.of("token", "nested-secret", "safe", "yes")
                ),
                RuntimeForwardHeaders.empty()
        );

        String json = new ObjectMapper().writeValueAsString(RelayRuntimeWireRequestMapper.toQueryWireRequest(request));

        assertThat(json)
                .contains("\"source\":\"web\"")
                .doesNotContain("authorization")
                .doesNotContain("Bearer secret")
                .doesNotContain("cookie")
                .doesNotContain("sid=abc")
                .doesNotContain("nested-secret")
                .contains("\"safe\":\"yes\"");
    }

    @Test
    void normalizesJsonAndSseChunksBeforeReturningChatEvents() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                        .body("data: {\"type\":\"message.delta\",\"content\":\"你\"}\n\n"
                                + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
                                + "data: [DONE]\n\n")
                        .build()));
        RelayAgentProperties properties = new RelayAgentProperties();
        properties.setBaseUrl("http://relay.test");
        AgentRuntimeForwardCookieProperties forwardCookie = new AgentRuntimeForwardCookieProperties();
        RelayStreamHttpRuntimeAdapter adapter = adapter(builder, properties, forwardCookie);

        StepVerifier.create(adapter.query(request(RuntimeForwardHeaders.empty())))
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "你"))
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "好"))
                .assertNext(event -> assertThat(event.type()).isEqualTo("message.completed"))
                .verifyComplete();
    }

    @Test
    void unknownJsonFrameFailsInsteadOfLeakingRawJsonToFrontend() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("{\"unexpected\":\"raw\"}")
                        .build()));
        RelayAgentProperties properties = new RelayAgentProperties();
        properties.setBaseUrl("http://relay.test");
        RelayStreamHttpRuntimeAdapter adapter = adapter(builder, properties,
                new AgentRuntimeForwardCookieProperties());

        StepVerifier.create(adapter.query(request(RuntimeForwardHeaders.empty())))
                .expectError(RelayRuntimeProtocolException.class)
                .verify();
    }

    @Test
    void stopsReadingAfterMessageCompletedEvent() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                        .body("data: {\"delta\":\"before\"}\n\n"
                                + "data: [DONE]\n\n"
                                + "data: {\"delta\":\"after\"}\n\n")
                        .build()));
        RelayAgentProperties properties = new RelayAgentProperties();
        properties.setBaseUrl("http://relay.test");
        RelayStreamHttpRuntimeAdapter adapter = adapter(builder, properties,
                new AgentRuntimeForwardCookieProperties());

        StepVerifier.create(adapter.query(request(RuntimeForwardHeaders.empty())))
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "before"))
                .assertNext(event -> assertThat(event.type()).isEqualTo("message.completed"))
                .verifyComplete();
    }

    private AgentRuntimeRequest request(RuntimeForwardHeaders forwardHeaders) {
        return new AgentRuntimeRequest(
                "tenant1",
                "user1",
                "session1",
                "run1",
                "runtimeSession1",
                "hello",
                List.of(),
                MemoryContext.empty(),
                null,
                null,
                Map.of(),
                forwardHeaders
        );
    }

    private RelayStreamHttpRuntimeAdapter adapter(WebClient.Builder builder, RelayAgentProperties properties,
                                                  AgentRuntimeForwardCookieProperties forwardCookie) {
        return new RelayStreamHttpRuntimeAdapter(builder, properties, forwardCookie,
                new RelayRuntimeResponseNormalizer(new ObjectMapper()));
    }
}
