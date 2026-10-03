/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.sessiontitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.huawei.it.ex.one.application.config.IntegrationAuthProperties;
import com.huawei.it.ex.one.application.config.SessionTitleProperties;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleAppExclusionProvider;
import com.huawei.it.ex.one.application.integration.sessiontitle.SessionTitleProvider;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

class SessionTitleProviderConfigurationTest {
    @Test
    void modelModeDoesNotRequireLegacyUrlOrIntegrationAuthentication() {
        SessionTitleProperties properties = modelProperties();
        AuthHeaderProviderRegistry auth = mock(AuthHeaderProviderRegistry.class);

        SessionTitleProvider provider = new SessionTitleProviderConfiguration().sessionTitleProvider(
                WebClient.builder(), properties, new IntegrationAuthProperties(), auth, Schedulers.immediate());

        assertThat(provider).isInstanceOf(ModelSessionTitleProvider.class);
        verifyNoInteractions(auth);
    }

    @ParameterizedTest
    @ValueSource(strings = {"endpoint", "name", "api-key", "timeout"})
    void modelModeValidatesItsOwnRequiredConfiguration(String missing) {
        SessionTitleProperties properties = modelProperties();
        switch (missing) {
            case "endpoint" -> properties.getModel().setEndpoint(" ");
            case "name" -> properties.getModel().setName(null);
            case "api-key" -> properties.getModel().setApiKey(" ");
            default -> properties.setTimeout("");
        }
        assertThatThrownBy(() -> new SessionTitleProviderConfiguration().sessionTitleProvider(
                WebClient.builder(), properties, new IntegrationAuthProperties(),
                mock(AuthHeaderProviderRegistry.class), Schedulers.immediate()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(missing);
    }

    @ParameterizedTest
    @ValueSource(strings = {"61s", "60001ms", "0s", "-1s", "invalid"})
    void modelModeRejectsInvalidTimeout(String timeout) {
        SessionTitleProperties properties = modelProperties();
        properties.setTimeout(timeout);
        assertThatThrownBy(() -> new SessionTitleProviderConfiguration().sessionTitleProvider(
                WebClient.builder(), properties, new IntegrationAuthProperties(),
                mock(AuthHeaderProviderRegistry.class), Schedulers.immediate()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("timeout");
    }

    @Test
    void disabledModelAndCustomProviderDoNotRequireModelCredentials() {
        new ApplicationContextRunner()
                .withUserConfiguration(SessionTitleProviderConfiguration.class)
                .withPropertyValues("financeex.session-title.mode=MODEL")
                .withBean(WebClient.Builder.class, WebClient::builder)
                .withBean(IntegrationAuthProperties.class, IntegrationAuthProperties::new)
                .withBean(AuthHeaderProviderRegistry.class, () -> mock(AuthHeaderProviderRegistry.class))
                .run(context -> assertThat(context).hasNotFailed());

        SessionTitleProvider custom = request -> Mono.just("custom");
        new ApplicationContextRunner()
                .withUserConfiguration(SessionTitleProviderConfiguration.class)
                .withPropertyValues("financeex.session-title.enabled=true", "financeex.session-title.mode=MODEL")
                .withBean(SessionTitleProvider.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SessionTitleProvider.class)).isSameAs(custom);
                });
    }

    @Test
    void modelModeRejectsNonHttpEndpoint() {
        SessionTitleProperties properties = modelProperties();
        properties.getModel().setEndpoint("file:///model");
        assertThatThrownBy(() -> new SessionTitleProviderConfiguration().sessionTitleProvider(
                WebClient.builder(), properties, new IntegrationAuthProperties(),
                mock(AuthHeaderProviderRegistry.class), Schedulers.immediate()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("valid HTTP URL");
    }

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
        properties.setTimeout("61s");

        assertThatThrownBy(() -> configuration.sessionTitleProvider(
                WebClient.builder(), properties, configuredAuth(),
                mock(AuthHeaderProviderRegistry.class), Schedulers.immediate()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not exceed 60s");
    }

    @ParameterizedTest
    @EnumSource(SessionTitleProperties.Mode.class)
    void bothModesAcceptSixtySecondTimeout(SessionTitleProperties.Mode mode) {
        SessionTitleProperties properties = modelProperties();
        properties.setMode(mode);
        properties.setBaseUrl("https://session-title.example.test");
        properties.setTimeout("60s");

        SessionTitleProvider provider = new SessionTitleProviderConfiguration().sessionTitleProvider(
                WebClient.builder(), properties, configuredAuth(),
                mock(AuthHeaderProviderRegistry.class), Schedulers.immediate());

        assertThat(provider).isInstanceOf(mode == SessionTitleProperties.Mode.MODEL
                ? ModelSessionTitleProvider.class : DefaultSessionTitleProvider.class);
        assertThat(properties.effectiveRequestTimeout()).hasSeconds(60);
    }

    @ParameterizedTest
    @ValueSource(strings = {"60001ms", "0s", "-1s", "invalid"})
    void httpModeRejectsInvalidTimeout(String timeout) {
        SessionTitleProperties properties = new SessionTitleProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://session-title.example.test");
        properties.setTimeout(timeout);

        assertThatThrownBy(() -> new SessionTitleProviderConfiguration().sessionTitleProvider(
                WebClient.builder(), properties, configuredAuth(),
                mock(AuthHeaderProviderRegistry.class), Schedulers.immediate()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("financeex.session-title.timeout");
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

    private SessionTitleProperties modelProperties() {
        SessionTitleProperties properties = new SessionTitleProperties();
        properties.setEnabled(true);
        properties.setMode(SessionTitleProperties.Mode.MODEL);
        properties.setTimeout("2s");
        properties.getModel().setEndpoint("https://model.example.test/v1/chat/completions");
        properties.getModel().setName("GLM-V5");
        properties.getModel().setApiKey("test-secret");
        return properties;
    }
}
