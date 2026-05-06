package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.domain.auth.UserContext;

/**
 * 权限检查器。
 *
 * <p>当前阶段 UserContext 只承载身份信息，不做 scope 权限控制。保留该扩展点是为了后续接入企业权限框架时，
 * 可以在应用层统一增加租户、角色、数据范围和 Agent 访问策略，而不用修改 Controller 或编排主链路。</p>
 */
public class PermissionChecker {
    public void checkChatPermission(UserContext user) {
        // 暂不做权限拦截；身份完整性由 AuthContextProvider 负责保证。
    }
}
