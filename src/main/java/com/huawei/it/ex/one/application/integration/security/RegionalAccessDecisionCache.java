package com.huawei.it.ex.one.application.integration.security;

import java.util.Optional;

/**
 * Local cache used to avoid repeated HR and IP location lookups.
 */
public interface RegionalAccessDecisionCache {
    Optional<RegionalAccessDecision> get(RegionalAccessCacheKey key);

    void put(RegionalAccessCacheKey key, RegionalAccessDecision decision);
}
