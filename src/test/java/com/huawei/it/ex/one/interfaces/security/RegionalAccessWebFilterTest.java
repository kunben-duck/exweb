package com.huawei.it.ex.one.interfaces.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.application.config.RegionalAccessProperties;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessCacheKey;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDecision;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDecisionCache;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDictionarySnapshot;
import com.huawei.it.ex.one.application.integration.security.RegionalLocationResult;
import com.huawei.it.ex.one.application.service.security.RegionalAccessAuthorizer;
import com.huawei.it.ex.one.domain.auth.UserContext;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class RegionalAccessWebFilterTest {

    @Test
    void blockedRequestReturns451WithoutInvokingChain() {
        AtomicInteger chainCalls = new AtomicInteger();
        RegionalAccessProperties properties = new RegionalAccessProperties();
        RegionalAccessAuthorizer authorizer = new RegionalAccessAuthorizer(
                () -> new RegionalAccessDictionarySnapshot(Set.of(), Set.of("France")),
                (user, employeeNumber) -> Mono.just(RegionalLocationResult.found("France")),
                (user, ip) -> Mono.just(RegionalLocationResult.found("China")),
                noCache(), properties);
        RegionalAccessWebFilter filter = new RegionalAccessWebFilter(
                this::user, authorizer, new TrustedClientIpResolver(), properties, objectMapper());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/v1/chat/runs")
                .header("X-Real-IP", "203.0.113.30")
                .build());

        filter.filter(exchange, ignored -> {
            chainCalls.incrementAndGet();
            return Mono.empty();
        }).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(451);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"SERVICE_REGION_RESTRICTED\"")
                .contains("根据服务可用地区政策");
        assertThat(chainCalls).hasValue(0);
    }

    @Test
    void missingIdentityReturnsExistingUnauthorizedEnvelope() {
        AtomicInteger chainCalls = new AtomicInteger();
        RegionalAccessProperties properties = new RegionalAccessProperties();
        RegionalAccessWebFilter filter = new RegionalAccessWebFilter(
                () -> {
                    throw new SecurityException("当前用户身份缺失");
                },
                new RegionalAccessAuthorizer(RegionalAccessDictionarySnapshot::empty,
                        (user, employeeNumber) -> Mono.just(RegionalLocationResult.unavailable()),
                        (user, ip) -> Mono.just(RegionalLocationResult.unavailable()), noCache(), properties),
                new TrustedClientIpResolver(), properties, objectMapper());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/chat/sessions").build());

        filter.filter(exchange, ignored -> {
            chainCalls.incrementAndGet();
            return Mono.empty();
        }).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"AUTH_CONTEXT_MISSING\"");
        assertThat(chainCalls).hasValue(0);
    }

    @Test
    void websocketUpgradePathSkipsRegionalAuthorization() {
        AtomicInteger authCalls = new AtomicInteger();
        AtomicInteger chainCalls = new AtomicInteger();
        RegionalAccessProperties properties = new RegionalAccessProperties();
        RegionalAccessWebFilter filter = new RegionalAccessWebFilter(
                () -> {
                    authCalls.incrementAndGet();
                    return user();
                },
                new RegionalAccessAuthorizer(
                        () -> new RegionalAccessDictionarySnapshot(Set.of(), Set.of("France")),
                        (user, employeeNumber) -> Mono.just(RegionalLocationResult.found("France")),
                        (user, ip) -> Mono.just(RegionalLocationResult.found("France")),
                        noCache(), properties),
                new TrustedClientIpResolver(), properties, objectMapper());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/chat/ws").build());

        filter.filter(exchange, ignored -> {
            chainCalls.incrementAndGet();
            return Mono.empty();
        }).block();

        assertThat(authCalls).hasValue(0);
        assertThat(chainCalls).hasValue(1);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
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

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
