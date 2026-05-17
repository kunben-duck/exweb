package com.huawei.finance.front.one.infrastructure.runtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RuntimeBinding Redis 热缓存配置。
 */
@ConfigurationProperties(prefix = "financeex.runtime-binding")
public class RuntimeBindingProperties {
    /** RuntimeBinding Redis key 前缀，必须以 fin_ex 开头。 */
    private String redisKeyPrefix = "fin_ex:runtime_binding";
    /** RuntimeBinding Redis 热缓存 TTL。 */
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
