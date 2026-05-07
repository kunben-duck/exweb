package com.huawei.finance.front.one.infrastructure.security;

import com.huawei.finance.front.one.application.integration.identity.AuthContextProvider;
import com.huawei.finance.front.one.domain.auth.UserContext;
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
    private final String tenantId;
    private final String userId;
    private final String username;

    public ApplicationAuthContextProvider(@Value("${financeex.security.dev.tenant-id:}") String tenantId,
                                          @Value("${financeex.security.dev.user-id:}") String externalUserId,
                                          @Value("${financeex.security.dev.username:}") String username) {
        this.tenantId = tenantId;
        this.userId = externalUserId;
        this.username = username;
    }

    @Override
    public UserContext resolve() {
        String resolvedTenant = requireText(tenantId, "当前租户 ID 缺失");
        String resolvedUserId = requireText(userId, "当前用户 ID 缺失");
        String resolvedUsername = requireText(username, "当前用户名缺失");
        return new UserContext(resolvedTenant, resolvedUserId, resolvedUsername);
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new SecurityException(message);
        }
        return value.trim();
    }
}
