package com.huawei.it.ex.one.interfaces.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.RegionalAccessProperties;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessCacheKey;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDecision;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDecisionCache;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDictionarySnapshot;
import com.huawei.it.ex.one.application.integration.security.RegionalLocationResult;
import com.huawei.it.ex.one.application.service.security.RegionalAccessAuthorizer;
import com.huawei.it.ex.one.application.service.security.RegionalAccessDeniedException;
import com.huawei.it.ex.one.domain.auth.UserContext;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Mono;

class RegionalAccessInterceptorTest {

    @Test
    void allowedInitialDispatchIsMarkedAndNotCheckedAgain() {
        AtomicInteger authCalls = new AtomicInteger();
        AtomicInteger employeeCalls = new AtomicInteger();
        RegionalAccessProperties properties = new RegionalAccessProperties();
        RegionalAccessAuthorizer authorizer = new RegionalAccessAuthorizer(
                () -> new RegionalAccessDictionarySnapshot(Set.of(), Set.of("France")),
                (user, employeeNumber) -> {
                    employeeCalls.incrementAndGet();
                    return Mono.just(RegionalLocationResult.found("China"));
                },
                (user, ip) -> Mono.just(RegionalLocationResult.notApplicable()),
                noCache(),
                properties
        );
        RegionalAccessInterceptor interceptor = new RegionalAccessInterceptor(
                () -> {
                    authCalls.incrementAndGet();
                    return user();
                },
                authorizer,
                new TrustedClientIpResolver(),
                properties
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/runs");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();

        assertThat(authCalls).hasValue(1);
        assertThat(employeeCalls).hasValue(1);
        assertThat(request.getAttribute(RegionalAccessInterceptor.CHECKED_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void euDecisionStopsBeforeController() {
        RegionalAccessProperties properties = new RegionalAccessProperties();
        RegionalAccessAuthorizer authorizer = new RegionalAccessAuthorizer(
                () -> new RegionalAccessDictionarySnapshot(Set.of(), Set.of("France")),
                (user, employeeNumber) -> Mono.just(RegionalLocationResult.found("France")),
                (user, ip) -> Mono.just(RegionalLocationResult.notApplicable()),
                noCache(),
                properties
        );
        RegionalAccessInterceptor interceptor = new RegionalAccessInterceptor(
                this::user, authorizer, new TrustedClientIpResolver(), properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/runs");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(RegionalAccessDeniedException.class)
                .hasMessage(RegionalAccessDeniedException.DEFAULT_MESSAGE);
        assertThat(request.getAttribute(RegionalAccessInterceptor.CHECKED_ATTRIBUTE)).isNull();
    }

    @Test
    void optionsRequestDoesNotResolveIdentity() {
        AtomicInteger authCalls = new AtomicInteger();
        RegionalAccessProperties properties = new RegionalAccessProperties();
        RegionalAccessInterceptor interceptor = new RegionalAccessInterceptor(
                () -> {
                    authCalls.incrementAndGet();
                    return user();
                },
                new RegionalAccessAuthorizer(RegionalAccessDictionarySnapshot::empty,
                        (user, employeeNumber) -> Mono.just(RegionalLocationResult.unavailable()),
                        (user, ip) -> Mono.just(RegionalLocationResult.unavailable()), noCache(), properties),
                new TrustedClientIpResolver(), properties);

        boolean accepted = interceptor.preHandle(
                new MockHttpServletRequest("OPTIONS", "/v1/chat/runs"),
                new MockHttpServletResponse(), new Object());

        assertThat(accepted).isTrue();
        assertThat(authCalls).hasValue(0);
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One", "account1", "emp-001",
                "用户一", "EMPLOYEE", "uuid1", null, "User One", "用户一", 1001L);
    }

    private RegionalAccessDecisionCache noCache() {
        return new RegionalAccessDecisionCache() {
            @Override
            public Optional<RegionalAccessDecision> get(RegionalAccessCacheKey key) {
                return Optional.empty();
            }

            @Override
            public void put(RegionalAccessCacheKey key, RegionalAccessDecision decision) {
                // Deliberately empty for request-boundary tests.
            }
        };
    }
}
