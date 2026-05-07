package com.huawei.finance.front.one.domain.auth;

/**
 * 当前调用方身份信息。
 *
 * <p>当前阶段只承载用户身份，不表达权限范围。权限、角色、数据域等控制后续接入企业权限框架时，
 * 由专门的权限上下文或策略服务补充，避免把临时 scope 模型固化到领域层。</p>
 *
 * @param tenantId 当前租户标识，必须由应用身份上下文解析。
 * @param userId 当前用户标识，必须由应用身份上下文解析。
 * @param username 当前用户展示名或登录名。
 */
public record UserContext(
        String tenantId,
        String userId,
        String username
) {}
