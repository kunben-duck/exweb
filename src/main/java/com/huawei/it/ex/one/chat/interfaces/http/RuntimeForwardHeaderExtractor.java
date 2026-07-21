package com.huawei.it.ex.one.chat.interfaces.http;

import com.huawei.it.ex.one.common.http.AgentRuntimeForwardCookieProperties;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * HTTP 请求入口的下游 Agent 请求头提取器。
 *
 * <p>企业鉴权上下文通常依赖 Servlet/Filter 线程中的请求头。这里在 Controller 入口阶段
 * 一次性读取 {@code Cookie}，并转换成不可变内存快照传入后台 run；后台线程、Runtime adapter
 * 和显式技能 adapter 不再访问 HTTP 请求上下文或 ThreadLocal。</p>
 */
@Component
@EnableConfigurationProperties(AgentRuntimeForwardCookieProperties.class)
public class RuntimeForwardHeaderExtractor {
    private final AgentRuntimeForwardCookieProperties properties;

    public RuntimeForwardHeaderExtractor(AgentRuntimeForwardCookieProperties properties) {
        this.properties = properties;
    }

    /**
     * 从 HTTP Cookie 头创建 Runtime 转发快照。
     *
     * @param cookieHeader 原始 {@code Cookie} 请求头。
     * @return 本轮 run 的不可变转发头快照。
     */
    public RuntimeForwardHeaders fromCookieHeader(String cookieHeader) {
        if (!properties.isEnabled()) {
            return RuntimeForwardHeaders.empty();
        }
        return RuntimeForwardHeaders.fromCookieHeader(cookieHeader, properties.normalizedMaxLength());
    }
}
