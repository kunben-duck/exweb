package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.tool.ToolDefinition;

/**
 * 权限检查器。
 *
 * <p>当前只做 scope 级校验；后续可扩展租户、角色、数据范围和高危操作策略。</p>
 */
public class PermissionChecker {
    public void checkChatPermission(UserContext user) {
        // 聊天入口要求基础聊天权限。
        if (!user.hasScope("finance:chat")) throw new SecurityException("缺少 finance:chat 权限");
    }
    public void checkToolVisible(UserContext user, ToolDefinition tool) {
        // 禁用工具对所有调用方不可见。
        if (!tool.enabled()) throw new SecurityException("工具已禁用: " + tool.toolCode());
    }
    public void checkToolExecutable(UserContext user, ToolDefinition tool) {
        checkToolVisible(user, tool);
        // 工具定义中的 requiredScopes 必须全部满足。
        for (String scope : tool.requiredScopes()) {
            if (!user.hasScope(scope)) throw new SecurityException("缺少工具权限: " + scope);
        }
    }
}
