package com.huawei.it.ex.one.common.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 下游 Agent Cookie 请求头透传配置。
 *
 * <p>Cookie 透传只用于 FinanceEXChatService 到可信下游 Agent 的出站调用，
 * 包括 Relay WebSocket 和 DomainAgent。</p>
 */
@ConfigurationProperties(prefix = "financeex.agent-runtime.forward-cookie")
public class AgentRuntimeForwardCookieProperties {
    /** 是否启用入口 Cookie 到可信下游 Agent 的透传。 */
    private boolean enabled = true;
    /** 单个 Cookie 请求头允许透传的最大字符数，默认 8 KiB。 */
    private int maxLength = 8192;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    /**
     * @return 归一化后的最大长度；配置为 0 表示禁止非空 Cookie 通过。
     */
    public int normalizedMaxLength() {
        return Math.max(0, maxLength);
    }

}
