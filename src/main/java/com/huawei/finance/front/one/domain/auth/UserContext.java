package com.huawei.finance.front.one.domain.auth;

/**
 * 当前调用方身份信息。
 *
 * <p>当前阶段只承载用户身份，不表达权限范围。权限、角色、数据域等控制后续接入企业权限框架时，
 * 由专门的权限上下文或策略服务补充，避免把临时 scope 模型固化到领域层。</p>
 */
public record UserContext(
        String tenantId,
        String userId,
        String username
) {}
