package com.huawei.it.ex.one.infrastructure.sessiontitle;

import com.huawei.it.ex.one.application.config.IntegrationAuthProperties;
import com.huawei.it.ex.one.application.config.SessionTitleProperties;
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

/** 会话标题默认 Provider 与阻塞鉴权调度器装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionTitleProperties.class)
public class SessionTitleProviderConfiguration {
    @Bean(name = "sessionTitleIoScheduler", destroyMethod = "dispose")
    public Scheduler sessionTitleIoScheduler() {
        return Schedulers.newBoundedElastic(4, 128, "finex-session-title-io");
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
        if (properties.normalizedBaseUrl() == null) {
            return request -> Mono.error(new IllegalStateException("Session title provider is not configured"));
        }
        return new DefaultSessionTitleProvider(webClientBuilder, properties, authHeaders, ioScheduler);
    }

    private void validateRequiredConfiguration(
            SessionTitleProperties properties,
            IntegrationAuthProperties authProperties) {
        validateBaseUrl(properties.normalizedBaseUrl());
        if (properties.normalizedPath() == null) {
            throw missingConfiguration("financeex.session-title.path");
        }
        Duration timeout = properties.normalizedTimeout();
        if (timeout == null) {
            throw missingConfiguration("financeex.session-title.timeout");
        }
        if (timeout.compareTo(SessionTitleProperties.MAX_REQUEST_TIMEOUT) > 0) {
            throw new IllegalStateException("financeex.session-title.timeout must not exceed 30s");
        }
        properties.normalizedMaxConcurrentRequests();
        if (properties.normalizedDefaultLanguage().length() > 32) {
            throw new IllegalStateException("financeex.session-title.default-language must not exceed 32 characters");
        }
        if (properties.getMaxTitleLength() < 1 || properties.getMaxTitleLength() > 256) {
            throw new IllegalStateException("financeex.session-title.max-title-length must be between 1 and 256");
        }
        String provider = authProperties.providerFor("session-title");
        if ("none".equals(provider)) {
            throw missingConfiguration("financeex.integration-auth.services.session-title.provider");
        }
        if ("sgov".equals(provider)) {
            validateSgovConfiguration(authProperties.getSgov());
        }
    }

    private void validateBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            throw missingConfiguration("financeex.session-title.base-url");
        }
        try {
            URI uri = URI.create(baseUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("unsupported URI");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("financeex.session-title.base-url must be a valid HTTP URL", ex);
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
