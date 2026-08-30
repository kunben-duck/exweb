/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.sessiontitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.huawei.it.ex.one.application.config.IntegrationAuthProperties;
import com.huawei.it.ex.one.application.config.SessionTitleProperties;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleAppExclusionProvider;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleProvider;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

class SessionTitleProviderConfigurationTest {
    @Test
    void enabledDefaultProviderRequiresUrlTimeoutAndAuthentication() {
        SessionTitleProviderConfiguration configuration = new SessionTitleProviderConfiguration();
        SessionTitleProperties properties = new SessionTitleProperties();
        properties.setEnabled(true);

        assertThatThrownBy(() -> configuration.sessionTitleProvider(
                WebClient.builder(), properties, new IntegrationAuthProperties(),
                mock(AuthHeaderProviderRegistry.class), Schedulers.immediate()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("financeex.session-title.base-url");
    }

    @Test
    void enabledDefaultProviderStartsWithExplicitConfiguration() {
        SessionTitleProviderConfiguration configuration = new SessionTitleProviderConfiguration();
        SessionTitleProperties properties = new SessionTitleProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://session-title.example.test");
        properties.setTimeout("30s");
        IntegrationAuthProperties auth = configuredAuth();

        SessionTitleProvider provider = configuration.sessionTitleProvider(
                WebClient.builder(), properties, auth,
                mock(AuthHeaderProviderRegistry.class), Schedulers.immediate());

        assertThat(provider).isInstanceOf(DefaultSessionTitleProvider.class);
    }

    @Test
    void enabledDefaultProviderRejectsTimeoutAboveHardLimit() {
        SessionTitleProviderConfiguration configuration = new SessionTitleProviderConfiguration();
        SessionTitleProperties properties = new SessionTitleProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://session-title.example.test");
        properties.setTimeout("31s");

        assertThatThrownBy(() -> configuration.sessionTitleProvider(
                WebClient.builder(), properties, configuredAuth(),
                mock(AuthHeaderProviderRegistry.class), Schedulers.immediate()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not exceed 30s");
    }

    @Test
    void customProviderDoesNotRequireDefaultHttpConfiguration() {
        SessionTitleProvider custom = request -> Mono.just("custom");

        new ApplicationContextRunner()
                .withUserConfiguration(SessionTitleProviderConfiguration.class)
                .withPropertyValues("financeex.session-title.enabled=true")
                .withBean(SessionTitleProvider.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SessionTitleProvider.class)).isSameAs(custom);
                });
    }

    @Test
    void defaultAppExclusionProviderUsesConfiguredAppIds() {
        SessionTitleProvider customTitleProvider = request -> Mono.just("custom");

        new ApplicationContextRunner()
                .withUserConfiguration(SessionTitleProviderConfiguration.class)
                .withPropertyValues("financeex.session-title.excluded-app-ids= app-a,app-b ")
                .withBean(SessionTitleProvider.class, () -> customTitleProvider)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SessionTitleAppExclusionProvider provider =
                            context.getBean(SessionTitleAppExclusionProvider.class);
                    assertThat(provider).isInstanceOf(DefaultSessionTitleAppExclusionProvider.class);
                    assertThat(provider.isExcluded("app-a").block()).isTrue();
                    assertThat(provider.isExcluded("APP-A").block()).isFalse();
                });
    }

    @Test
    void customAppExclusionProviderOverridesDefault() {
        SessionTitleProvider customTitleProvider = request -> Mono.just("custom");
        SessionTitleAppExclusionProvider customExclusionProvider = appId -> Mono.just(true);

        new ApplicationContextRunner()
                .withUserConfiguration(SessionTitleProviderConfiguration.class)
                .withBean(SessionTitleProvider.class, () -> customTitleProvider)
                .withBean(SessionTitleAppExclusionProvider.class, () -> customExclusionProvider)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SessionTitleAppExclusionProvider.class))
                            .isSameAs(customExclusionProvider);
                });
    }

    private IntegrationAuthProperties configuredAuth() {
        IntegrationAuthProperties properties = new IntegrationAuthProperties();
        properties.setEnabled(true);
        IntegrationAuthProperties.Service service = new IntegrationAuthProperties.Service();
        service.setProvider("sgov");
        properties.setServices(Map.of("session-title", service));
        properties.getSgov().setAppId("app-id");
        properties.getSgov().setSecret("secret");
        return properties;
    }
}
