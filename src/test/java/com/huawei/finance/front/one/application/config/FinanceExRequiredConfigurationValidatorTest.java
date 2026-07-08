package com.huawei.finance.front.one.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FinanceExRequiredConfigurationValidatorTest {
    private static final String[] COMMON_REQUIRED = {
            "spring.datasource.url=jdbc:postgresql://db.internal:5432/finex",
            "spring.datasource.username=finex",
            "spring.datasource.password=secret",
            "financeex.redis.mode=standalone",
            "financeex.redis.host=redis.internal",
            "financeex.websocket.allowed-origin-patterns=https://finex.example.com",
            "financeex.storage.provider=local",
            "financeex.agent-runtime.default-provider=relay"
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
    void completeStreamHttpConfigurationStarts() {
        contextWith("financeex.agent-runtime.base-url=https://relay.example.com")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void optionalIntegrationsDoNotRequireUrlsWhenDisabled() {
        contextWith(
                "financeex.agent-runtime.base-url=https://relay.example.com",
                "financeex.intent.enabled=false",
                "financeex.use-case-library.enabled=false",
                "financeex.domain-agent.enabled=false",
                "financeex.share.delivery.providers.welink.enabled=false"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void enabledOptionalIntegrationsRequireOwnBaseUrls() {
        contextWith(
                "financeex.agent-runtime.base-url=https://relay.example.com",
                "financeex.intent.enabled=true",
                "financeex.use-case-library.enabled=true",
                "financeex.domain-agent.enabled=true",
                "financeex.share.delivery.providers.welink.enabled=true"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("financeex.intent.base-url")
                    .hasMessageContaining("financeex.use-case-library.base-url")
                    .hasMessageContaining("financeex.domain-agent.base-url")
                    .hasMessageContaining("financeex.share.share-url-prefix")
                    .hasMessageContaining("financeex.share.delivery.providers.welink.base-url");
        });
    }

    @Test
    void huaweiS3StorageRequiresObjectStorageConfiguration() {
        contextWith(
                "financeex.agent-runtime.base-url=https://relay.example.com",
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
                "financeex.agent-runtime.base-url=https://relay.example.com",
                "financeex.storage.provider=api-store"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("financeex.storage.api-store.base-url");
        });
    }

    @Test
    void relayWebSocketAdapterRequiresOnlyWebSocketUrl() {
        contextWith(
                "financeex.agent-runtime.relay.adapter=relay-websocket",
                "financeex.agent-runtime.relay.websocket.url=wss://relay.example.com/ws"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    private ApplicationContextRunner contextWith(String... properties) {
        return contextRunner.withPropertyValues(Stream.concat(
                Stream.of(COMMON_REQUIRED),
                Stream.of(properties)
        ).toArray(String[]::new));
    }
}
