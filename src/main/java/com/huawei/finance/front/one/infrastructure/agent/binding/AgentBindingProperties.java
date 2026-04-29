package com.huawei.finance.front.one.infrastructure.agent.binding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "financeex.agent-binding")
public class AgentBindingProperties {
    private String redisKeyPrefix = "fin_ex:agent_binding";
    private Duration redisTtl = Duration.ofDays(3);

    public String getRedisKeyPrefix() {
        return redisKeyPrefix;
    }

    public void setRedisKeyPrefix(String redisKeyPrefix) {
        this.redisKeyPrefix = redisKeyPrefix;
    }

    public Duration getRedisTtl() {
        return redisTtl;
    }

    public void setRedisTtl(Duration redisTtl) {
        this.redisTtl = redisTtl;
    }
}
