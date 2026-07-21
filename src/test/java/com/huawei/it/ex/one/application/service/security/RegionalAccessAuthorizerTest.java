package com.huawei.it.ex.one.application.service.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.RegionalAccessProperties;
import com.huawei.it.ex.one.application.integration.security.EmployeeLocationProvider;
import com.huawei.it.ex.one.application.integration.security.IpLocationProvider;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessCacheKey;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDecision;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDecisionCache;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDictionarySnapshot;
import com.huawei.it.ex.one.application.integration.security.RegionalLocationResult;
import com.huawei.it.ex.one.domain.auth.UserContext;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class RegionalAccessAuthorizerTest {

    @Test
    void whitelistIsCheckedBeforeCacheAndRemoteProviders() {
        AtomicInteger employeeCalls = new AtomicInteger();
        AtomicInteger ipCalls = new AtomicInteger();
        TrackingCache cache = new TrackingCache();
        RegionalAccessAuthorizer authorizer = authorizer(
                new RegionalAccessDictionarySnapshot(Set.of(" EMP-001 "), Set.of("France")),
                (user, employeeNumber) -> counted(employeeCalls, RegionalLocationResult.found("France")),
                (user, ip) -> counted(ipCalls, RegionalLocationResult.found("France")),
                cache,
                properties()
        );

        RegionalAccessDecision decision = authorizer.authorize(user("emp-001"), "203.0.113.10").block();

        assertThat(decision).isEqualTo(RegionalAccessDecision.ALLOW);
        assertThat(cache.getCalls()).isZero();
        assertThat(employeeCalls).hasValue(0);
        assertThat(ipCalls).hasValue(0);
    }

    @Test
    void anyEuResultBlocksAndCachesDecision() {
        AtomicInteger employeeCalls = new AtomicInteger();
        AtomicInteger ipCalls = new AtomicInteger();
        TrackingCache cache = new TrackingCache();
        RegionalAccessAuthorizer authorizer = authorizer(
                new RegionalAccessDictionarySnapshot(Set.of(), Set.of("FRANCE", "Germany")),
                (user, employeeNumber) -> counted(employeeCalls, RegionalLocationResult.found(" France ")),
                (user, ip) -> counted(ipCalls, RegionalLocationResult.found("China")),
                cache,
                properties()
        );

        assertThat(authorizer.authorize(user("emp-002"), "203.0.113.11").block())
                .isEqualTo(RegionalAccessDecision.BLOCK);
        assertThat(authorizer.authorize(user("emp-002"), "203.0.113.11").block())
                .isEqualTo(RegionalAccessDecision.BLOCK);

        assertThat(employeeCalls).hasValue(1);
        assertThat(ipCalls).hasValue(1);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void definitiveNonEuResultIsCached() {
        AtomicInteger employeeCalls = new AtomicInteger();
        AtomicInteger ipCalls = new AtomicInteger();
        TrackingCache cache = new TrackingCache();
        RegionalAccessAuthorizer authorizer = authorizer(
                new RegionalAccessDictionarySnapshot(Set.of(), Set.of("France")),
                (user, employeeNumber) -> counted(employeeCalls, RegionalLocationResult.found("China")),
                (user, ip) -> counted(ipCalls, RegionalLocationResult.found("Singapore")),
                cache,
                properties()
        );

        assertThat(authorizer.authorize(user("emp-003"), "203.0.113.12").block())
                .isEqualTo(RegionalAccessDecision.ALLOW);
        assertThat(authorizer.authorize(user("emp-003"), "203.0.113.12").block())
                .isEqualTo(RegionalAccessDecision.ALLOW);

        assertThat(employeeCalls).hasValue(1);
        assertThat(ipCalls).hasValue(1);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void failedLookupFailsOpenWithoutCaching() {
        AtomicInteger employeeCalls = new AtomicInteger();
        AtomicInteger ipCalls = new AtomicInteger();
        TrackingCache cache = new TrackingCache();
        RegionalAccessAuthorizer authorizer = authorizer(
                new RegionalAccessDictionarySnapshot(Set.of(), Set.of("France")),
                (user, employeeNumber) -> counted(employeeCalls, RegionalLocationResult.unavailable()),
                (user, ip) -> counted(ipCalls, RegionalLocationResult.found("China")),
                cache,
                properties()
        );

        assertThat(authorizer.authorize(user("emp-004"), "203.0.113.13").block())
                .isEqualTo(RegionalAccessDecision.ALLOW);
        assertThat(authorizer.authorize(user("emp-004"), "203.0.113.13").block())
                .isEqualTo(RegionalAccessDecision.ALLOW);

        assertThat(employeeCalls).hasValue(2);
        assertThat(ipCalls).hasValue(2);
        assertThat(cache.size()).isZero();
    }

    @Test
    void timedOutLookupFailsOpenWithoutCaching() {
        TrackingCache cache = new TrackingCache();
        RegionalAccessProperties properties = properties();
        properties.setLookupTimeout(Duration.ofMillis(20));
        RegionalAccessAuthorizer authorizer = authorizer(
                new RegionalAccessDictionarySnapshot(Set.of(), Set.of("France")),
                (user, employeeNumber) -> Mono.never(),
                (user, ip) -> Mono.just(RegionalLocationResult.found("China")),
                cache,
                properties
        );

        assertThat(authorizer.authorize(user("emp-005"), "203.0.113.14").block(Duration.ofSeconds(1)))
                .isEqualTo(RegionalAccessDecision.ALLOW);
        assertThat(cache.size()).isZero();
    }

    @Test
    void emptyEuDictionarySkipsCacheAndRemoteProviders() {
        AtomicInteger employeeCalls = new AtomicInteger();
        TrackingCache cache = new TrackingCache();
        RegionalAccessAuthorizer authorizer = authorizer(
                RegionalAccessDictionarySnapshot.empty(),
                (user, employeeNumber) -> counted(employeeCalls, RegionalLocationResult.found("France")),
                (user, ip) -> Mono.just(RegionalLocationResult.found("France")),
                cache,
                properties()
        );

        assertThat(authorizer.authorize(user("emp-006"), "203.0.113.15").block())
                .isEqualTo(RegionalAccessDecision.ALLOW);
        assertThat(employeeCalls).hasValue(0);
        assertThat(cache.getCalls()).isZero();
    }

    @Test
    void exhaustedLookupPermitFailsOpenWithoutWaiting() throws InterruptedException {
        RegionalAccessProperties properties = properties();
        properties.setMaxConcurrentLookups(1);
        Sinks.One<RegionalLocationResult> pendingLookup = Sinks.one();
        AtomicInteger employeeCalls = new AtomicInteger();
        AtomicReference<RegionalAccessDecision> firstDecision = new AtomicReference<>();
        CountDownLatch firstCompleted = new CountDownLatch(1);
        RegionalAccessAuthorizer authorizer = authorizer(
                new RegionalAccessDictionarySnapshot(Set.of(), Set.of("France")),
                (user, employeeNumber) -> {
                    employeeCalls.incrementAndGet();
                    return pendingLookup.asMono();
                },
                (user, ip) -> Mono.just(RegionalLocationResult.notApplicable()),
                new TrackingCache(),
                properties
        );
        authorizer.authorize(user("emp-007"), "")
                .subscribe(firstDecision::set, ignored -> firstCompleted.countDown(), firstCompleted::countDown);

        RegionalAccessDecision concurrentDecision = authorizer.authorize(user("emp-008"), "").block();

        assertThat(concurrentDecision).isEqualTo(RegionalAccessDecision.ALLOW);
        assertThat(employeeCalls).hasValue(1);
        pendingLookup.tryEmitValue(RegionalLocationResult.found("China"));
        assertThat(firstCompleted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(firstDecision.get()).isEqualTo(RegionalAccessDecision.ALLOW);
    }

    @Test
    void cacheFailureCannotOverrideConfirmedEuBlock() {
        RegionalAccessDecisionCache failingCache = new RegionalAccessDecisionCache() {
            @Override
            public Optional<RegionalAccessDecision> get(RegionalAccessCacheKey key) {
                throw new IllegalStateException("cache read failed");
            }

            @Override
            public void put(RegionalAccessCacheKey key, RegionalAccessDecision decision) {
                throw new IllegalStateException("cache write failed");
            }
        };
        RegionalAccessAuthorizer authorizer = authorizer(
                new RegionalAccessDictionarySnapshot(Set.of(), Set.of("France")),
                (user, employeeNumber) -> Mono.just(RegionalLocationResult.found("France")),
                (user, ip) -> Mono.just(RegionalLocationResult.found("China")),
                failingCache,
                properties()
        );

        assertThat(authorizer.authorize(user("emp-009"), "203.0.113.19").block())
                .isEqualTo(RegionalAccessDecision.BLOCK);
    }

    private RegionalAccessAuthorizer authorizer(RegionalAccessDictionarySnapshot snapshot,
                                                EmployeeLocationProvider employeeLocations,
                                                IpLocationProvider ipLocations,
                                                RegionalAccessDecisionCache cache,
                                                RegionalAccessProperties properties) {
        return new RegionalAccessAuthorizer(() -> snapshot, employeeLocations, ipLocations, cache, properties);
    }

    private Mono<RegionalLocationResult> counted(AtomicInteger calls, RegionalLocationResult result) {
        calls.incrementAndGet();
        return Mono.just(result);
    }

    private RegionalAccessProperties properties() {
        RegionalAccessProperties properties = new RegionalAccessProperties();
        properties.setLookupTimeout(Duration.ofSeconds(1));
        return properties;
    }

    private UserContext user(String employeeNumber) {
        return new UserContext("tenant1", "user1", "User One", "account1", employeeNumber,
                "用户一", "EMPLOYEE", "uuid1", null, "User One", "用户一", 1001L);
    }

    private static final class TrackingCache implements RegionalAccessDecisionCache {
        private final Map<RegionalAccessCacheKey, RegionalAccessDecision> values = new ConcurrentHashMap<>();
        private final AtomicInteger getCalls = new AtomicInteger();

        @Override
        public Optional<RegionalAccessDecision> get(RegionalAccessCacheKey key) {
            getCalls.incrementAndGet();
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void put(RegionalAccessCacheKey key, RegionalAccessDecision decision) {
            values.put(key, decision);
        }

        int getCalls() {
            return getCalls.get();
        }

        int size() {
            return values.size();
        }
    }
}
