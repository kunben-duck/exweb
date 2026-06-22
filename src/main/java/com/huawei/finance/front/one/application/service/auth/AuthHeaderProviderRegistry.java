package com.huawei.finance.front.one.application.service.auth;

import com.huawei.finance.front.one.application.config.IntegrationAuthProperties;
import com.huawei.finance.front.one.application.integration.auth.AuthHeaderProvider;
import com.huawei.finance.front.one.application.integration.auth.AuthHeaderRequest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 集成服务鉴权请求头 provider 注册表。
 */
@Component
public class AuthHeaderProviderRegistry {
    private final IntegrationAuthProperties properties;
    private final Map<String, AuthHeaderProvider> providers;

    public AuthHeaderProviderRegistry(IntegrationAuthProperties properties, List<AuthHeaderProvider> providers) {
        this.properties = properties;
        this.providers = providers == null ? Map.of() : providers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        provider -> normalize(provider.providerCode()),
                        Function.identity(),
                        (left, right) -> left
                ));
    }

    /**
     * 根据服务配置获取集成服务调用请求头。
     *
     * @param request 目标服务、操作和用户上下文。
     * @return 需要写入 HTTP 请求的 header；未启用或 provider=none 时为空。
     */
    public Map<String, String> headers(AuthHeaderRequest request) {
        if (request == null) {
            return Map.of();
        }
        String providerCode = properties.providerFor(request.serviceCode());
        if ("none".equals(providerCode)) {
            return Map.of();
        }
        AuthHeaderProvider provider = providers.get(providerCode);
        if (provider == null) {
            throw new IllegalArgumentException("未知集成服务鉴权 provider: " + providerCode);
        }
        return provider.headers(request);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
