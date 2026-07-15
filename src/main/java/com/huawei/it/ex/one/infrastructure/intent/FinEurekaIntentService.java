package com.huawei.it.ex.one.infrastructure.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.it.ex.one.application.integration.auth.AuthHeaderRequest;
import com.huawei.it.ex.one.application.integration.intent.IntentRecognitionResult;
import com.huawei.it.ex.one.application.integration.intent.IntentService;
import com.huawei.it.ex.one.application.integration.intent.IntentRetryContext;
import com.huawei.it.ex.one.application.integration.intent.IntentRetryPolicy;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(FinEurekaIntentService.class);

    private final WebClient webClient;
    private final IntentServiceHttpProperties properties;
    private final IntentServiceWireMapper wireMapper;
    private final AuthHeaderProviderRegistry authHeaders;
    private final IntentRetryPolicy retryPolicy;

    public FinEurekaIntentService(WebClient.Builder webClientBuilder, IntentServiceHttpProperties properties,
                                  IntentServiceWireMapper wireMapper, AuthHeaderProviderRegistry authHeaders,
                                  IntentRetryPolicy retryPolicy) {
        this.webClient = properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
                ? webClientBuilder.build()
                : webClientBuilder.baseUrl(properties.getBaseUrl().trim()).build();
        this.properties = properties;
        this.wireMapper = wireMapper;
        this.authHeaders = authHeaders;
        this.retryPolicy = retryPolicy;
    }

    @Override
    public IntentDecision recognize(ChatCommand command, MemoryContext memory, UserContext user) {
        IntentRecognitionResult result = recognizeForRouting(command, memory, user);
        return result.decision() == null ? wireMapper.degraded("intent response has no final decision") : result.decision();
    }

    @Override
    public IntentRecognitionResult recognizeForRouting(ChatCommand command, MemoryContext memory, UserContext user) {
        int maxAttempts = 1 + properties.normalizedMaxRetries();
        IntentRecognitionResult lastResult = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            lastResult = recognizeOnce(command, memory, user);
            if (lastResult.waitingClarification()) {
                return lastResult;
            }
            IntentDecision decision = lastResult.decision();
            IntentRetryContext retryContext = new IntentRetryContext(command, memory, user, decision,
                    attempt, maxAttempts);
            if (!shouldRetry(retryContext)) {
                return lastResult;
            }
        }
        return lastResult == null ? IntentRecognitionResult.degraded(wireMapper.degraded("empty intent response")) : lastResult;
    }

    private boolean shouldRetry(IntentRetryContext context) {
        try {
            return retryPolicy.shouldRetry(context);
        } catch (RuntimeException ex) {
            // Retry policy is an enterprise-replaceable extension point. A strategy bug should not fail
            // the chat run; return the current decision and let normal routing degrade if needed.
            log.warn("Intent retry policy failed; skip remaining retries. sessionId={}, attempt={}, reason={}",
                    context.command() == null ? null : context.command().sessionId(),
                    context.attempt(), ex.getMessage());
            return false;
        }
    }

    private IntentRecognitionResult recognizeOnce(ChatCommand command, MemoryContext memory, UserContext user) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            return IntentRecognitionResult.degraded(wireMapper.degraded("intent service base-url is not configured"));
        }
        try {
            return webClient.post()
                    .uri(properties.getRecognizePath())
                    .headers(headers -> applyAuthHeaders(headers, user))
                    .bodyValue(wireMapper.toWireRequest(command, memory, user))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .map(wireMapper::toRecognitionResult)
                    .timeout(properties.normalizedTimeout())
                    .blockOptional()
                    .orElseGet(() -> IntentRecognitionResult.degraded(wireMapper.degraded("empty intent response")));
        } catch (RuntimeException ex) {
            return IntentRecognitionResult.degraded(wireMapper.degraded("intent service failed: " + ex.getMessage()));
        }
    }

    private void applyAuthHeaders(HttpHeaders headers, UserContext user) {
        authHeaders.headers(new AuthHeaderRequest(
                user == null ? null : user.tenantId(),
                user == null ? null : user.ownerUserId(),
                "intent-service",
                "recognize",
                properties.getBaseUrl(),
                properties.getRecognizePath(),
                null
        )).forEach(headers::set);
    }
}
