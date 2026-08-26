package com.huawei.it.ex.one.infrastructure.intent;

import com.huawei.it.ex.one.application.config.IntentCandidateProperties;
import com.huawei.it.ex.one.application.integration.auth.AuthHeaderRequest;
import com.huawei.it.ex.one.application.integration.intent.IntentCandidate;
import com.huawei.it.ex.one.application.integration.intent.IntentCandidateProvider;
import com.huawei.it.ex.one.application.integration.intent.IntentCandidateQueryException;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/** HTTP adapter for querying Intent candidates by a trusted ChatService message ID. */
@Component
@EnableConfigurationProperties(IntentServiceHttpProperties.class)
public class FinEurekaIntentCandidateProvider implements IntentCandidateProvider {
    private static final AppLogger log = AppLoggerFactory.getLogger(FinEurekaIntentCandidateProvider.class);

    private final WebClient webClient;
    private final IntentServiceHttpProperties properties;
    private final IntentCandidateProperties candidateProperties;
    private final AuthHeaderProviderRegistry authHeaders;
    private final Scheduler authIoScheduler;

    public FinEurekaIntentCandidateProvider(WebClient.Builder webClientBuilder,
                                            IntentServiceHttpProperties properties,
                                            IntentCandidateProperties candidateProperties,
                                            AuthHeaderProviderRegistry authHeaders,
                                            @Qualifier("intentCandidateAuthScheduler") Scheduler authIoScheduler) {
        this.webClient = properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
                ? webClientBuilder.build()
                : webClientBuilder.baseUrl(properties.getBaseUrl().trim()).build();
        this.properties = properties;
        this.candidateProperties = candidateProperties;
        this.authHeaders = authHeaders;
        this.authIoScheduler = authIoScheduler;
    }

    @Override
    public Mono<List<IntentCandidate>> findCandidates(UserContext user, String messageId) {
        return Mono.defer(() -> {
            validateConfiguration();
            int maxAttempts = 1 + properties.normalizedMaxRetries();
            DurationBudget firstAttemptBudget = DurationBudget.start(properties.normalizedTimeout());
            return resolveAuthHeaders(user)
                    .doOnError(failure -> logPreparationFailure(failure, maxAttempts))
                    .flatMap(headers -> attempt(
                            messageId,
                            headers,
                            1,
                            maxAttempts,
                            firstAttemptBudget.remaining()));
        });
    }

    private Mono<List<IntentCandidate>> attempt(String messageId,
                                                Map<String, String> headers,
                                                int attempt,
                                                int maxAttempts,
                                                java.time.Duration attemptTimeout) {
        Mono<List<IntentCandidate>> request = attemptTimeout.isZero()
                ? Mono.error(IntentCandidateQueryException.timeout(
                        new TimeoutException("Intent candidate first attempt budget exhausted")))
                : requestOnce(messageId, headers, attemptTimeout);
        return request
                .onErrorResume(IntentCandidateQueryException.class, failure -> {
                    boolean retry = failure.retryable() && attempt < maxAttempts;
                    logAttemptFailure(failure, attempt, maxAttempts, retry);
                    return retry
                            ? Mono.delay(retryDelay(attempt))
                                    .then(attempt(
                                            messageId,
                                            headers,
                                            attempt + 1,
                                            maxAttempts,
                                            properties.normalizedTimeout()))
                            : Mono.error(failure);
                });
    }

    private Mono<List<IntentCandidate>> requestOnce(String messageId,
                                                    Map<String, String> headers,
                                                    java.time.Duration attemptTimeout) {
        return Mono.defer(() -> webClient.post()
                        .uri(properties.getConfidencePath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .headers(requestHeaders -> headers.forEach(requestHeaders::set))
                        .bodyValue(new IntentConfidenceRequest(messageId))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .switchIfEmpty(Mono.error(IntentCandidateQueryException.upstream(
                                "Intent候选技能服务返回空响应")))
                        .map(this::parseCandidates))
                .timeout(attemptTimeout)
                .onErrorMap(failure -> !(failure instanceof IntentCandidateQueryException),
                        this::mapHttpFailure);
    }

    private void validateConfiguration() {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw IntentCandidateQueryException.upstream("Intent候选技能服务地址未配置");
        }
        if (properties.getConfidencePath() == null || properties.getConfidencePath().isBlank()) {
            throw IntentCandidateQueryException.upstream("Intent候选技能服务路径未配置");
        }
    }

    private Mono<Map<String, String>> resolveAuthHeaders(UserContext user) {
        return Mono.fromCallable(() -> authHeaders.headers(authHeaderRequest(user)))
                .map(headers -> headers == null ? Map.<String, String>of() : Map.copyOf(headers))
                .defaultIfEmpty(Map.of())
                .subscribeOn(authIoScheduler)
                .timeout(properties.normalizedTimeout())
                .onErrorMap(failure -> !(failure instanceof IntentCandidateQueryException),
                        this::mapAuthFailure);
    }

    private List<IntentCandidate> parseCandidates(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw IntentCandidateQueryException.upstream("Intent候选技能响应格式错误");
        }
        JsonNode code = root.get("code");
        if (code != null && !code.isNull() && (!code.canConvertToInt() || code.intValue() != 200)) {
            throw IntentCandidateQueryException.upstream("Intent候选技能服务返回失败状态");
        }
        JsonNode status = root.get("status");
        if (status != null && !status.isNull()
                && (!status.isTextual() || !"success".equalsIgnoreCase(status.textValue()))) {
            throw IntentCandidateQueryException.upstream("Intent候选技能服务返回失败状态");
        }
        JsonNode candidates = root.path("data").path("confidence").path("topCandidates");
        if (!candidates.isArray()) {
            throw IntentCandidateQueryException.upstream("Intent候选技能响应缺少topCandidates");
        }
        List<IntentCandidate> result = new ArrayList<>(candidates.size());
        for (JsonNode candidate : candidates) {
            if (!candidate.isObject()) {
                throw IntentCandidateQueryException.upstream("Intent候选技能响应包含非法候选项");
            }
            String accessName = nullableText(candidate, "accessName");
            result.add(new IntentCandidate(
                    nullableText(candidate, "intentId"),
                    accessName,
                    IntentAccessNameNormalizer.normalize(
                            accessName, properties.getResponseAccessNamePrefix()),
                    nullableText(candidate, "intentName"),
                    nullableNumber(candidate, "confidence")));
        }
        return List.copyOf(result);
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw IntentCandidateQueryException.upstream(
                    "Intent候选技能响应字段类型错误: " + field);
        }
        return value.textValue();
    }

    private Double nullableNumber(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw IntentCandidateQueryException.upstream(
                    "Intent候选技能响应字段类型错误: " + field);
        }
        return value.doubleValue();
    }

    private AuthHeaderRequest authHeaderRequest(UserContext user) {
        return new AuthHeaderRequest(
                user == null ? null : user.tenantId(),
                user == null ? null : user.ownerUserId(),
                "intent-service",
                "confidence",
                properties.getBaseUrl(),
                properties.getConfidencePath(),
                null);
    }

    private IntentCandidateQueryException mapHttpFailure(Throwable failure) {
        Throwable cause = Exceptions.unwrap(failure);
        if (cause instanceof TimeoutException) {
            return IntentCandidateQueryException.timeout(cause);
        }
        if (cause instanceof WebClientResponseException response) {
            int status = response.getStatusCode().value();
            if (status == 408 || response.getStatusCode().is5xxServerError()) {
                return IntentCandidateQueryException.retryableUpstream(
                        "Intent候选技能服务暂时不可用", cause);
            }
            return IntentCandidateQueryException.upstream(
                    "Intent候选技能服务拒绝请求", cause);
        }
        if (cause instanceof WebClientRequestException) {
            return IntentCandidateQueryException.retryableUpstream(
                    "Intent候选技能服务网络异常", cause);
        }
        return IntentCandidateQueryException.upstream("Intent候选技能服务调用失败", cause);
    }

    private IntentCandidateQueryException mapAuthFailure(Throwable failure) {
        Throwable cause = Exceptions.unwrap(failure);
        if (hasCause(cause, RejectedExecutionException.class)) {
            return IntentCandidateQueryException.busy();
        }
        return IntentCandidateQueryException.upstream("Intent候选技能鉴权失败", cause);
    }

    private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private java.time.Duration retryDelay(int failedAttempt) {
        long minNanos = safeNanos(candidateProperties.getRetryMinBackoff());
        long maxNanos = safeNanos(candidateProperties.getRetryMaxBackoff());
        long baseNanos = minNanos;
        for (int index = 1; index < failedAttempt && baseNanos < maxNanos; index++) {
            baseNanos = baseNanos > maxNanos / 2 ? maxNanos : baseNanos * 2;
        }
        double jitterFactor = ThreadLocalRandom.current().nextDouble(0.5d, 1.5d);
        long delayedNanos = Math.max(1L, Math.min(maxNanos, Math.round(baseNanos * jitterFactor)));
        return java.time.Duration.ofNanos(delayedNanos);
    }

    private long safeNanos(java.time.Duration value) {
        try {
            return value.toNanos();
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private void logPreparationFailure(Throwable failure, int maxAttempts) {
        IntentCandidateQueryException candidateFailure = failure instanceof IntentCandidateQueryException value
                ? value
                : IntentCandidateQueryException.upstream("Intent候选技能查询准备失败", failure);
        logAttemptFailure(candidateFailure, 1, maxAttempts, false);
    }

    private void logAttemptFailure(IntentCandidateQueryException failure,
                                   int attempt,
                                   int maxAttempts,
                                   boolean retry) {
        log.warn(SystemErrorLogEntry.builder(classifyFailure(failure),
                        "Intent candidate query attempt failed")
                .operation("intent.candidates")
                .attribute("attempt", attempt)
                .attribute("maxAttempts", maxAttempts)
                .attribute("willRetry", retry)
                .build());
    }

    private SystemErrorCode classifyFailure(IntentCandidateQueryException failure) {
        if (failure.isBusy()) {
            return SystemErrorCode.TASK_REJECTED;
        }
        if (failure.timeout()) {
            return SystemErrorCode.INTENT_DECISION_TIMEOUT;
        }
        Throwable cause = failure.getCause();
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
        return SystemErrorCode.INTENT_DECISION_PROTOCOL_INVALID;
    }

    private record DurationBudget(long startedAtNanos, long timeoutNanos) {
        private static DurationBudget start(java.time.Duration timeout) {
            long timeoutNanos;
            try {
                timeoutNanos = timeout.toNanos();
            } catch (ArithmeticException ex) {
                timeoutNanos = Long.MAX_VALUE;
            }
            return new DurationBudget(System.nanoTime(), timeoutNanos);
        }

        private java.time.Duration remaining() {
            long elapsed = Math.max(0L, System.nanoTime() - startedAtNanos);
            if (elapsed >= timeoutNanos) {
                return java.time.Duration.ZERO;
            }
            return java.time.Duration.ofNanos(timeoutNanos - elapsed);
        }
    }
}
