package com.huawei.it.ex.one.security.infrastructure.auth;

import com.huawei.it.ex.one.security.application.auth.AuthHeaderProvider;
import com.huawei.it.ex.one.security.application.auth.AuthHeaderRequest;
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
