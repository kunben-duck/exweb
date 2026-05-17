package com.huawei.finance.front.one.infrastructure.agent.runtime.relay;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntime;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * RelayAgent Runtime HTTP 适配器。
 *
 * <p>这是当前上线版本默认的 AgentRuntime adapter。它只出现在 infrastructure 层，
 * application 层仍然依赖 AgentRuntime 接口。后续如果替换 Runtime 实现，应新增另一个
 * AgentRuntime adapter，并通过 financeex.agent-runtime.provider 切换装配。</p>
 */
@Component
@EnableConfigurationProperties(RelayAgentProperties.class)
@ConditionalOnProperty(prefix = "financeex.agent-runtime", name = "provider", havingValue = "relay", matchIfMissing = true)
public class RelayAgentRuntime implements AgentRuntime {
    private static final Logger log = LoggerFactory.getLogger(RelayAgentRuntime.class);

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
        // 正式版统一以 delta 流输出，不再按前端响应模式聚合为整块文本。
        return deltas
                .map(delta -> (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), delta))
                .concatWithValues(MessageCompletedEvent.of(request.runId(), request.sessionId()));
    }

    @Override
    public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
        if (properties.getStopPath() == null || properties.getStopPath().isBlank()) {
            return Mono.empty();
        }
        String path = properties.getStopPath().replace("{runId}", request.runId() == null ? "" : request.runId());
        return webClientBuilder.baseUrl(properties.getBaseUrl())
                .build()
                .post()
                .uri(path)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(properties.getTimeout())
                .onErrorResume(ex -> {
                    log.warn("Relay Runtime cancel 失败，runId={}，原因：{}", request.runId(), ex.getMessage());
                    return Mono.empty();
                });
    }
}
