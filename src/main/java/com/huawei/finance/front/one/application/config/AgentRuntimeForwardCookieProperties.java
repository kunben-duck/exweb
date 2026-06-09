package com.huawei.finance.front.one.application.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 下游 Agent Cookie 请求头透传配置。
 *
 * <p>Cookie 透传只用于 FinanceEXChatService 到可信下游 Agent 的出站调用。当前上线版本包括
 * Relay streamable HTTP 和前端显式选择的 legacy skill。{@code allowedAdapters} 只约束
 * Relay Runtime adapter；legacy skill 由显式路由和老 Agent 配置共同限定。</p>
 */
@ConfigurationProperties(prefix = "financeex.agent-runtime.forward-cookie")
public class AgentRuntimeForwardCookieProperties {
    /** 是否启用入口 Cookie 到可信下游 Agent 的透传。 */
    private boolean enabled = true;
    /** 单个 Cookie 请求头允许透传的最大字符数，默认 8 KiB。 */
    private int maxLength = 8192;
    /** 允许接收 Cookie 的 Relay Runtime API adapter 名称列表；显式技能 legacy Agent 不使用该列表。 */
    private List<String> allowedAdapters = new ArrayList<>(List.of("relay-stream-http"));

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

    public List<String> getAllowedAdapters() {
        return allowedAdapters;
    }

    public void setAllowedAdapters(List<String> allowedAdapters) {
        this.allowedAdapters = allowedAdapters == null ? List.of() : List.copyOf(allowedAdapters);
    }

    /**
     * @return 归一化后的最大长度；配置为 0 表示禁止非空 Cookie 通过。
     */
    public int normalizedMaxLength() {
        return Math.max(0, maxLength);
    }

    /**
     * 判断某个 Runtime adapter 是否可以接收入口 Cookie。
     *
     * @param adapterName Runtime API adapter 名称。
     * @return true 表示可以向该 adapter 的出站请求头设置 Cookie。
     */
    public boolean isAdapterAllowed(String adapterName) {
        if (!enabled || adapterName == null || adapterName.isBlank()) {
            return false;
        }
        String normalized = normalize(adapterName);
        return allowedAdapters.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(this::normalize)
                .anyMatch(normalized::equals);
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
