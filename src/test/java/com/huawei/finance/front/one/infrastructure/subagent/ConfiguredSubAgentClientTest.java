package com.huawei.finance.front.one.infrastructure.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.config.IntegrationAuthProperties;
import com.huawei.finance.front.one.application.service.auth.AuthHeaderProviderRegistry;
import com.huawei.finance.front.one.domain.agent.AgentQueryRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.infrastructure.auth.NoopAuthHeaderProvider;
import com.huawei.finance.front.one.infrastructure.auth.SgovAuthHeaderProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class ConfiguredSubAgentClientTest {
    @Test
    void appliesConfiguredOutboundAuthorizationHeader() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "text/plain")
                            .body("hello")
                            .build());
                });
        ConfiguredSubAgentClient client = new ConfiguredSubAgentClient(builder, properties(), authHeaders());

        List<ChatEvent> events = client.query(request()).collectList().block(Duration.ofSeconds(2));

        assertThat(events).hasSize(2);
        assertThat(captured.get().headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer subagent-token");
    }

    private SubAgentProperties properties() {
        SubAgentProperties properties = new SubAgentProperties();
        properties.setTimeout(Duration.ofSeconds(1));
        SubAgentProperties.AgentEndpoint endpoint = new SubAgentProperties.AgentEndpoint();
        endpoint.setEnabled(true);
        endpoint.setEndpoint("http://subagent.test/query");
        properties.setAgents(Map.of("agent1", endpoint));
        return properties;
    }

    private AuthHeaderProviderRegistry authHeaders() {
        IntegrationAuthProperties properties = new IntegrationAuthProperties();
        properties.setEnabled(true);
        return new AuthHeaderProviderRegistry(properties, List.of(
                new NoopAuthHeaderProvider(),
                new SgovAuthHeaderProvider(properties, (request, appId, secret) -> Optional.of("Bearer subagent-token"))
        ));
    }

    private AgentQueryRequest request() {
        return new AgentQueryRequest("tenant1", "user1", "session1", "run1", "agent1",
                "hello", List.of(), MemoryContext.empty(), RouteTarget.subAgent("agent1", "test", 1.0, "hit"),
                Map.of());
    }
}
