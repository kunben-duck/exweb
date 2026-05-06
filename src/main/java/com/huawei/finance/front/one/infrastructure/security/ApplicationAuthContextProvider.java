package com.huawei.finance.front.one.infrastructure.security;

import com.huawei.finance.front.one.application.integration.identity.AuthContextProvider;
import com.huawei.finance.front.one.application.integration.identity.UserIdResolveRequest;
import com.huawei.finance.front.one.application.integration.identity.UserIdResolver;
import com.huawei.finance.front.one.domain.auth.UserContext;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 应用身份上下文实现。
 *
 * <p>当前服务还没有接入企业统一权限框架，所以这里用配置项模拟“应用上下文中的当前用户”。
 * 这个类是明确的防腐层：业务编排只依赖 AuthContextProvider，不知道身份来自本地配置、
 * 网关鉴权、SSO Token 还是企业权限 SDK。</p>
 *
 * <p>身份信息不能兜底成默认租户或匿名用户。任何缺失都会直接抛出异常，避免财经作业在错误身份下
 * 读取数据、绑定会话或执行任务。</p>
 *
 * <p>接入生产权限框架时，可以直接替换该实现，例如从 SecurityContext、网关注入的服务端上下文
 * 或内部权限 SDK 中读取 tenantId/userId/username；接口层和聊天编排代码不需要再改。</p>
 */
@Component
public class ApplicationAuthContextProvider implements AuthContextProvider {
    private final UserIdResolver userIdResolver;
    private final String tenantId;
    private final String externalUserId;
    private final String username;

    public ApplicationAuthContextProvider(UserIdResolver userIdResolver,
                                          @Value("${financeex.security.dev.tenant-id:}") String tenantId,
                                          @Value("${financeex.security.dev.user-id:}") String externalUserId,
                                          @Value("${financeex.security.dev.username:}") String username) {
        this.userIdResolver = userIdResolver;
        this.tenantId = tenantId;
        this.externalUserId = externalUserId;
        this.username = username;
    }

    @Override
    public UserContext resolve() {
        String resolvedTenant = requireText(tenantId, "当前租户 ID 缺失");
        String resolvedExternalUser = requireText(externalUserId, "当前用户 ID 缺失");
        String resolvedUsername = requireText(username, "当前用户名缺失");
        String resolvedUserId = userIdResolver.resolveUserId(new UserIdResolveRequest(
                resolvedTenant,
                resolvedExternalUser,
                Map.of("source", "application-auth-context")));
        return new UserContext(resolvedTenant, requireText(resolvedUserId, "当前用户 ID 解析结果缺失"),
                resolvedUsername);
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new SecurityException(message);
        }
        return value.trim();
    }
}
