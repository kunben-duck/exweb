package com.huawei.finance.front.one.infrastructure.share;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.ChatShareDeliveryProperties;
import com.huawei.finance.front.one.application.integration.auth.AuthHeaderRequest;
import com.huawei.finance.front.one.application.integration.share.ChatShareDeliveryProvider;
import com.huawei.finance.front.one.application.integration.share.ChatShareProviderDeliveryRequest;
import com.huawei.finance.front.one.application.integration.share.ChatShareProviderDeliveryResult;
import com.huawei.finance.front.one.application.service.auth.AuthHeaderProviderRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WeLink 分享发送 provider。
 *
 * <p>该实现只负责 WeLink wire 协议转换和成功判断，不读取 HTTP 请求上下文。
 * Cookie 必须由接口入口捕获为 {@link com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders}
 * 后显式传入；Cookie、Authorization 或企业鉴权头都不会写入发送记录。</p>
 */
@Component
public class WelinkChatShareDeliveryProvider implements ChatShareDeliveryProvider {
    private static final int MAX_RESPONSE_BODY_LENGTH = 4096;

    private final WebClient.Builder webClientBuilder;
    private final ChatShareDeliveryProperties properties;
    private final ObjectMapper objectMapper;
    private final AuthHeaderProviderRegistry authHeaders;

    public WelinkChatShareDeliveryProvider(WebClient.Builder webClientBuilder,
                                           ChatShareDeliveryProperties properties,
                                           ObjectMapper objectMapper,
                                           AuthHeaderProviderRegistry authHeaders) {
        this.webClientBuilder = webClientBuilder;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.authHeaders = authHeaders;
    }

    @Override
    public String providerCode() {
        return "welink";
    }

    @Override
    public ChatShareProviderDeliveryResult deliver(ChatShareProviderDeliveryRequest request) {
        ChatShareDeliveryProperties.Welink welink = properties.getDelivery().getProviders().getWelink();
        ensureEnabled(welink);
        int maxAttempts = 1 + welink.normalizedMaxRetries();
        ChatShareProviderDeliveryResult lastResult = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            lastResult = callOnce(request, welink);
            if (lastResult.success()) {
                return lastResult;
            }
        }
        return lastResult == null
                ? ChatShareProviderDeliveryResult.failed("WELINK_CALL_FAILED", "WeLink 调用失败", Map.of())
                : lastResult;
    }

    private ChatShareProviderDeliveryResult callOnce(ChatShareProviderDeliveryRequest request,
                                                     ChatShareDeliveryProperties.Welink welink) {
        Duration timeout = welink.normalizedTimeout();
        try {
            WelinkHttpResponse response = webClientBuilder.baseUrl(welink.getBaseUrl().trim())
                    .build()
                    .post()
                    .uri(welink.getSendPath().trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> applyOutboundHeaders(headers, request, welink))
                    .bodyValue(toWireRequest(request))
                    .exchangeToMono(clientResponse -> clientResponse.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new WelinkHttpResponse(clientResponse.statusCode().value(), body)))
                    .timeout(timeout)
                    .block(timeout.plusMillis(100));
            return toResult(response, welink);
        } catch (Exception ex) {
            return ChatShareProviderDeliveryResult.failed(
                    "WELINK_CALL_FAILED",
                    safeMessage(ex),
                    Map.of("exception", ex.getClass().getSimpleName())
            );
        }
    }

    private void ensureEnabled(ChatShareDeliveryProperties.Welink welink) {
        if (welink == null || !welink.isEnabled()) {
            throw new IllegalArgumentException("分享发送 provider 未启用: welink");
        }
        if (welink.getBaseUrl() == null || welink.getBaseUrl().isBlank()) {
            throw new IllegalStateException("未配置 WeLink 分享发送 base-url");
        }
        if (welink.getSendPath() == null || welink.getSendPath().isBlank()) {
            throw new IllegalStateException("未配置 WeLink 分享发送 send-path");
        }
    }

    private Map<String, Object> toWireRequest(ChatShareProviderDeliveryRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userAccount", nullToEmpty(request.userAccount()));
        body.put("title", nullToEmpty(request.title()));
        body.put("linkUrl", nullToEmpty(request.linkUrl()));
        body.put("content", nullToEmpty(request.content()));
        body.put("targetAccount", nullToEmpty(request.targetAccount()));
        body.put("groupID", nullToEmpty(request.groupId()));
        if (request.language() != null && !request.language().isBlank()) {
            body.put("language", request.language().trim());
        }
        return body;
    }

    private void applyOutboundHeaders(HttpHeaders headers, ChatShareProviderDeliveryRequest request,
                                      ChatShareDeliveryProperties.Welink welink) {
        authHeaders.headers(new AuthHeaderRequest(
                request.tenantId(),
                request.userAccount(),
                "welink-share",
                "send",
                welink.getBaseUrl(),
                welink.getSendPath(),
                "welink"
        )).forEach(headers::set);
        String referer = welink.normalizedReferer();
        if (!referer.isBlank()) {
            headers.set(HttpHeaders.REFERER, referer);
        }
        if (request.forwardHeaders().hasCookie()) {
            headers.set(HttpHeaders.COOKIE, request.forwardHeaders().cookieHeader());
        }
    }

    private ChatShareProviderDeliveryResult toResult(WelinkHttpResponse response,
                                                     ChatShareDeliveryProperties.Welink welink) {
        if (response == null) {
            return ChatShareProviderDeliveryResult.failed("WELINK_EMPTY_RESPONSE", "WeLink 响应为空", Map.of());
        }
        Map<String, Object> summary = responseSummary(response, welink.getSuccessStatusField());
        if (response.httpStatus() < 200 || response.httpStatus() >= 300) {
            return ChatShareProviderDeliveryResult.failed("WELINK_HTTP_STATUS",
                    "WeLink HTTP 状态码非 2xx: " + response.httpStatus(), summary);
        }
        String providerStatus = providerStatus(response.body(), welink.getSuccessStatusField());
        String successValue = welink.getSuccessStatusValue() == null ? "200" : welink.getSuccessStatusValue();
        if (successValue.equals(providerStatus)) {
            return ChatShareProviderDeliveryResult.success(summary);
        }
        return ChatShareProviderDeliveryResult.failed("WELINK_STATUS",
                "WeLink 返回状态不是成功值: " + providerStatus, summary);
    }

    private Map<String, Object> responseSummary(WelinkHttpResponse response, String statusField) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("httpStatus", response.httpStatus());
        String status = providerStatus(response.body(), statusField);
        if (status != null) {
            summary.put("status", status);
        }
        return summary;
    }

    private String providerStatus(String body, String statusField) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode status = select(root, statusField == null || statusField.isBlank() ? "status" : statusField);
            return status == null || status.isMissingNode() || status.isNull() ? null : status.asText();
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private JsonNode select(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return root;
        }
        JsonNode current = root;
        for (String part : path.split("\\.")) {
            if (current == null || part.isBlank()) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_RESPONSE_BODY_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_RESPONSE_BODY_LENGTH);
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : truncate(message);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record WelinkHttpResponse(int httpStatus, String body) {}
}
