/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationException;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationProvider;
import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfigurationQuery;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/** 通过 Cookie 透传 HTTP 接口查询 DomainAgent 技能配置的默认防腐层实现。 */
public final class DefaultDomainAgentSkillConfigurationProvider
        implements DomainAgentSkillConfigurationProvider {
    private static final AppLogger log = AppLoggerFactory.getLogger(DefaultDomainAgentSkillConfigurationProvider.class);
    private final WebClient webClient;
    private final DomainAgentSkillConfigurationProperties properties;

    public DefaultDomainAgentSkillConfigurationProvider(
            WebClient.Builder webClientBuilder,
            DomainAgentSkillConfigurationProperties properties) {
        this.webClient = Objects.requireNonNull(webClientBuilder, "webClientBuilder").build();
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public Mono<DomainAgentSkillConfiguration> findBySkillId(
            DomainAgentSkillConfigurationQuery query) {
        if (query == null) {
            return Mono.error(protocolError("DomainAgent skill configuration query is null"));
        }
        if (query.skillId() == null || query.skillId().isBlank()) {
            return Mono.error(protocolError("DomainAgent skill configuration skillId is blank"));
        }
        Duration timeout = properties.normalizedTimeout();
        if (timeout == null) {
            return Mono.error(protocolError("DomainAgent skill configuration timeout is not configured"));
        }
        String skillId = query.skillId().trim();
        return requestConfiguration(query, skillId)
                .timeout(timeout)
                .map(response -> mapResponse(skillId, response))
                .onErrorMap(this::translateFailure);
    }

    private Mono<SkillConfigurationResponse> requestConfiguration(
            DomainAgentSkillConfigurationQuery query,
            String skillId) {
        String requestUrl = requestUrl();
        if (requestUrl == null) {
            return Mono.error(protocolError("DomainAgent skill configuration endpoint is not configured"));
        }
        return webClient.post()
                .uri(requestUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyForwardHeaders(headers, query.forwardHeaders()))
                .bodyValue(List.of(skillId))
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody().then(Mono.error(unavailable(
                                "DomainAgent skill configuration service returned HTTP "
                                        + response.statusCode().value(),
                                null)));
                    }
                    return response.bodyToMono(SkillConfigurationResponse.class)
                            .switchIfEmpty(Mono.error(protocolError(
                                    "DomainAgent skill configuration response is empty")));
                });
    }

    private void applyForwardHeaders(HttpHeaders headers, RuntimeForwardHeaders forwardHeaders) {
        if (forwardHeaders == null || !forwardHeaders.hasCookie()) {
            return;
        }
        // Cookie 仅作为配置查询出站请求头使用，不能进入请求体、缓存、日志或持久化数据。
        headers.set(HttpHeaders.COOKIE, forwardHeaders.cookieHeader());
    }

    private String requestUrl() {
        String baseUrl = properties.normalizedBaseUrl();
        String queryPath = properties.normalizedQueryPath();
        if (baseUrl == null || queryPath == null) {
            return null;
        }
        if (baseUrl.endsWith("/") && queryPath.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + queryPath;
        }
        if (!baseUrl.endsWith("/") && !queryPath.startsWith("/")) {
            return baseUrl + "/" + queryPath;
        }
        return baseUrl + queryPath;
    }

    private DomainAgentSkillConfiguration mapResponse(
            String requestedSkillId,
            SkillConfigurationResponse response) {
        if (response == null) {
            throw protocolError("DomainAgent skill configuration response is empty");
        }
        if (normalize(response.status()).isEmpty()) {
            throw protocolError("DomainAgent skill configuration response has no status");
        }
        if (!"success".equalsIgnoreCase(normalize(response.status()))) {
            throw unavailable("DomainAgent skill configuration service returned a failed status", null);
        }
        DomainAgentSkillConfiguration matched = null;
        for (SkillConfigurationItem item : safeItems(response.data())) {
            if (item == null || !requestedSkillId.equals(normalize(item.skillId()))) {
                continue;
            }
            DomainAgentSkillConfiguration candidate = parseConfiguration(
                    requestedSkillId,
                    item.skillName(),
                    item.isSaveSession(),
                    item.attachmentType(),
                    parseAllowedUploadCount(requestedSkillId, item.allowedUploadCount()));
            if (matched != null && !Objects.equals(matched.saveSession(), candidate.saveSession())) {
                throw protocolError("Conflicting DomainAgent skill configuration entries");
            }
            matched = candidate;
        }
        return matched == null
                ? DomainAgentSkillConfiguration.unconfigured(requestedSkillId)
                : matched;
    }

    private DomainAgentSkillConfiguration parseConfiguration(
            String skillId,
            String skillName,
            String value,
            String attachmentType,
            Integer allowedUploadCount) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return new DomainAgentSkillConfiguration(
                    skillId, nullableText(skillName), null, nullableText(attachmentType), allowedUploadCount);
        }
        return switch (normalized) {
            case "N" -> new DomainAgentSkillConfiguration(
                    skillId, nullableText(skillName), Boolean.FALSE, nullableText(attachmentType), allowedUploadCount);
            case "Y" -> new DomainAgentSkillConfiguration(
                    skillId, nullableText(skillName), Boolean.TRUE, nullableText(attachmentType), allowedUploadCount);
            default -> throw protocolError("Invalid isSaveSession value in DomainAgent skill configuration");
        };
    }

    private Integer parseAllowedUploadCount(String skillId, JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        // 禁止 Jackson 将小数、字符串或溢出值强转为整数；坏字段不应丢失同快照的留存与类型配置。
        if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0) {
            return value.intValue();
        }
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.CONFIGURATION_INVALID,
                        "DomainAgent attachment count configuration is invalid; count limit is skipped")
                .operation("domain-agent-attachment.config")
                .attribute("skillId", skillId)
                .build());
        return null;
    }

    private String nullableText(String value) {
        String normalized = normalize(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private Throwable translateFailure(Throwable failure) {
        Throwable cause = Exceptions.unwrap(failure);
        if (cause instanceof DomainAgentSkillConfigurationException) {
            return cause;
        }
        if (cause instanceof TimeoutException) {
            return new DomainAgentSkillConfigurationException(
                    DomainAgentSkillConfigurationException.Reason.TIMEOUT,
                    "DomainAgent skill configuration request timed out",
                    cause);
        }
        if (cause instanceof DecodingException) {
            return new DomainAgentSkillConfigurationException(
                    DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID,
                    "DomainAgent skill configuration response is invalid",
                    cause);
        }
        return unavailable("DomainAgent skill configuration request failed", cause);
    }

    private DomainAgentSkillConfigurationException protocolError(String message) {
        return new DomainAgentSkillConfigurationException(
                DomainAgentSkillConfigurationException.Reason.PROTOCOL_INVALID,
                message);
    }

    private DomainAgentSkillConfigurationException unavailable(String message, Throwable cause) {
        return new DomainAgentSkillConfigurationException(
                DomainAgentSkillConfigurationException.Reason.UNAVAILABLE,
                message,
                cause);
    }

    private List<SkillConfigurationItem> safeItems(List<SkillConfigurationItem> items) {
        return items == null ? List.of() : items;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
