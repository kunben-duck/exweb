package com.huawei.it.ex.one.infrastructure.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.huawei.it.ex.one.application.config.RegionalAccessProperties;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessCacheKey;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDecision;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDecisionCache;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Bounded process-local regional access decision cache.
 */
@Component
public class CaffeineRegionalAccessDecisionCache implements RegionalAccessDecisionCache {
    private final Cache<RegionalAccessCacheKey, RegionalAccessDecision> cache;

    public CaffeineRegionalAccessDecisionCache(RegionalAccessProperties properties) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.normalizedCacheTtl())
                .maximumSize(properties.normalizedCacheMaximumSize())
                .build();
    }

    @Override
    public Optional<RegionalAccessDecision> get(RegionalAccessCacheKey key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    @Override
    public void put(RegionalAccessCacheKey key, RegionalAccessDecision decision) {
        if (key != null && decision != null) {
            cache.put(key, decision);
        }
    }
}
