package com.huawei.it.ex.one.application.integration.security;

import com.huawei.it.ex.one.domain.auth.UserContext;
import reactor.core.publisher.Mono;

/**
 * Resolves a trusted gateway-provided client IP to a country.
 */
public interface IpLocationProvider {
    Mono<RegionalLocationResult> findCountry(UserContext user, String ipAddress);
}
