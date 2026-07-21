package com.huawei.it.ex.one.security.infrastructure.auth;

import com.huawei.it.ex.one.security.infrastructure.config.IntegrationAuthProperties;
import com.huawei.it.ex.one.security.application.auth.AuthHeaderProvider;
import com.huawei.it.ex.one.security.application.auth.AuthHeaderRequest;
import com.huawei.it.ex.one.security.application.auth.SgovTokenResolver;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Sgov 集成服务鉴权 provider。
 *
 * <p>该类只负责把企业 token resolver 返回值放入 Authorization header；
 * token 如何获取、缓存和刷新由企业实现的 {@link SgovTokenResolver} 决定。</p>
 */
@Component
public class SgovAuthHeaderProvider implements AuthHeaderProvider {
    private final IntegrationAuthProperties properties;
    private final SgovTokenResolver tokenResolver;

    public SgovAuthHeaderProvider(IntegrationAuthProperties properties, SgovTokenResolver tokenResolver) {
        this.properties = properties;
        this.tokenResolver = tokenResolver;
    }

    @Override
    public String providerCode() {
        return "sgov";
    }

    @Override
    public Map<String, String> headers(AuthHeaderRequest request) {
        IntegrationAuthProperties.Sgov sgov = properties.getSgov();
        String token = tokenResolver.resolve(request, safe(sgov.getAppId()), safe(sgov.getSecret()))
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("Sgov 集成服务鉴权 token 不可用: " + request.serviceCode()));
        return Map.of(HttpHeaders.AUTHORIZATION, token);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
