package com.huawei.finance.front.one.application.gateway;

import java.util.Map;

/**
 * 用户身份解析请求。
 *
 * <p>当前第一版仍使用前端传入的 userId；后续可在实现层从服务端 Session、Token 或网关上下文解析真实用户。</p>
 */
public record UserIdResolveRequest(
        String tenantId,
        String frontendUserId,
        Map<String, Object> attributes
) {}
