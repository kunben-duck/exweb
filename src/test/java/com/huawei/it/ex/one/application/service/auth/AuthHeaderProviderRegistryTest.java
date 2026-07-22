package com.huawei.it.ex.one.application.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.IntegrationAuthProperties;
import com.huawei.it.ex.one.application.integration.auth.AuthHeaderRequest;
import com.huawei.it.ex.one.infrastructure.auth.NoopAuthHeaderProvider;
import com.huawei.it.ex.one.infrastructure.auth.SgovAuthHeaderProvider;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

class AuthHeaderProviderRegistryTest {
    @Test
    void disabledConfigurationDoesNotAddHeaders() {
        IntegrationAuthProperties properties = new IntegrationAuthProperties();
        AuthHeaderProviderRegistry registry = registry(properties, Optional.of("token"));

        assertThat(registry.headers(request("welink-share"))).isEmpty();
    }

    @Test
    void configuredSgovServiceAddsAuthorizationHeader() {
        IntegrationAuthProperties properties = new IntegrationAuthProperties();
        properties.setEnabled(true);
        AuthHeaderProviderRegistry registry = registry(properties, Optional.of("Bearer abc"));

        assertThat(registry.headers(request("welink-share")))
                .containsEntry(HttpHeaders.AUTHORIZATION, "Bearer abc");
    }

    @Test
    void unconfiguredServiceUsesDefaultProviderNone() {
        IntegrationAuthProperties properties = new IntegrationAuthProperties();
        properties.setEnabled(true);
        AuthHeaderProviderRegistry registry = registry(properties, Optional.of("Bearer abc"));

        assertThat(registry.headers(request("relay-agent-runtime"))).isEmpty();
    }

    @Test
    void serviceConfigurationKeysAreCaseInsensitive() {
        IntegrationAuthProperties.Service service = new IntegrationAuthProperties.Service();
        service.setProvider("sgov");
        IntegrationAuthProperties properties = new IntegrationAuthProperties();
        properties.setEnabled(true);
        properties.setServices(Map.of("WeLink-Share", service));
        AuthHeaderProviderRegistry registry = registry(properties, Optional.of("Bearer abc"));

        assertThat(registry.headers(request("welink-share")))
                .containsEntry(HttpHeaders.AUTHORIZATION, "Bearer abc");
    }

    @Test
    void sgovWithoutTokenFailsClearly() {
        IntegrationAuthProperties properties = new IntegrationAuthProperties();
        properties.setEnabled(true);
        AuthHeaderProviderRegistry registry = registry(properties, Optional.empty());

        assertThatThrownBy(() -> registry.headers(request("intent-service")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sgov 集成服务鉴权 token 不可用");
    }

    private AuthHeaderProviderRegistry registry(IntegrationAuthProperties properties, Optional<String> token) {
        return new AuthHeaderProviderRegistry(properties, List.of(
                new NoopAuthHeaderProvider(),
                new SgovAuthHeaderProvider(properties, (request, appId, secret) -> token)
        ));
    }

    private AuthHeaderRequest request(String serviceCode) {
        return new AuthHeaderRequest("tenant1", "user1", serviceCode, "send",
                "http://service.test", "/api", null);
    }
}
