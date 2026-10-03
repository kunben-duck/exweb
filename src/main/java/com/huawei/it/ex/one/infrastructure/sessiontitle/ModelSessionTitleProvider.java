/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.sessiontitle;

import com.huawei.it.ex.one.application.config.SessionTitleProperties;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleProvider;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/** 通过OpenAI兼容协议生成标题；重试、解析和网络等待共用一次调用期限。 */
public final class ModelSessionTitleProvider implements SessionTitleProvider {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_QUERY_CODE_POINTS = 500;
    private static final int MAX_QUERIES = 3;

    private final WebClient webClient;
    private final SessionTitleProperties properties;

    public ModelSessionTitleProvider(WebClient.Builder builder, SessionTitleProperties properties) {
        this.properties = properties;
        // 克隆Builder，响应容量限制只作用于标题模型，不改变其他下游的WebClient配置。
        this.webClient = builder.clone()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
    }

    @Override
    public Mono<String> generate(SessionTitleRequest request) {
        return Mono.defer(() -> {
            ModelRequest body = new ModelRequest(properties.getModel().normalizedName(),
                    List.of(new ModelMessage("user", prompt(request))), false);
            return requestTitle(body)
                    .retryWhen(Retry.backoff(1, Duration.ofMillis(500)).jitter(0.5)
                            .filter(this::retryable)
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
        }).timeout(properties.effectiveRequestTimeout());
    }

    private Mono<String> requestTitle(ModelRequest body) {
        return webClient.post()
                .uri(URI.create(properties.getModel().normalizedEndpoint()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(properties.getModel().normalizedApiKey()))
                .bodyValue(body)
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        // 不保存错误响应正文、请求头或密钥到异常中。
                        return response.releaseBody().then(Mono.<JsonNode>error(
                                new ModelHttpException(response.statusCode().value())));
                    }
                    return response.bodyToMono(JsonNode.class);
                })
                .switchIfEmpty(Mono.error(new IllegalStateException("Session title model returned an empty response")))
                .map(this::extractTitle);
    }

    private String extractTitle(JsonNode response) {
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.textValue().isBlank()) {
            throw new IllegalStateException("Session title model response does not contain a nonblank title");
        }
        return content.textValue();
    }

    private boolean retryable(Throwable failure) {
        if (failure instanceof ModelHttpException http) {
            return http.status == 429 || (http.status >= 500 && http.status < 600);
        }
        Throwable transportFailure = failure instanceof WebClientRequestException ? failure.getCause() : failure;
        // 响应头之后的连接中断可直接以IOException传播；JSON协议错误不属于可重试传输错误。
        return transportFailure instanceof IOException && !(transportFailure instanceof JsonProcessingException);
    }

    private String prompt(SessionTitleRequest request) {
        String conversation = request.queries().stream().limit(MAX_QUERIES)
                .map(String::trim).filter(query -> !query.isEmpty())
                .map(this::truncateQuery)
                .map(query -> "user: " + query)
                .collect(Collectors.joining("\n"));
        if (conversation.isBlank()) {
            throw new IllegalArgumentException("Session title model requires nonblank queries");
        }
        String language = "en_US".equalsIgnoreCase(properties.normalizeLanguage(request.language()))
                ? "English" : "Chinese";
        return """
                Based on the following conversation, generate a concise topic summary in %s.

                Requirements:
                1. Maximum %d characters
                2. Capture the main theme or purpose of the conversation
                3. Be specific and descriptive
                4. If the conversation contains any document/ticket/order numbers (e.g. DTSxxx, SRxxx, POxxx, PRxxx), you MUST preserve them exactly as-is in the topic
                5. Ignore images, attachments, base64 encoded data, and multimedia content - focus only on text
                6. Output ONLY the topic text, no quotes or explanations

                Conversation:
                %s

                Topic (≤%d characters):
                """.formatted(language, properties.getMaxTitleLength(), conversation, properties.getMaxTitleLength());
    }

    private String truncateQuery(String query) {
        return query.codePointCount(0, query.length()) <= MAX_QUERY_CODE_POINTS ? query
                : query.substring(0, query.offsetByCodePoints(0, MAX_QUERY_CODE_POINTS)) + "...";
    }

    private record ModelMessage(String role, String content) {
    }

    private record ModelRequest(String model, List<ModelMessage> messages, boolean stream) {
    }

    private static final class ModelHttpException extends RuntimeException {
        private final int status;

        private ModelHttpException(int status) {
            super("Session title model returned HTTP " + status);
            this.status = status;
        }
    }
}
