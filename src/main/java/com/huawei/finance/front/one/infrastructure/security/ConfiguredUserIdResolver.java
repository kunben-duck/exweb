package com.huawei.finance.front.one.infrastructure.security;

import com.huawei.finance.front.one.application.integration.identity.UserIdResolveRequest;
import com.huawei.finance.front.one.application.integration.identity.UserIdResolver;
import org.springframework.stereotype.Component;

/**
 * 开发态用户 ID 解析器。
 *
 * <p>它只接收 AuthContextProvider 已经从应用上下文解析出的 externalUserId，不再读取前端传参。
 * 生产环境可在这里做企业账号到本系统 userId 的映射，例如员工号、域账号、IAM subject 的转换。</p>
 */
@Component
public class ConfiguredUserIdResolver implements UserIdResolver {
    @Override
    public String resolveUserId(UserIdResolveRequest request) {
        String externalUserId = request == null ? null : request.externalUserId();
        if (externalUserId == null || externalUserId.isBlank()) {
            throw new SecurityException("当前用户 ID 缺失");
        }
        return externalUserId.trim();
    }
}
