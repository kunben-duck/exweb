package com.huawei.finance.front.one.infrastructure.id;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeneratedApplicationInstanceIdProviderTest {
    @Test
    void usesConfiguredInstanceIdWhenProvided() {
        GeneratedApplicationInstanceIdProvider provider = new GeneratedApplicationInstanceIdProvider("configured-instance");

        assertThat(provider.currentInstanceId()).isEqualTo("configured-instance");
    }

    @Test
    void generatedIdsAreStablePerProviderAndDifferentAcrossProviders() {
        GeneratedApplicationInstanceIdProvider first = new GeneratedApplicationInstanceIdProvider("");
        GeneratedApplicationInstanceIdProvider second = new GeneratedApplicationInstanceIdProvider("");

        assertThat(first.currentInstanceId()).startsWith("finex-");
        assertThat(first.currentInstanceId()).isEqualTo(first.currentInstanceId());
        assertThat(second.currentInstanceId()).startsWith("finex-");
        assertThat(first.currentInstanceId()).isNotEqualTo(second.currentInstanceId());
    }
}
