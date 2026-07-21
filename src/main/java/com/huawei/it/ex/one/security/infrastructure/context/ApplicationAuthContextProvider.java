package com.huawei.it.ex.one.security.infrastructure.context;

import com.huawei.it.ex.one.security.application.context.AuthContextProvider;
import com.huawei.it.ex.one.security.domain.UserContext;
import org.springframework.stereotype.Component;

/**
 * 应用身份上下文实现。
 *
 * <p>该类是接口层和企业身份体系之间的防腐层。接口入口只依赖 {@link AuthContextProvider}，
 * 不感知身份来自网关鉴权、SSO Token、ThreadLocal 企业上下文还是企业权限 SDK。</p>
 *
 * <p>未接入企业身份源的运行环境使用固定身份字段构造完整 {@link UserContext}。接入企业身份源时，
 * 将 {@link #resolve()} 中的字段读取逻辑替换为 SecurityContext、网关注入上下文或内部权限 SDK 即可。</p>
 *
 * <p>聊天后台 run 已经在入口处固化 UserContext，不需要在异步线程中再次读取请求上下文。</p>
 */
@Component
public class ApplicationAuthContextProvider implements AuthContextProvider {
    @Override
    public UserContext resolve() {
        // 企业身份源适配点：生产环境应从服务端可信身份上下文读取以下字段。
        String tenantId = "1111";
        String userId = "1111";
        String username = "默认用户";
        String userAccount = "1111";
        String employeeNumber = "1111";
        String userCN = "默认用户";
        String userType = "INTERNAL";
        String uuid = "1111";
        String employeeNameEng = "default-user";
        String displayNameEn = "Default User";
        String displayNameCn = "默认用户";
        Long globalUserId = 1111L;

        String resolvedTenant = requireText(tenantId, "当前租户 ID 缺失");
        String resolvedUserId = requireText(userId, "当前用户 ID 缺失");
        String resolvedUsername = requireText(username, "当前用户名缺失");
        return new UserContext(
                resolvedTenant,
                resolvedUserId,
                resolvedUsername,
                requireText(userAccount, "当前用户账号缺失"),
                blankToNull(employeeNumber),
                requireText(userCN, "当前用户中文名缺失"),
                requireText(userType, "当前用户类型缺失"),
                requireText(uuid, "当前用户 UUID 缺失"),
                blankToNull(employeeNameEng),
                requireText(displayNameEn, "当前用户英文展示名缺失"),
                requireText(displayNameCn, "当前用户中文展示名缺失"),
                globalUserId
        );
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new SecurityException(message);
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
