package com.huawei.finance.front.one.infrastructure.agent.runtime.relay;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntime;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
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
 * RelayAgent Runtime HTTP 适配器。
 *
 * <p>当前正式版本只保留 Relay Runtime。更换 RelayAgent 部署地址时，只需要调整
 * financeex.agent-runtime.base-url 和 stream-path。</p>
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
    public Flux<ChatEvent> query(AgentRuntimeRequest request) {
        Flux<String> deltas = webClientBuilder.baseUrl(properties.getBaseUrl())
                .build()
                .post()
                .uri(properties.getStreamPath())
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(properties.getTimeout());
        if (responseMode(request) == ChatResponseMode.BLOCK) {
            return deltas.collectList()
                    .map(this::joinDeltas)
                    .map(text -> (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), text))
                    .flux()
                    .concatWithValues(MessageCompletedEvent.of(request.runId(), request.sessionId()));
        }
        return deltas
                .map(delta -> (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), delta))
                .concatWithValues(MessageCompletedEvent.of(request.runId(), request.sessionId()));
    }

    private String joinDeltas(List<String> deltas) {
        return String.join("", deltas == null ? List.of() : deltas);
    }

    private ChatResponseMode responseMode(AgentRuntimeRequest request) {
        return request.responseMode() == null ? ChatResponseMode.BLOCK : request.responseMode();
    }
}
