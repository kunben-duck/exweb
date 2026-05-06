package com.huawei.finance.front.one.application.integration.identity;

import java.util.Map;

/**
 * 用户身份解析请求。
 *
 * <p>request 只表达“外部身份体系已经解析出的候选身份信息”，不再承载前端透传的 userId。
 * 生产实现可以把 SSO、网关鉴权、企业权限 SDK 或服务端 Session 中的属性放到 attributes。</p>
 */
public record UserIdResolveRequest(
        String tenantId,
        String externalUserId,
        Map<String, Object> attributes
) {}
