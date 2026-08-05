package com.huawei.it.ex.one.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.stream.Stream;

class FinanceExRequiredConfigurationValidatorTest {
    private static final String[] COMMON_REQUIRED = {
            "spring.datasource.url=jdbc:postgresql://db.internal:5432/finex",
            "spring.datasource.username=finex",
            "spring.datasource.password=secret",
            "financeex.redis.mode=standalone",
            "financeex.redis.host=redis.internal",
            "financeex.websocket.allowed-origin-patterns=https://finex.example.com",
            "financeex.storage.provider=local",
            "financeex.agent-runtime.default-provider=relay",
            "financeex.agent-runtime.relay.websocket.url=wss://relay.example.com/ws"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FinanceExRequiredConfigurationValidator.class);

    @Test
    void missingRequiredConfigurationFailsFast() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("spring.datasource.url")
                    .hasMessageContaining("financeex.redis.mode")
                    .hasMessageContaining("financeex.storage.provider");
        });
    }

    @Test
    void completeWebSocketConfigurationStarts() {
        contextWith().run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void optionalIntegrationsDoNotRequireUrlsWhenDisabled() {
        contextWith(
                "financeex.intent.enabled=false",
                "financeex.use-case-library.enabled=false",
                "financeex.domain-agent.enabled=false",
                "financeex.share.delivery.providers.welink.enabled=false"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void enabledOptionalIntegrationsRequireOwnBaseUrls() {
        contextWith(
                "financeex.intent.enabled=true",
                "financeex.use-case-library.enabled=true",
                "financeex.domain-agent.enabled=true",
                "financeex.share.delivery.providers.welink.enabled=true"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("financeex.intent.base-url")
                    .hasMessageContaining("financeex.intent.domain-expert-access-name-prefix")
                    .hasMessageContaining("financeex.use-case-library.base-url")
                    .hasMessageContaining("financeex.domain-agent.base-url")
                    .hasMessageContaining("financeex.share.share-url-prefix")
                    .hasMessageContaining("financeex.share.delivery.providers.welink.base-url");
        });
    }

    @Test
    void enabledIntentStartsWithExplicitDomainExpertPrefix() {
        contextWith(
                "financeex.intent.enabled=true",
                "financeex.intent.base-url=https://intent.example.com",
                "financeex.intent.access-name=intent-service",
                "financeex.intent.domain-expert-access-name-prefix=RE_"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void huaweiS3StorageRequiresObjectStorageConfiguration() {
        contextWith(
                "financeex.storage.provider=huawei-s3"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("financeex.storage.huawei-s3.bucket")
                    .hasMessageContaining("financeex.storage.huawei-s3.endpoint")
                    .hasMessageContaining("financeex.storage.huawei-s3.access-key")
                    .hasMessageContaining("financeex.storage.huawei-s3.secret-key");
        });
    }

    @Test
    void apiStoreStorageRequiresBaseUrl() {
        contextWith(
                "financeex.storage.provider=api-store"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("financeex.storage.api-store.base-url");
        });
    }

    @Test
    void enabledRelayRequiresWebSocketUrl() {
        contextWithoutRelayUrl().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("financeex.agent-runtime.relay.websocket.url")
                    .hasMessageContaining("FINANCEEX_RELAY_WS_URL");
        });
    }

    @Test
    void disabledRelayDoesNotRequireWebSocketUrl() {
        contextWithoutRelayUrl(
                "financeex.agent-runtime.relay.enabled=false",
                "financeex.agent-runtime.default-provider=domain-agent",
                "financeex.domain-agent.enabled=true",
                "financeex.domain-agent.base-url=https://domain-agent.example.com"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    private ApplicationContextRunner contextWith(String... properties) {
        return contextRunner.withPropertyValues(Stream.concat(
                Stream.of(COMMON_REQUIRED),
                Stream.of(properties)
        ).toArray(String[]::new));
    }

    private ApplicationContextRunner contextWithoutRelayUrl(String... properties) {
        return contextRunner.withPropertyValues(Stream.concat(
                Stream.of(COMMON_REQUIRED)
                        .filter(property -> !property.startsWith("financeex.agent-runtime.relay.websocket.url=")),
                Stream.of(properties)
        ).toArray(String[]::new));
    }
}
