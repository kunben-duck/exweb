package com.huawei.finance.front.one.infrastructure.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.LegacySkillProperties;
import com.huawei.finance.front.one.application.integration.agent.LegacySkillAgentRequest;
import com.huawei.finance.front.one.application.integration.agent.LegacySkillCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.domain.auth.UserContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ConfiguredLegacySkillAgentClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void queryForwardsCookieOnlyAsLegacyAgentHttpHeader() throws Exception {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                            .body("message: {\"content\":\"ok\"}\n\nmessage: {\"endFlag\":true}\n\n")
                            .build());
                });
        LegacySkillProperties properties = properties();
        LegacySkillChatRequestMapper mapper = new LegacySkillChatRequestMapper(objectMapper, properties);
        ConfiguredLegacySkillAgentClient client = new ConfiguredLegacySkillAgentClient(
                builder, properties, mapper, new LegacySkillResponseNormalizer(objectMapper));
        LegacySkillAgentRequest request = queryRequest(RuntimeForwardHeaders.fromCookieHeader("sid=abc", 8192));

        StepVerifier.create(client.query(request))
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "ok"))
                .assertNext(event -> assertThat(event.type()).isEqualTo("message.completed"))
                .verifyComplete();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().headers().getFirst(HttpHeaders.COOKIE)).isEqualTo("sid=abc");
        String body = objectMapper.writeValueAsString(mapper.toWireRequest(request));
        assertThat(body)
                .contains("\"skillId\":\"skill-tax\"")
                .contains("\"isThinking\":1")
                .contains("\"qaType\":\"normalQa\"")
                .contains("\"streamFlag\":\"stream\"")
                .doesNotContain("\"isThink\"")
                .doesNotContain("\"queryType\"")
                .doesNotContain("\"steamFlag\"")
                .doesNotContain("sid=abc")
                .doesNotContain("forwardHeaders")
                .doesNotContain("cookieHeader");
    }

    @Test
    void cancelForwardsCookieOnlyAsLegacyAgentHttpHeader() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                });
        LegacySkillProperties properties = properties();
        properties.setStopPath("/api/stop");
        ConfiguredLegacySkillAgentClient client = new ConfiguredLegacySkillAgentClient(
                builder,
                properties,
                new LegacySkillChatRequestMapper(objectMapper, properties),
                new LegacySkillResponseNormalizer(objectMapper));

        StepVerifier.create(client.cancel(new LegacySkillCancelRequest(
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
        assertThat(captured.get().headers().getFirst(HttpHeaders.COOKIE)).isEqualTo("sid=abc");
    }

    private LegacySkillAgentRequest queryRequest(RuntimeForwardHeaders forwardHeaders) {
        return new LegacySkillAgentRequest(
                user(),
                "session1",
                "run1",
                "skill-tax",
                "hello",
                List.of(),
                Map.of(),
                forwardHeaders
        );
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private LegacySkillProperties properties() {
        LegacySkillProperties properties = new LegacySkillProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://legacy.test");
        properties.setChatPath("/api/chat");
        properties.setAllowedSkillIds(List.of("skill-tax"));
        return properties;
    }
}
