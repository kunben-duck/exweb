package com.huawei.finance.front.one.application.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AgentRuntime Cookie 请求头透传配置。
 *
 * <p>Cookie 透传只用于 FinanceEXChatService 到可信 Relay Runtime 的出站调用。默认允许
 * {@code relay-stream-http} 与 {@code relay-websocket} 两个内部 adapter；DeepSeek/OpenAI-compatible
 * 第三方替身不在允许列表中，避免把企业登录态发送到公网模型服务。</p>
 */
@ConfigurationProperties(prefix = "financeex.agent-runtime.forward-cookie")
public class AgentRuntimeForwardCookieProperties {
    /** 是否启用入口 Cookie 到 Relay Runtime 的透传。 */
    private boolean enabled = true;
    /** 单个 Cookie 请求头允许透传的最大字符数，默认 8 KiB。 */
    private int maxLength = 8192;
    /** 允许接收 Cookie 的 Runtime API adapter 名称列表。 */
    private List<String> allowedAdapters = new ArrayList<>(List.of("relay-stream-http", "relay-websocket"));

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
