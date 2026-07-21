package com.huawei.it.ex.one.security.infrastructure.auth;

import com.huawei.it.ex.one.security.application.auth.AuthHeaderRequest;
import com.huawei.it.ex.one.security.application.auth.SgovTokenResolver;
import java.util.Optional;

/**
 * Sgov token resolver 的生产默认实现。
 *
 * <p>默认实现不内置企业 token 获取逻辑，只返回空结果；当集成鉴权启用且目标服务选择
 * {@code sgov} provider 时，上层会据此返回明确的鉴权配置错误。企业接入时可提供新的
 * {@link SgovTokenResolver} bean 覆盖该实现。</p>
 */
public class DefaultSgovTokenResolver implements SgovTokenResolver {
    @Override
    public Optional<String> resolve(AuthHeaderRequest request, String appId, String secret) {
        return Optional.empty();
    }
}
