package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;

/** 默认技能配置 Provider 的调用约束。 */
@ConfigurationProperties(prefix = "financeex.domain-agent-skill-config")
public class DomainAgentSkillConfigurationProperties {
    private String timeout = "";

    public String getTimeout() {
        return timeout;
    }

    public void setTimeout(String timeout) {
        this.timeout = timeout;
    }

    public Duration normalizedTimeout() {
        if (timeout == null || timeout.isBlank()) {
            return null;
        }
        try {
            Duration parsed = DurationStyle.detectAndParse(timeout.trim());
            return parsed.isZero() || parsed.isNegative() ? null : parsed;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
