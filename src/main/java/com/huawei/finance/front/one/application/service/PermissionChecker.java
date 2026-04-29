package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.domain.auth.UserContext;

/**
 * 权限检查器。
 *
 * <p>当前只做 scope 级校验；后续可扩展租户、角色、数据范围和 Agent 访问策略。</p>
 */
public class PermissionChecker {
    public void checkChatPermission(UserContext user) {
        // 聊天入口要求基础聊天权限。
        if (!user.hasScope("finance:chat")) throw new SecurityException("缺少 finance:chat 权限");
    }
}
