package com.huawei.finance.front.one.infrastructure.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.finance.front.one.application.integration.auth.AuthHeaderRequest;
import com.huawei.finance.front.one.application.integration.intent.IntentService;
import com.huawei.finance.front.one.application.service.auth.AuthHeaderProviderRegistry;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 财经 Eureka 意图服务 HTTP 适配器。
 *
 * <p>该类只负责 HTTP 调用和超时降级；下游请求体和响应体的字段转换由专用 mapper 处理。
 * 后续意图服务协议未定或变更时，优先修改 mapper，不把 wire 契约扩散到应用层。</p>
 */
@Component
@EnableConfigurationProperties(IntentServiceHttpProperties.class)
public class FinEurekaIntentService implements IntentService {
    private final WebClient webClient;
    private final IntentServiceHttpProperties properties;
    private final IntentServiceRequestMapper requestMapper;
    private final IntentServiceResponseMapper responseMapper;
    private final AuthHeaderProviderRegistry authHeaders;

    public FinEurekaIntentService(WebClient.Builder webClientBuilder, IntentServiceHttpProperties properties,
                                  IntentServiceRequestMapper requestMapper,
                                  IntentServiceResponseMapper responseMapper,
                                  AuthHeaderProviderRegistry authHeaders) {
        this.webClient = webClientBuilder.baseUrl(properties.getBaseUrl()).build();
        this.properties = properties;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
        this.authHeaders = authHeaders;
    }

    @Override
    public IntentDecision recognize(ChatCommand command, MemoryContext memory, UserContext user) {
        try {
            return webClient.post()
                    .uri(properties.getRecognizePath())
                    .headers(headers -> applyAuthHeaders(headers, user))
                    .bodyValue(requestMapper.toWireRequest(command, memory, user))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .map(responseMapper::toDecision)
                    .timeout(properties.normalizedTimeout())
                    .blockOptional()
                    .orElseGet(() -> responseMapper.degraded("empty intent response"));
        } catch (RuntimeException ex) {
            return responseMapper.degraded("intent service failed: " + ex.getMessage());
        }
    }

    private void applyAuthHeaders(HttpHeaders headers, UserContext user) {
        authHeaders.headers(new AuthHeaderRequest(
                user == null ? null : user.tenantId(),
                user == null ? null : user.userId(),
                "intent-service",
                "recognize",
                properties.getBaseUrl(),
                properties.getRecognizePath(),
                null
        )).forEach(headers::set);
    }
}
