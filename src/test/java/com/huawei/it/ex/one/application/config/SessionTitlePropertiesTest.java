package com.huawei.it.ex.one.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

class SessionTitlePropertiesTest {

    @Test
    void normalizesExcludedAppIds() {
        SessionTitleProperties properties = new SessionTitleProperties();

        properties.setExcludedAppIds(List.of(" app-a ", "", "app-b", "app-a"));

        assertThat(properties.getExcludedAppIds()).containsExactly("app-a", "app-b");
    }

    @Test
    void nullConfigurationKeepsExclusionDisabled() {
        SessionTitleProperties properties = new SessionTitleProperties();

        properties.setExcludedAppIds(null);

        assertThat(properties.getExcludedAppIds()).isEmpty();
    }

    @Test
    void applicationYamlBindsCommaSeparatedExcludedAppIds() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "test-environment",
                Map.of("FINANCEEX_SESSION_TITLE_EXCLUDED_APP_IDS", " app-a, ,app-b,app-a ")));
        new YamlPropertySourceLoader()
                .load("application", new FileSystemResource("src/main/resources/application.yml"))
                .forEach(environment.getPropertySources()::addLast);

        SessionTitleProperties properties = Binder.get(environment)
                .bind("financeex.session-title", Bindable.of(SessionTitleProperties.class))
                .orElseThrow(() -> new IllegalStateException("financeex.session-title was not bound"));

        assertThat(properties.getExcludedAppIds()).containsExactly("app-a", "app-b");
    }
}
