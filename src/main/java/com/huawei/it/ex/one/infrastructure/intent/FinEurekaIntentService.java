package com.huawei.it.ex.one.infrastructure.intent;

import com.huawei.it.ex.one.application.integration.auth.AuthHeaderRequest;
import com.huawei.it.ex.one.application.integration.intent.IntentRecognitionResult;
import com.huawei.it.ex.one.application.integration.intent.IntentRetryContext;
import com.huawei.it.ex.one.application.integration.intent.IntentRetryPolicy;
import com.huawei.it.ex.one.application.integration.intent.IntentService;
import com.huawei.it.ex.one.application.integration.intent.IntentUserPreferenceCorrection;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.memory.MemoryContext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.Exceptions;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * 财经 Eureka 意图服务 HTTP 适配器。
 *
 * <p>该类只负责 HTTP 调用和超时降级；下游请求体和响应体的字段转换由专用 mapper 处理。
 * 后续意图服务协议未定或变更时，优先修改 mapper，不把 wire 契约扩散到应用层。</p>
 */
@Component
@EnableConfigurationProperties(IntentServiceHttpProperties.class)
public class FinEurekaIntentService implements IntentService {
    private static final AppLogger log = AppLoggerFactory.getLogger(FinEurekaIntentService.class);

    private final WebClient webClient;
    private final IntentServiceHttpProperties properties;
    private final IntentServiceWireMapper wireMapper;
    private final AuthHeaderProviderRegistry authHeaders;
    private final IntentRetryPolicy retryPolicy;
    private final IntentPreferenceCorrectionLoader preferenceLoader;

    public FinEurekaIntentService(WebClient.Builder webClientBuilder, IntentServiceHttpProperties properties,
                                  IntentServiceWireMapper wireMapper, AuthHeaderProviderRegistry authHeaders,
                                  IntentRetryPolicy retryPolicy) {
        this(webClientBuilder, properties, wireMapper, authHeaders, retryPolicy, null);
    }

    @Autowired
    public FinEurekaIntentService(WebClient.Builder webClientBuilder, IntentServiceHttpProperties properties,
                                  IntentServiceWireMapper wireMapper, AuthHeaderProviderRegistry authHeaders,
                                  IntentRetryPolicy retryPolicy,
                                  IntentPreferenceCorrectionLoader preferenceLoader) {
        this.webClient = properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
                ? webClientBuilder.build()
                : webClientBuilder.baseUrl(properties.getBaseUrl().trim()).build();
        this.properties = properties;
        this.wireMapper = wireMapper;
        this.authHeaders = authHeaders;
        this.retryPolicy = retryPolicy;
        this.preferenceLoader = preferenceLoader;
    }

    @Override
    public IntentDecision recognize(ChatCommand command, MemoryContext memory, UserContext user) {
        return recognize(command, memory, user, null);
    }

    @Override
    public IntentDecision recognize(ChatCommand command,
                                    MemoryContext memory,
                                    UserContext user,
                                    String userMessageId) {
        IntentRecognitionResult result = recognizeForRouting(command, memory, user, userMessageId);
        return result.decision() == null ? wireMapper.degraded("intent response has no final decision") : result.decision();
    }

    @Override
    public IntentRecognitionResult recognizeForRouting(ChatCommand command, MemoryContext memory, UserContext user) {
        return recognizeForRouting(command, memory, user, null);
    }

    @Override
    public IntentRecognitionResult recognizeForRouting(ChatCommand command,
                                                        MemoryContext memory,
                                                        UserContext user,
                                                        String userMessageId) {
        int maxAttempts = 1 + properties.normalizedMaxRetries();
        List<IntentUserPreferenceCorrection> preferenceCorrections = preferenceLoader == null
                || properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
                ? List.of()
                : preferenceLoader.loadBlocking(command, user);
        IntentRecognitionResult lastResult = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            lastResult = recognizeOnce(command, memory, user, userMessageId, preferenceCorrections);
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
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "IntentDecision retry policy failed; skipping remaining retries")
                    .sessionId(context.command() == null ? null : context.command().sessionId())
                    .operation("intent.retry-policy")
                    .attribute("attempt", context.attempt())
                    .retryable(false)
                    .build(), ex);
            return false;
        }
    }

    private IntentRecognitionResult recognizeOnce(ChatCommand command,
                                                   MemoryContext memory,
                                                   UserContext user,
                                                   String userMessageId,
                                                   List<IntentUserPreferenceCorrection> preferenceCorrections) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            return IntentRecognitionResult.degraded(wireMapper.degraded("intent service base-url is not configured"));
        }
        try {
            IntentRecognitionResult result = webClient.post()
                    .uri(properties.getRecognizePath())
                    .headers(headers -> applyAuthHeaders(headers, user))
                    .bodyValue(wireMapper.toWireRequest(
                            command, memory, user, userMessageId, preferenceCorrections))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .map(wireMapper::toRecognitionResult)
                    .timeout(properties.normalizedTimeout())
                    .blockOptional()
                    .orElse(null);
            if (result == null) {
                log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTENT_DECISION_EMPTY_RESPONSE,
                                "IntentDecision returned an empty response")
                        .sessionId(command == null ? null : command.sessionId())
                        .operation("intent.recognize")
                        .build());
                return IntentRecognitionResult.degraded(wireMapper.degraded("empty intent response"));
            }
            if (result.status() == IntentRecognitionResult.Status.FAILED_OR_DEGRADED) {
                log.warn(SystemErrorLogEntry.builder(classifyIntentResult(result),
                                "IntentDecision returned a non-executable result")
                        .sessionId(command == null ? null : command.sessionId())
                        .operation("intent.recognize")
                        .build());
            }
            return result;
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(classifyIntentFailure(ex),
                            "IntentDecision request failed; returning a degraded result")
                    .sessionId(command == null ? null : command.sessionId())
                    .operation("intent.recognize")
                    .build(), ex);
            return IntentRecognitionResult.degraded(wireMapper.degraded("intent service failed: " + ex.getMessage()));
        }
    }

    private SystemErrorCode classifyIntentFailure(RuntimeException failure) {
        Throwable cause = Exceptions.unwrap(failure);
        if (cause instanceof TimeoutException) {
            return SystemErrorCode.INTENT_DECISION_TIMEOUT;
        }
        if (cause instanceof WebClientResponseException response) {
            if (response.getStatusCode().value() == 429) {
                return SystemErrorCode.INTENT_DECISION_RATE_LIMITED;
            }
            return response.getStatusCode().is5xxServerError()
                    ? SystemErrorCode.INTENT_DECISION_HTTP_SERVER_ERROR
                    : SystemErrorCode.INTENT_DECISION_HTTP_CLIENT_ERROR;
        }
        if (cause instanceof WebClientRequestException) {
            return SystemErrorCode.INTENT_DECISION_UNAVAILABLE;
        }
        if (hasCause(cause, JsonProcessingException.class) || hasCause(cause, DecodingException.class)) {
            return SystemErrorCode.INTENT_DECISION_RESPONSE_PARSE_FAILED;
        }
        return SystemErrorCode.INTENT_DECISION_ERROR;
    }

    private SystemErrorCode classifyIntentResult(IntentRecognitionResult result) {
        if (result == null || result.decision() == null) {
            return SystemErrorCode.INTENT_DECISION_PROTOCOL_INVALID;
        }
        String reason = result.decision().raw() == null
                ? ""
                : String.valueOf(result.decision().raw().getOrDefault("reason", ""));
        if ("意图服务协议异常".equals(result.decision().intentName())
                || reason.contains("routeAction") || reason.contains("ROUTE_SINGLE")) {
            return SystemErrorCode.INTENT_DECISION_PROTOCOL_INVALID;
        }
        if (reason.contains("empty intent response")) {
            return SystemErrorCode.INTENT_DECISION_EMPTY_RESPONSE;
        }
        return SystemErrorCode.INTENT_DECISION_STATUS_FAILED;
    }

    private boolean hasCause(Throwable failure, Class<? extends Throwable> expectedType) {
        Throwable current = failure;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
