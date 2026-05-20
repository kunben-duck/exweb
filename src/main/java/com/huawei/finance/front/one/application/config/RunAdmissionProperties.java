package com.huawei.finance.front.one.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * run 创建入口的本机准入控制配置。
 *
 * <p>该配置用于限制用户创建 run 的速率以及租户在当前 JVM 上的并发 run 数。
 * 同一会话的 active run 互斥由 ChatRun 事实源和 Redis active key 另行保证。</p>
 */
@Component
@ConfigurationProperties(prefix = "financeex.run-admission")
public class RunAdmissionProperties {
    /** 是否启用本机 run 准入控制。 */
    private boolean enabled = true;
    /** 单用户每分钟允许创建的 run 数量。 */
    private int maxRunsPerUserPerMinute = 60;
    /** 单租户在当前 JVM 上允许并发执行的 run 数量。 */
    private int maxConcurrentRunsPerTenant = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRunsPerUserPerMinute() {
        return maxRunsPerUserPerMinute;
    }

    public void setMaxRunsPerUserPerMinute(int maxRunsPerUserPerMinute) {
        this.maxRunsPerUserPerMinute = maxRunsPerUserPerMinute;
    }

    public int getMaxConcurrentRunsPerTenant() {
        return maxConcurrentRunsPerTenant;
    }

    public void setMaxConcurrentRunsPerTenant(int maxConcurrentRunsPerTenant) {
        this.maxConcurrentRunsPerTenant = maxConcurrentRunsPerTenant;
    }

    public int normalizedMaxRunsPerUserPerMinute() {
        return Math.max(1, maxRunsPerUserPerMinute);
    }

    public int normalizedMaxConcurrentRunsPerTenant() {
        return Math.max(1, maxConcurrentRunsPerTenant);
    }
}
