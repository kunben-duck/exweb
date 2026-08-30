/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * RuntimeBinding Redis 热缓存配置。
 */
@ConfigurationProperties(prefix = "financeex.runtime-binding")
public class RuntimeBindingProperties {
    /** RuntimeBinding Redis 逻辑 key 前缀，必须以 fin_ex 开头；运行时会自动插入环境段。 */
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
