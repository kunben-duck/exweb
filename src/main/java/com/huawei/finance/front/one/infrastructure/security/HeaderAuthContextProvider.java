package com.huawei.finance.front.one.infrastructure.security;

import com.huawei.finance.front.one.application.gateway.AuthContextProvider;
import com.huawei.finance.front.one.application.gateway.UserIdResolveRequest;
import com.huawei.finance.front.one.application.gateway.UserIdResolver;
import com.huawei.finance.front.one.domain.auth.UserContext;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 开发态鉴权上下文实现。
 *
 * <p>当前只从接口层传入的 tenant/user 构造 UserContext；生产环境应替换为真实登录态或网关鉴权。</p>
 */
@Component
public class HeaderAuthContextProvider implements AuthContextProvider {
    private final UserIdResolver userIdResolver;

    public HeaderAuthContextProvider(UserIdResolver userIdResolver) {
        this.userIdResolver = userIdResolver;
    }

    @Override
    public UserContext resolve(String tenantId, String userId) {
        String safeTenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        String safeUser = userIdResolver.resolveUserId(new UserIdResolveRequest(safeTenant, userId, Map.of("source", "front")));
        // 开发环境用系统属性模拟权限范围，默认包含通配符便于本地调试。
        Set<String> scopes = Arrays.stream(System.getProperty("financeex.dev.scopes", "finance:chat,finance:tool:read,*").split(","))
                .map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toSet());
        return new UserContext(safeTenant, safeUser, safeUser, scopes);
    }
}
