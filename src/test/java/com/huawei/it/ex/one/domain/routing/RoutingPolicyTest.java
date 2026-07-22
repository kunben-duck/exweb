package com.huawei.it.ex.one.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;
import com.huawei.it.ex.one.domain.usecase.UseCaseMatchResult;

import org.junit.jupiter.api.Test;

import java.util.Map;

class RoutingPolicyTest {
    private final RoutingPolicy policy = new RoutingPolicy(0.85);

    @Test
    void routesMatchedUseCaseToDomainAgent() {
        RouteTarget target = policy.decideFromUseCase(new UseCaseMatchResult(true, 0.9, "finance.office.agent", "hit", Map.of(), Map.of()));

        assertThat(target.type()).isEqualTo(RouteType.DOMAIN_AGENT);
        assertThat(target.selectedAgentCode()).isEqualTo("finance.office.agent");
        assertThat(target.routeSource()).isEqualTo("use-case-library");
    }

    @Test
    void routesSimpleIntentWithDomainAgentToDomainAgent() {
        IntentDecision intent = new IntentDecision("finance.office.query", "office", TaskComplexity.SIMPLE, 0.91, true,
                "finance.office.agent", Map.of(), java.util.List.of(), Map.of());

        RouteTarget target = policy.decideFromIntent(null, null, intent, null);

        assertThat(target.type()).isEqualTo(RouteType.DOMAIN_AGENT);
        assertThat(target.selectedAgentCode()).isEqualTo("finance.office.agent");
        assertThat(target.routeSource()).isEqualTo("intent-agent");
    }

    @Test
    void intentConfidenceNoLongerBlocksRouteSingleDomainAgent() {
        RoutingPolicy strictPolicy = new RoutingPolicy(0.85, 0.96);
        IntentDecision intent = new IntentDecision("finance.office.query", "office", TaskComplexity.SIMPLE, 0.95, true,
                "finance.office.agent", Map.of(), java.util.List.of(), Map.of());

        RouteTarget target = strictPolicy.decideFromIntent(null, null, intent, null);

        assertThat(target.type()).isEqualTo(RouteType.DOMAIN_AGENT);
        assertThat(target.selectedAgentCode()).isEqualTo("finance.office.agent");
    }

    @Test
    void routesComplexIntentToAgentRuntime() {
        IntentDecision intent = new IntentDecision("finance.complex", "complex", TaskComplexity.COMPLEX, 0.92, false,
                null, Map.of(), java.util.List.of(), Map.of());

        RouteTarget target = policy.decideFromIntent(null, null, intent, null);

        assertThat(target.type()).isEqualTo(RouteType.AGENT_RUNTIME);
    }

    @Test
    void routesUnsupportedIntentToSystemResponse() {
        IntentDecision intent = new IntentDecision("unsupported", "unsupported", TaskComplexity.UNSUPPORTED, 0.95, false,
                null, Map.of(), java.util.List.of(), Map.of());

        RouteTarget target = policy.decideFromIntent(null, null, intent, null);

        assertThat(target.type()).isEqualTo(RouteType.SYSTEM_RESPONSE);
    }
}
