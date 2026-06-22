package com.huawei.finance.front.one.infrastructure.auth;

import com.huawei.finance.front.one.application.integration.auth.AuthHeaderRequest;
import com.huawei.finance.front.one.application.integration.auth.SgovTokenResolver;
import java.util.Optional;

/**
 * 默认 Sgov token resolver。
 *
 * <p>本服务不内置企业 token 获取逻辑；接入企业框架时提供新的 {@link SgovTokenResolver}
 * bean 覆盖即可。</p>
 */
public class UnsupportedSgovTokenResolver implements SgovTokenResolver {
    @Override
    public Optional<String> resolve(AuthHeaderRequest request, String appId, String secret) {
        return Optional.empty();
    }
}
