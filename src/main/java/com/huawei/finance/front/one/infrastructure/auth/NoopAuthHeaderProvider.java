package com.huawei.finance.front.one.infrastructure.auth;

import com.huawei.finance.front.one.application.integration.auth.AuthHeaderProvider;
import com.huawei.finance.front.one.application.integration.auth.AuthHeaderRequest;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 不添加任何集成服务鉴权头的 provider。
 */
@Component
public class NoopAuthHeaderProvider implements AuthHeaderProvider {
    @Override
    public String providerCode() {
        return "none";
    }

    @Override
    public Map<String, String> headers(AuthHeaderRequest request) {
        return Map.of();
    }
}
