package com.huawei.it.ex.one.infrastructure.sessiontitle;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.SessionTitleProperties;

import org.junit.jupiter.api.Test;

import java.util.List;

class DefaultSessionTitleAppExclusionProviderTest {
    @Test
    void usesNormalizedConfigurationAndMatchesCaseSensitively() {
        SessionTitleProperties properties = new SessionTitleProperties();
        properties.setExcludedAppIds(List.of(" app-a ", "", "app-b", "app-a"));
        DefaultSessionTitleAppExclusionProvider provider =
                new DefaultSessionTitleAppExclusionProvider(properties);

        assertThat(provider.isExcluded("app-a").block()).isTrue();
        assertThat(provider.isExcluded("app-b").block()).isTrue();
        assertThat(provider.isExcluded("APP-A").block()).isFalse();
        assertThat(provider.isExcluded(" app-a ").block()).isFalse();
    }

    @Test
    void keepsMainSiteAndEmptyConfigurationEligible() {
        DefaultSessionTitleAppExclusionProvider provider =
                new DefaultSessionTitleAppExclusionProvider(new SessionTitleProperties());

        assertThat(provider.isExcluded(null).block()).isFalse();
        assertThat(provider.isExcluded("app-a").block()).isFalse();
    }
}
