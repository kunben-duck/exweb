package com.huawei.finance.front.one.infrastructure.subagent;

import com.huawei.finance.front.one.application.gateway.SubAgentClient;
import com.huawei.finance.front.one.domain.agent.AgentQueryRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatResponseMode;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Component
@EnableConfigurationProperties(SubAgentProperties.class)
public class ConfiguredSubAgentClient implements SubAgentClient {
    private final WebClient.Builder webClientBuilder;
    private final SubAgentProperties properties;

    public ConfiguredSubAgentClient(WebClient.Builder webClientBuilder, SubAgentProperties properties) {
        this.webClientBuilder = webClientBuilder;
        this.properties = properties;
    }

    @Override
    public Flux<ChatEvent> query(AgentQueryRequest request) {
        // SubAgent 以 agentCode 为稳定业务标识，具体 endpoint/protocol 在配置里解析。
        // 这样用例库和意图服务只需要返回 agentCode，不需要知道部署地址或协议细节。
        SubAgentProperties.AgentEndpoint endpoint = properties.getAgents().get(request.agentCode());
        if (endpoint == null || !endpoint.isEnabled() || endpoint.getEndpoint() == null || endpoint.getEndpoint().isBlank()) {
            return mockOrUnavailable(request);
        }
        if (!"http".equalsIgnoreCase(endpoint.getProtocol())) {
            return Flux.just(
                    (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), "SubAgent 协议暂不支持: " + endpoint.getProtocol()),
                    (ChatEvent) MessageCompletedEvent.of(request.runId(), request.sessionId(), "FAILED")
            );
        }
        Flux<String> deltas = webClientBuilder.build()
                .post()
                .uri(endpoint.getEndpoint())
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(properties.getTimeout());
        if (request.responseMode() == ChatResponseMode.STREAM) {
            // HTTP adapter 首版约定下游返回 String delta 流。本服务在边界处统一转成 ChatEvent，
            // 前端仍只理解 message.delta/message.completed，不感知 SubAgent 协议。
            return deltas
                    .map(delta -> (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), delta))
                    .concatWithValues(MessageCompletedEvent.of(request.runId(), request.sessionId(), "COMPLETED"));
        }
        // block 模式下先聚合下游片段，再作为单个 assistant delta 返回，保持前端事件结构一致。
        return deltas.collectList()
                .map(this::join)
                .flatMapMany(text -> Flux.just(
                        (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), text),
                        (ChatEvent) MessageCompletedEvent.of(request.runId(), request.sessionId(), "COMPLETED")
                ));
    }

    private Flux<ChatEvent> mockOrUnavailable(AgentQueryRequest request) {
        // 本地联调默认允许 mock fallback，方便在第三方 SubAgent 尚未部署时验证主控路由。
        // 生产环境可关闭 mockFallbackEnabled，让缺失配置显式失败。
        if (!properties.isMockFallbackEnabled()) {
            return Flux.just(
                    (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), "未找到可用 SubAgent: " + request.agentCode()),
                    (ChatEvent) MessageCompletedEvent.of(request.runId(), request.sessionId(), "FAILED")
            );
        }
        return Flux.just(
                (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), "已路由到 SubAgent: " + request.agentCode() + "。当前为 mock 响应。"),
                (ChatEvent) MessageCompletedEvent.of(request.runId(), request.sessionId(), "COMPLETED")
        );
    }

    private String join(List<String> deltas) {
        return String.join("", deltas == null ? List.of() : deltas);
    }
}
