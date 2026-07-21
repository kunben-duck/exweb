package com.huawei.it.ex.one.application.service.security;

import com.huawei.it.ex.one.application.config.RegionalAccessProperties;
import com.huawei.it.ex.one.application.integration.security.EmployeeLocationProvider;
import com.huawei.it.ex.one.application.integration.security.IpLocationProvider;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessCacheKey;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDecision;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDecisionCache;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDictionaryProvider;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDictionarySnapshot;
import com.huawei.it.ex.one.application.integration.security.RegionalLocationResult;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 在业务处理前执行员工白名单和可信 HR/IP 归属地准入策略。
 */
@Service
public class RegionalAccessAuthorizer {
    private static final AppLogger log = AppLoggerFactory.getLogger(RegionalAccessAuthorizer.class);

    private final RegionalAccessDictionaryProvider dictionaryProvider;
    private final EmployeeLocationProvider employeeLocations;
    private final IpLocationProvider ipLocations;
    private final RegionalAccessDecisionCache cache;
    private final RegionalAccessProperties properties;
    private final Semaphore lookupPermits;

    public RegionalAccessAuthorizer(RegionalAccessDictionaryProvider dictionaryProvider,
                                    EmployeeLocationProvider employeeLocations,
                                    IpLocationProvider ipLocations,
                                    RegionalAccessDecisionCache cache,
                                    RegionalAccessProperties properties) {
        this.dictionaryProvider = dictionaryProvider;
        this.employeeLocations = employeeLocations;
        this.ipLocations = ipLocations;
        this.cache = cache;
        this.properties = properties;
        this.lookupPermits = new Semaphore(properties.normalizedMaxConcurrentLookups());
    }

    public Mono<RegionalAccessDecision> authorize(UserContext user, String normalizedIp) {
        if (!properties.isEnabled()) {
            return Mono.just(RegionalAccessDecision.ALLOW);
        }
        if (user == null) {
            return Mono.error(new SecurityException("当前用户身份缺失"));
        }
        String employeeNumber = normalizeIdentity(user.employeeNumber());
        RegionalAccessDictionarySnapshot snapshot = safeSnapshot();
        if (!employeeNumber.isEmpty() && snapshot.employeeWhitelist().contains(employeeNumber)) {
            return Mono.just(RegionalAccessDecision.ALLOW);
        }
        Set<String> euCountries = snapshot.euCountryNames();
        if (euCountries.isEmpty()) {
            return Mono.just(RegionalAccessDecision.ALLOW);
        }

        RegionalAccessCacheKey cacheKey = new RegionalAccessCacheKey(
                user.ownerUserId(), employeeNumber, normalizeIdentity(normalizedIp));
        return cachedDecision(cacheKey)
                .map(Mono::just)
                .orElseGet(() -> lookup(user, employeeNumber, cacheKey.ipAddress(), euCountries, cacheKey));
    }

    private Mono<RegionalAccessDecision> lookup(UserContext user, String employeeNumber, String ipAddress,
                                                Set<String> euCountries, RegionalAccessCacheKey cacheKey) {
        if (!lookupPermits.tryAcquire()) {
            log.warn("Regional access lookup concurrency limit reached; allowing request without caching");
            return Mono.just(RegionalAccessDecision.ALLOW);
        }
        Mono<RegionalLocationResult> employeeResult = employeeNumber.isEmpty()
                ? Mono.just(RegionalLocationResult.notApplicable())
                : guardedLookup(Mono.defer(() -> employeeLocations.findCountry(user, employeeNumber)), "hr");
        Mono<RegionalLocationResult> ipResult = ipAddress.isEmpty()
                ? Mono.just(RegionalLocationResult.notApplicable())
                : guardedLookup(Mono.defer(() -> ipLocations.findCountry(user, ipAddress)), "ip");

        return Mono.zip(employeeResult, ipResult)
                .map(results -> decide(results.getT1(), results.getT2(), euCountries,
                        !employeeNumber.isEmpty(), !ipAddress.isEmpty()))
                .doOnNext(outcome -> cacheDecision(cacheKey, outcome))
                .map(LookupOutcome::decision)
                .doFinally(ignored -> lookupPermits.release());
    }

    private Optional<RegionalAccessDecision> cachedDecision(RegionalAccessCacheKey cacheKey) {
        try {
            return cache.get(cacheKey);
        } catch (RuntimeException ex) {
            log.warn("Regional access cache read failed; continuing with location lookup, exceptionClass={}",
                    ex.getClass().getName());
            return Optional.empty();
        }
    }

    private void cacheDecision(RegionalAccessCacheKey cacheKey, LookupOutcome outcome) {
        if (!outcome.cacheable()) {
            return;
        }
        try {
            cache.put(cacheKey, outcome.decision());
        } catch (RuntimeException ex) {
            log.warn("Regional access cache write failed; keeping the current decision, exceptionClass={}",
                    ex.getClass().getName());
        }
    }

    private Mono<RegionalLocationResult> guardedLookup(Mono<RegionalLocationResult> lookup, String operation) {
        return lookup
                .switchIfEmpty(Mono.just(RegionalLocationResult.unavailable()))
                .timeout(properties.normalizedLookupTimeout())
                .doOnError(error -> log.warn(
                        "Regional {} location lookup failed; allowing request without caching, exceptionClass={}",
                        operation, error.getClass().getName()))
                .onErrorReturn(RegionalLocationResult.unavailable());
    }

    private LookupOutcome decide(RegionalLocationResult employee, RegionalLocationResult ip,
                                 Set<String> euCountries, boolean employeeApplicable, boolean ipApplicable) {
        if (isEuCountry(employee, euCountries) || isEuCountry(ip, euCountries)) {
            return new LookupOutcome(RegionalAccessDecision.BLOCK, true);
        }
        boolean anyApplicable = employeeApplicable || ipApplicable;
        boolean allApplicableSucceeded = (!employeeApplicable || employee.found())
                && (!ipApplicable || ip.found());
        return new LookupOutcome(RegionalAccessDecision.ALLOW, anyApplicable && allApplicableSucceeded);
    }

    private boolean isEuCountry(RegionalLocationResult result, Set<String> euCountries) {
        return result != null && result.found() && euCountries.contains(normalizeCountry(result.countryName()));
    }

    private RegionalAccessDictionarySnapshot safeSnapshot() {
        try {
            RegionalAccessDictionarySnapshot snapshot = dictionaryProvider.currentSnapshot();
            return snapshot == null ? RegionalAccessDictionarySnapshot.empty() : snapshot;
        } catch (RuntimeException ex) {
            log.warn("Regional access dictionary lookup failed; allowing request, exceptionClass={}",
                    ex.getClass().getName());
            return RegionalAccessDictionarySnapshot.empty();
        }
    }

    private String normalizeIdentity(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeCountry(String value) {
        return normalizeIdentity(value);
    }

    private record LookupOutcome(RegionalAccessDecision decision, boolean cacheable) {
    }
}
