package com.huawei.it.ex.one.application.service.runtime;

import java.time.Duration;
import java.time.Instant;

/**
 * RuntimeBinding 业务过期策略。
 *
 * <p>未配置、零值或负值都表示不过期。Relay session 一旦由下游确认建立，也不再使用业务 TTL。</p>
 */
public final class RuntimeBindingExpirationPolicy {
    private RuntimeBindingExpirationPolicy() {
    }

    public static Duration normalize(Duration ttl) {
        return ttl == null ? Duration.ZERO : ttl;
    }

    public static Instant expiresAt(Duration ttl, boolean relaySessionEstablished) {
        Duration normalized = normalize(ttl);
        if (relaySessionEstablished || normalized.isZero() || normalized.isNegative()) {
            return null;
        }
        return Instant.now().plus(normalized);
    }
}
