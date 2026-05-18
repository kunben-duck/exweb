package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * DeepSeek/OpenAI-compatible Chat Completions adapter。
 *
 * <p>该 adapter 用于真实 Relay streamable-http 服务尚未就绪时的模拟接入。它把
 * {@link AgentRuntimeRequest} 转成 DeepSeek/OpenAI-compatible {@code /chat/completions} 请求，
 * 再把完整 JSON 或 SSE 增量响应转换成标准 ChatEvent。密钥只从配置或密钥系统注入，不能写入代码。</p>
 */
@Component
@EnableConfigurationProperties(RelayAgentProperties.class)
@ConditionalOnExpression("'${financeex.agent-runtime.provider:relay}' == 'relay'")
public class DeepSeekChatCompletionsRuntimeAdapter implements RelayRuntimeProtocolAdapter {
    private final WebClient.Builder webClientBuilder;
    private final RelayAgentProperties properties;
    private final OpenAiChatCompletionRelayCodec codec;

    public DeepSeekChatCompletionsRuntimeAdapter(WebClient.Builder webClientBuilder, RelayAgentProperties properties,
                                                 OpenAiChatCompletionRelayCodec codec) {
        this.webClientBuilder = webClientBuilder;
        this.properties = properties;
        this.codec = codec;
    }

    @Override
    public Set<String> adapterNames() {
        return Set.of("deepseek-chat-completions", "openai-chat-completions");
    }

    @Override
    public Flux<ChatEvent> query(AgentRuntimeRequest request) {
        WebClient.RequestBodySpec spec = webClientBuilder.baseUrl(properties.getBaseUrl())
                .build()
                .post()
                .uri(properties.getStreamPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(properties.isStream() ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON);
        applyAuthorization(spec);
        Object body = codec.buildRequestBody(request, properties);
        if (properties.isStream()) {
            return codec.decodeStreamingResponse(
                            request.runId(),
                            request.sessionId(),
                            spec.bodyValue(body).retrieve().bodyToFlux(String.class).timeout(properties.getTimeout())
                    )
                    .concatWithValues(MessageCompletedEvent.of(request.runId(), request.sessionId()));
        }
        return spec.bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.getTimeout())
                .flatMapMany(response -> Flux.fromIterable(codec.decodeBlockingResponse(
                        request.runId(), request.sessionId(), response)))
                .concatWithValues(MessageCompletedEvent.of(request.runId(), request.sessionId()));
    }

    @Override
    public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
        return Mono.empty();
    }

    private void applyAuthorization(WebClient.RequestHeadersSpec<?> spec) {
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            spec.headers(headers -> headers.setBearerAuth(properties.getApiKey().trim()));
        }
    }
}
