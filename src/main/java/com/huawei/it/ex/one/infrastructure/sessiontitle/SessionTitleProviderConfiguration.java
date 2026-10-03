/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.sessiontitle;

import com.huawei.it.ex.one.application.config.IntegrationAuthProperties;
import com.huawei.it.ex.one.application.config.SessionTitleProperties;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleAppExclusionProvider;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleProvider;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;

/** 会话标题 HTTP/模型 Provider 与标题旁路调度器装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionTitleProperties.class)
public class SessionTitleProviderConfiguration {
    @Bean(name = "sessionTitleIoScheduler", destroyMethod = "dispose")
    public Scheduler sessionTitleIoScheduler() {
        return Schedulers.newBoundedElastic(4, 128, "finex-session-title-io");
    }

    @Bean
    @ConditionalOnMissingBean(SessionTitleAppExclusionProvider.class)
    public SessionTitleAppExclusionProvider sessionTitleAppExclusionProvider(
            SessionTitleProperties properties) {
        return new DefaultSessionTitleAppExclusionProvider(properties);
    }

    @Bean
    @ConditionalOnMissingBean(SessionTitleProvider.class)
    public SessionTitleProvider sessionTitleProvider(
            WebClient.Builder webClientBuilder,
            SessionTitleProperties properties,
            IntegrationAuthProperties authProperties,
            AuthHeaderProviderRegistry authHeaders,
            @Qualifier("sessionTitleIoScheduler") Scheduler ioScheduler) {
        if (properties.isEnabled()) {
            validateRequiredConfiguration(properties, authProperties);
        }
        if (properties.getMode() == SessionTitleProperties.Mode.MODEL) {
            if (properties.getModel().normalizedEndpoint() == null) {
                return request -> Mono.error(new IllegalStateException("Session title model is not configured"));
            }
            return new ModelSessionTitleProvider(webClientBuilder, properties);
        }
        if (properties.normalizedBaseUrl() == null) {
            return request -> Mono.error(new IllegalStateException("Session title provider is not configured"));
        }
        return new DefaultSessionTitleProvider(webClientBuilder, properties, authHeaders, ioScheduler);
    }

    private void validateRequiredConfiguration(
            SessionTitleProperties properties,
            IntegrationAuthProperties authProperties) {
        if (properties.getMode() == null) {
            throw missingConfiguration("financeex.session-title.mode");
        }
        if (properties.getMode() == SessionTitleProperties.Mode.MODEL) {
            validateModelConfiguration(properties.getModel());
        } else {
            validateHttpUrl(properties.normalizedBaseUrl(), "financeex.session-title.base-url");
            if (properties.normalizedPath() == null) {
                throw missingConfiguration("financeex.session-title.path");
            }
        }
        Duration timeout = properties.normalizedTimeout();
        if (timeout == null) {
            throw missingConfiguration("financeex.session-title.timeout");
        }
        if (timeout.compareTo(SessionTitleProperties.MAX_REQUEST_TIMEOUT) > 0) {
            throw new IllegalStateException("financeex.session-title.timeout must not exceed 60s");
        }
        properties.normalizedMaxConcurrentRequests();
        if (properties.normalizedDefaultLanguage().length() > 32) {
            throw new IllegalStateException("financeex.session-title.default-language must not exceed 32 characters");
        }
        if (properties.getMaxTitleLength() < 1 || properties.getMaxTitleLength() > 256) {
            throw new IllegalStateException("financeex.session-title.max-title-length must be between 1 and 256");
        }
        // MODEL仅使用模型网关的静态密钥，不能要求旧HTTP服务的SGOV配置。
        if (properties.getMode() == SessionTitleProperties.Mode.MODEL) {
            return;
        }
        String provider = authProperties.providerFor("session-title");
        if ("none".equals(provider)) {
            throw missingConfiguration("financeex.integration-auth.services.session-title.provider");
        }
        if ("sgov".equals(provider)) {
            validateSgovConfiguration(authProperties.getSgov());
        }
    }

    private void validateModelConfiguration(SessionTitleProperties.Model model) {
        validateHttpUrl(model.normalizedEndpoint(), "financeex.session-title.model.endpoint");
        if (model.normalizedName() == null) {
            throw missingConfiguration("financeex.session-title.model.name");
        }
        if (model.normalizedApiKey() == null) {
            throw missingConfiguration("financeex.session-title.model.api-key");
        }
    }

    private void validateHttpUrl(String url, String propertyName) {
        if (url == null) {
            throw missingConfiguration(propertyName);
        }
        try {
            URI uri = URI.create(url);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("unsupported URI");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(propertyName + " must be a valid HTTP URL", ex);
        }
    }

    private void validateSgovConfiguration(IntegrationAuthProperties.Sgov sgov) {
        if (sgov == null || isBlank(sgov.getAppId())) {
            throw missingConfiguration("financeex.integration-auth.sgov.app-id");
        }
        if (isBlank(sgov.getSecret())) {
            throw missingConfiguration("financeex.integration-auth.sgov.secret");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private IllegalStateException missingConfiguration(String propertyName) {
        return new IllegalStateException(propertyName + " must be configured when session title summary is enabled");
    }
}
