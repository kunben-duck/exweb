/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.agent;

import java.time.Instant;

/**
 * 单次下游调用可透传的 HTTP 请求头快照。
 *
 * <p>该对象只在一次请求触发的内存调用链中传递，用于把请求入口捕获到的企业鉴权 Cookie
 * 透传给可信的 Relay Runtime adapter、DomainAgent adapter、DomainAgent 技能配置 Provider 或文档
 * provider upload adapter。它不应写入 metadata、数据库、事件 payload 或日志。</p>
 *
 * @param cookieHeader 原始 HTTP {@code Cookie} 请求头；为空表示不透传。
 * @param createdAt 请求头快照创建时间，用于诊断内存对象生命周期，不作为业务时间事实。
 */
public record RuntimeForwardHeaders(String cookieHeader, Instant createdAt) {
    private static final RuntimeForwardHeaders EMPTY = new RuntimeForwardHeaders(null, Instant.EPOCH);

    /**
     * @return 空转发头快照，表示本轮 Runtime 调用不携带任何入口请求头。
     */
    public static RuntimeForwardHeaders empty() {
        return EMPTY;
    }

    /**
     * 根据入口 Cookie 请求头创建运行期快照。
     *
     * @param cookieHeader 原始 {@code Cookie} 请求头。
     * @param maxLength 允许透传的最大 Cookie 头长度。
     * @return 运行期转发头快照；空 Cookie 会返回 {@link #empty()}。
     */
    public static RuntimeForwardHeaders fromCookieHeader(String cookieHeader, int maxLength) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return empty();
        }
        if (maxLength < 0) {
            throw new IllegalArgumentException("Cookie 请求头最大长度配置不能为负数");
        }
        if (cookieHeader.length() > maxLength) {
            throw new IllegalArgumentException("Cookie 请求头超过最大允许长度: " + maxLength);
        }
        return new RuntimeForwardHeaders(cookieHeader, Instant.now());
    }

    /**
     * @return 当前快照是否包含可透传 Cookie。
     */
    public boolean hasCookie() {
        return cookieHeader != null && !cookieHeader.isBlank();
    }

    /** 避免记录该内存对象时由 record 默认实现输出原始 Cookie。 */
    @Override
    public String toString() {
        return "RuntimeForwardHeaders[hasCookie=" + hasCookie() + ", createdAt=" + createdAt + "]";
    }
}
