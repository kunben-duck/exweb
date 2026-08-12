package com.huawei.it.ex.one.infrastructure.runtime.domainagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.domain.auth.UserContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class ConfiguredDomainAgentClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void queryUsesTrimmedConfiguredRefererAndForwardsCookieAsHttpHeaders() throws Exception {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                            .body("message: {\"content\":\"ok\"}\n\nmessage: {\"endFlag\":true}\n\n")
                            .build());
                });
        DomainAgentProperties properties = properties();
        properties.setReferer("  https://portal.example.com/domain-agent  ");
        DomainAgentChatRequestMapper mapper = new DomainAgentChatRequestMapper(properties);
        ConfiguredDomainAgentClient client = new ConfiguredDomainAgentClient(
                builder, properties, mapper, new DomainAgentResponseNormalizer(objectMapper));
        DomainAgentRequest request = queryRequest(RuntimeForwardHeaders.fromCookieHeader("sid=abc", 8192));

        StepVerifier.create(client.query(request))
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "ok"))
                .assertNext(event -> assertThat(event.type()).isEqualTo("message.completed"))
                .verifyComplete();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers().getFirst(HttpHeaders.REFERER))
                .isEqualTo("https://portal.example.com/domain-agent");
        assertThat(captured.get().headers().getFirst(HttpHeaders.COOKIE)).isEqualTo("sid=abc");
        String body = objectMapper.writeValueAsString(mapper.toWireRequest(request));
        assertThat(body)
                .contains("\"skillId\":\"skill-unlisted\"")
                .contains("\"query\":\"hello\"")
                .doesNotContain("\"isThinking\"")
                .doesNotContain("\"qaType\"")
                .doesNotContain("\"streamFlag\"")
                .doesNotContain("\"isThink\"")
                .doesNotContain("\"queryType\"")
                .doesNotContain("\"steamFlag\"")
                .doesNotContain("sid=abc")
                .doesNotContain("https://portal.example.com/domain-agent")
                .doesNotContain("forwardHeaders")
                .doesNotContain("cookieHeader");
    }

    @Test
    void cancelUsesTrimmedConfiguredRefererAndForwardsCookieAsHttpHeaders() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                });
        DomainAgentProperties properties = properties();
        properties.setStopPath("/api/stop");
        properties.setReferer("  https://portal.example.com/domain-agent  ");
        ConfiguredDomainAgentClient client = new ConfiguredDomainAgentClient(
                builder,
                properties,
                new DomainAgentChatRequestMapper(properties),
                new DomainAgentResponseNormalizer(objectMapper));

        StepVerifier.create(client.cancel(new DomainAgentCancelRequest(
                        user(),
                        "session1",
                        "run1",
                        "skill-tax",
                        "USER_STOP",
                        Map.of(),
                        RuntimeForwardHeaders.fromCookieHeader("sid=abc", 8192)
                )))
                .verifyComplete();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers().getFirst(HttpHeaders.REFERER))
                .isEqualTo("https://portal.example.com/domain-agent");
        assertThat(captured.get().headers().getFirst(HttpHeaders.COOKIE)).isEqualTo("sid=abc");
    }

    @Test
    void queryFallsBackToBaseUrlRefererWhenRefererIsBlank() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                            .body("message: {\"endFlag\":true}\n\n")
                            .build());
                });
        DomainAgentProperties properties = properties();
        properties.setBaseUrl("  http://domain.test  ");
        properties.setReferer("  ");
        ConfiguredDomainAgentClient client = new ConfiguredDomainAgentClient(
                builder,
                properties,
                new DomainAgentChatRequestMapper(properties),
                new DomainAgentResponseNormalizer(objectMapper));

        StepVerifier.create(client.query(queryRequest(RuntimeForwardHeaders.empty())))
                .assertNext(event -> assertThat(event.type()).isEqualTo("message.completed"))
                .verifyComplete();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers().getFirst(HttpHeaders.REFERER)).isEqualTo("http://domain.test");
    }

    @Test
    void cancelFallsBackToBaseUrlRefererWhenRefererIsBlank() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                });
        DomainAgentProperties properties = properties();
        properties.setBaseUrl("  http://domain.test  ");
        properties.setReferer(null);
        properties.setStopPath("/api/stop");
        ConfiguredDomainAgentClient client = new ConfiguredDomainAgentClient(
                builder,
                properties,
                new DomainAgentChatRequestMapper(properties),
                new DomainAgentResponseNormalizer(objectMapper));

        StepVerifier.create(client.cancel(new DomainAgentCancelRequest(
                        user(),
                        "session1",
                        "run1",
                        "skill-tax",
                        "USER_STOP",
                        Map.of(),
                        RuntimeForwardHeaders.empty()
                )))
                .verifyComplete();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers().getFirst(HttpHeaders.REFERER)).isEqualTo("http://domain.test");
    }

    @Test
    void queryStopsConsumingAfterDomainAgentMessageCompleted() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .body("""
                                message: {"content":"ok"}

                                message: {"endFlag":true}

                                message: {"content":"late"}

                                """)
                        .build()));
        DomainAgentProperties properties = properties();
        ConfiguredDomainAgentClient client = new ConfiguredDomainAgentClient(
                builder,
                properties,
                new DomainAgentChatRequestMapper(properties),
                new DomainAgentResponseNormalizer(objectMapper));

        StepVerifier.create(client.query(queryRequest(RuntimeForwardHeaders.empty())))
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "ok"))
                .assertNext(event -> assertThat(event.type()).isEqualTo("message.completed"))
                .verifyComplete();
    }

    @Test
    void queryPreservesUtf8CharacterSplitAcrossRawDataBuffers() {
        String response = "message: {\"content\":\"分析结果\"}\n\nmessage: {\"endFlag\":true}\n\n";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        int split = "message: {\"content\":\"".getBytes(StandardCharsets.UTF_8).length + 1;
        DefaultDataBufferFactory buffers = new DefaultDataBufferFactory();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .body(Flux.just(
                                buffers.wrap(Arrays.copyOfRange(bytes, 0, split)),
                                buffers.wrap(Arrays.copyOfRange(bytes, split, bytes.length))))
                        .build()));
        DomainAgentProperties properties = properties();
        ConfiguredDomainAgentClient client = new ConfiguredDomainAgentClient(
                builder,
                properties,
                new DomainAgentChatRequestMapper(properties),
                new DomainAgentResponseNormalizer(objectMapper, properties));

        StepVerifier.create(client.query(queryRequest(RuntimeForwardHeaders.empty())))
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "分析结果"))
                .assertNext(event -> assertThat(event.type()).isEqualTo("message.completed"))
                .verifyComplete();
    }

    @Test
    void queryFailsWithoutPublishingPartialEventWhenFrameExceedsLimit() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .body("message: {\"processResult\":{\"fixedResponse\":\""
                                + "x".repeat(200) + "\"}}\n\n")
                        .build()));
        DomainAgentProperties properties = properties();
        properties.setMaxPendingFrameBytes(128);
        ConfiguredDomainAgentClient client = new ConfiguredDomainAgentClient(
                builder,
                properties,
                new DomainAgentChatRequestMapper(properties),
                new DomainAgentResponseNormalizer(objectMapper, properties));

        StepVerifier.create(client.query(queryRequest(RuntimeForwardHeaders.empty())))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(DomainAgentProtocolException.class)
                        .hasMessageContaining("DOMAIN_AGENT_FRAME_TOO_LARGE"))
                .verify();
    }

    @Test
    void queryAppliesIdleTimeoutBeforeFirstRawChunk() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .body(Flux.never())
                        .build()));
        DomainAgentProperties properties = properties();
        properties.setStreamIdleTimeout(Duration.ofSeconds(2));
        properties.setStreamTotalTimeout(Duration.ofSeconds(10));
        ConfiguredDomainAgentClient client = new ConfiguredDomainAgentClient(
                builder,
                properties,
                new DomainAgentChatRequestMapper(properties),
                new DomainAgentResponseNormalizer(objectMapper, properties));

        StepVerifier.withVirtualTime(() -> client.query(queryRequest(RuntimeForwardHeaders.empty())))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(2))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(TimeoutException.class)
                        .hasMessageContaining("stream IDLE timeout"))
                .verify();
    }

    @Test
    void queryAppliesIdleTimeoutBetweenRawChunks() {
        DefaultDataBufferFactory buffers = new DefaultDataBufferFactory();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .body(Flux.concat(
                                Flux.just(buffers.wrap(
                                        "message: {\"content\":\"first\"}\n\n".getBytes(StandardCharsets.UTF_8))),
                                Mono.delay(Duration.ofSeconds(3)).map(ignored -> buffers.wrap(
                                        "message: {\"endFlag\":true}\n\n".getBytes(StandardCharsets.UTF_8)))))
                        .build()));
        DomainAgentProperties properties = properties();
        properties.setStreamIdleTimeout(Duration.ofSeconds(2));
        properties.setStreamTotalTimeout(Duration.ofSeconds(10));
        ConfiguredDomainAgentClient client = new ConfiguredDomainAgentClient(
                builder,
                properties,
                new DomainAgentChatRequestMapper(properties),
                new DomainAgentResponseNormalizer(objectMapper, properties));

        StepVerifier.withVirtualTime(() -> client.query(queryRequest(RuntimeForwardHeaders.empty())))
                .expectSubscription()
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "first"))
                .thenAwait(Duration.ofSeconds(2))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(TimeoutException.class)
                        .hasMessageContaining("stream IDLE timeout"))
                .verify();
    }

    @Test
    void queryAppliesAbsoluteTotalTimeoutWhileChunksRemainActive() {
        DefaultDataBufferFactory buffers = new DefaultDataBufferFactory();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                        .body(Flux.interval(Duration.ZERO, Duration.ofSeconds(1))
                                .map(index -> buffers.wrap(("message: {\"content\":\"" + index
                                        + "\"}\n\n").getBytes(StandardCharsets.UTF_8))))
                        .build()));
        DomainAgentProperties properties = properties();
        properties.setStreamIdleTimeout(Duration.ofSeconds(2));
        properties.setStreamTotalTimeout(Duration.ofMillis(3500));
        ConfiguredDomainAgentClient client = new ConfiguredDomainAgentClient(
                builder,
                properties,
                new DomainAgentChatRequestMapper(properties),
                new DomainAgentResponseNormalizer(objectMapper, properties));

        StepVerifier.withVirtualTime(() -> client.query(queryRequest(RuntimeForwardHeaders.empty())))
                .expectSubscription()
                .expectNextCount(1)
                .thenAwait(Duration.ofSeconds(1))
                .expectNextCount(1)
                .thenAwait(Duration.ofSeconds(1))
                .expectNextCount(1)
                .thenAwait(Duration.ofSeconds(1))
                .expectNextCount(1)
                .thenAwait(Duration.ofMillis(500))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(TimeoutException.class)
                        .hasMessageContaining("stream TOTAL timeout"))
                .verify();
    }

    @Test
    void cancelContinuesToUseLegacyTimeout() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.<ClientResponse>never()
                        .doOnCancel(() -> cancelled.set(true)));
        DomainAgentProperties properties = properties();
        properties.setStopPath("/api/stop");
        properties.setTimeout(Duration.ofSeconds(2));
        properties.setStreamIdleTimeout(Duration.ofMillis(10));
        properties.setStreamTotalTimeout(Duration.ofMillis(10));
        ConfiguredDomainAgentClient client = new ConfiguredDomainAgentClient(
                builder,
                properties,
                new DomainAgentChatRequestMapper(properties),
                new DomainAgentResponseNormalizer(objectMapper, properties));

        StepVerifier.withVirtualTime(() -> client.cancel(new DomainAgentCancelRequest(
                        user(),
                        "session1",
                        "run1",
                        "skill-tax",
                        "USER_STOP",
                        Map.of(),
                        RuntimeForwardHeaders.empty())))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(1))
                .thenAwait(Duration.ofSeconds(1))
                .verifyComplete();

        assertThat(cancelled).isTrue();
    }

    private DomainAgentRequest queryRequest(RuntimeForwardHeaders forwardHeaders) {
        return new DomainAgentRequest(
                user(),
                "session1",
                "run1",
                "skill-unlisted",
                "session1",
                "hello",
                List.of(),
                Map.of("skillId", "skill-tax", "query", "hello"),
                forwardHeaders
        );
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private DomainAgentProperties properties() {
        DomainAgentProperties properties = new DomainAgentProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://domain.test");
        properties.setChatPath("/api/chat");
        return properties;
    }
}
