package com.huawei.finance.front.one.infrastructure.agent.runtime.relay;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntime;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.domain.agent.AgentRuntimeProvider;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatResponseMode;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * RelayAgent AgentRuntime provider。
 *
 * <p>第一版默认 mock，配置 enabled=true 后转发到外部完整 Agent 服务。</p>
 */
@Component
@EnableConfigurationProperties(RelayAgentProperties.class)
public class RelayAgentRuntime implements AgentRuntime {
    private final WebClient.Builder webClientBuilder;
    private final RelayAgentProperties properties;

    public RelayAgentRuntime(WebClient.Builder webClientBuilder, RelayAgentProperties properties) {
        this.webClientBuilder = webClientBuilder;
        this.properties = properties;
    }

    @Override
    public AgentRuntimeProvider provider() {
        return AgentRuntimeProvider.RELAY_AGENT;
    }

    @Override
    public boolean supports(AgentRuntimeProvider provider) {
        return provider == AgentRuntimeProvider.RELAY_AGENT;
    }

    @Override
    public Flux<ChatEvent> query(AgentRuntimeRequest request) {
        if (!properties.isEnabled()) {
            return mockResponse(request);
        }
        Flux<String> deltas = webClientBuilder.baseUrl(properties.getBaseUrl())
                .build()
                .post()
                .uri(properties.getStreamPath())
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class);
        if (responseMode(request) == ChatResponseMode.BLOCK) {
            return deltas.collectList()
                    .map(this::joinDeltas)
                    .map(text -> (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), text))
                    .flux()
                    .concatWithValues(MessageCompletedEvent.of(request.runId(), request.sessionId(), "ACTIVE"));
        }
        return deltas
                .map(delta -> (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), delta))
                .concatWithValues(MessageCompletedEvent.of(request.runId(), request.sessionId(), "ACTIVE"));
    }

    private Flux<ChatEvent> mockResponse(AgentRuntimeRequest request) {
        if (responseMode(request) == ChatResponseMode.BLOCK) {
            return Flux.just(
                    (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), "任务已进入 AgentRuntime。当前 provider=relay-agent，处于 mock 模式，可通过 financeex.agent-runtime.providers.relay-agent.enabled=true 接入真实 RelayAgent。"),
                    (ChatEvent) MessageCompletedEvent.of(request.runId(), request.sessionId(), "ACTIVE")
            );
        }
        return Flux.just(
                (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), "任务已进入 AgentRuntime。"),
                (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), "当前 provider=relay-agent，处于 mock 模式。"),
                (ChatEvent) MessageCompletedEvent.of(request.runId(), request.sessionId(), "ACTIVE")
        );
    }

    private String joinDeltas(List<String> deltas) {
        return String.join("", deltas == null ? List.of() : deltas);
    }

    private ChatResponseMode responseMode(AgentRuntimeRequest request) {
        return request.responseMode() == null ? ChatResponseMode.BLOCK : request.responseMode();
    }
}
