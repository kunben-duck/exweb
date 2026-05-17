package com.huawei.finance.front.one.infrastructure.subagent;

import com.huawei.finance.front.one.application.integration.agent.SubAgentClient;
import com.huawei.finance.front.one.domain.agent.AgentQueryRequest;
import com.huawei.finance.front.one.domain.agent.SubAgentCancelRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 配置化 HTTP SubAgent adapter。
 *
 * <p>当前正式版只支持单轮 SubAgent 调用。路由层传入稳定的 agentCode，本 adapter 根据配置解析
 * HTTP endpoint，把下游文本流统一转换为 message.delta/message.completed 事件。</p>
 */
@Component
@EnableConfigurationProperties(SubAgentProperties.class)
public class ConfiguredSubAgentClient implements SubAgentClient {
    private static final Logger log = LoggerFactory.getLogger(ConfiguredSubAgentClient.class);

    private final WebClient.Builder webClientBuilder;
    private final SubAgentProperties properties;

    public ConfiguredSubAgentClient(WebClient.Builder webClientBuilder, SubAgentProperties properties) {
        this.webClientBuilder = webClientBuilder;
        this.properties = properties;
    }

    @Override
    public Flux<ChatEvent> query(AgentQueryRequest request) {
        // SubAgent 以 agentCode 为稳定业务标识，具体 HTTP endpoint 在配置里解析。
        // 这样用例库和意图服务只需要返回 agentCode，不需要知道部署地址。
        SubAgentProperties.AgentEndpoint endpoint = endpoint(request.agentCode());
        if (endpoint == null || !endpoint.isEnabled() || endpoint.getEndpoint() == null || endpoint.getEndpoint().isBlank()) {
            return unavailable(request, "未找到可用 SubAgent 配置: " + request.agentCode());
        }
        Flux<String> deltas = webClientBuilder.build()
                .post()
                .uri(endpoint.getEndpoint())
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(properties.getTimeout());
        // HTTP adapter 首版约定下游返回 String delta 流。本服务在边界处统一转成 ChatEvent，
        // 前端只理解 message.delta/message.completed，不感知 SubAgent 私有协议。
        return deltas
                .map(delta -> (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), delta))
                .concatWithValues(MessageCompletedEvent.of(request.runId(), request.sessionId(),
                        completionPayload(request, "COMPLETED", null)))
                .onErrorResume(ex -> unavailable(request, "SubAgent 调用失败: " + ex.getMessage()));
    }

    @Override
    public Mono<Void> cancel(SubAgentCancelRequest request) {
        SubAgentProperties.AgentEndpoint endpoint = endpoint(request.agentCode());
        if (endpoint == null || !endpoint.isEnabled()
                || endpoint.getStopEndpoint() == null || endpoint.getStopEndpoint().isBlank()) {
            return Mono.empty();
        }
        return webClientBuilder.build()
                .post()
                .uri(endpoint.getStopEndpoint())
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(properties.getTimeout())
                .onErrorResume(ex -> {
                    log.warn("SubAgent cancel 失败，agentCode={}，runId={}，原因：{}",
                            request.agentCode(), request.runId(), ex.getMessage());
                    return Mono.empty();
                });
    }

    private Flux<ChatEvent> unavailable(AgentQueryRequest request, String message) {
        return Flux.just(
                (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), message),
                (ChatEvent) MessageCompletedEvent.of(request.runId(), request.sessionId(),
                        completionPayload(request, "FAILED", message))
        );
    }

    private SubAgentProperties.AgentEndpoint endpoint(String agentCode) {
        if (properties.getAgents() == null || agentCode == null) {
            return null;
        }
        SubAgentProperties.AgentEndpoint direct = properties.getAgents().get(agentCode);
        if (direct != null) {
            return direct;
        }
        return properties.getAgents().get(agentCode.replace("_", "-"));
    }

    private Map<String, Object> completionPayload(AgentQueryRequest request, String subAgentStatus, String errorMessage) {
        return Map.of(
                "agentCode", request.agentCode() == null ? "" : request.agentCode(),
                "subAgentStatus", subAgentStatus,
                "executionMode", "single_turn",
                "errorMessage", errorMessage == null ? "" : errorMessage
        );
    }
}
