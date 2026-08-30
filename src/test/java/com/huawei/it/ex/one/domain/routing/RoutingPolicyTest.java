/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

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
        assertThat(target.invocationSkillId()).isEqualTo("finance.office.agent");
    }

    @Test
    void routesSimpleIntentWithDomainAgentToDomainAgent() {
        IntentDecision intent = new IntentDecision("finance.office.query", "office", TaskComplexity.SIMPLE, 0.91, true,
                "finance.office.agent", Map.of(), java.util.List.of(), Map.of());

        RouteTarget target = policy.decideFromIntent(null, null, intent, null);

        assertThat(target.type()).isEqualTo(RouteType.DOMAIN_AGENT);
        assertThat(target.selectedAgentCode()).isEqualTo("finance.office.agent");
        assertThat(target.routeSource()).isEqualTo("intent-agent");
        assertThat(target.invocationSkillId()).isEqualTo("finance.office.agent");
    }

    @Test
    void routesConfiguredDomainExpertIntentToRelayProfile() {
        RoutingPolicy expertPolicy = new RoutingPolicy(0.85, 0.85, "RE_");
        IntentDecision intent = new IntentDecision(
                "finance.expert", "领域专家", TaskComplexity.SIMPLE, 0.95, true,
                "RE_system-awareness", Map.of("routeAction", "ROUTE_SINGLE"), java.util.List.of(), Map.of());

        RouteTarget target = expertPolicy.decideFromIntent(null, null, intent, null);

        assertThat(target.type()).isEqualTo(RouteType.AGENT_RUNTIME);
        assertThat(target.runtimeProfile()).isEqualTo(RuntimeProfile.DOMAIN_EXPERT);
        assertThat(target.runtimeRoleName()).isEqualTo("system-awareness");
        assertThat(target.relayOutputMode()).isEqualTo(RelayOutputMode.FULL_STREAM);
        assertThat(target.selectedAgentCode()).isNull();
        assertThat(target.invocationSkillId()).isEqualTo("RE_system-awareness");
    }

    @Test
    void routesConfiguredSensitiveInformationIntentToRelayDelegate() {
        RoutingPolicy sensitivePolicy = new RoutingPolicy(
                0.85,
                0.85,
                "RE_",
                new SensitiveInformationAccessNameResolver("sensitive_information"));
        IntentDecision intent = new IntentDecision(
                "finance.sensitive", "敏感信息", TaskComplexity.SIMPLE, 0.95, true,
                "sensitive_information", Map.of("routeAction", "ROUTE_SINGLE"),
                java.util.List.of(), Map.of());

        RouteTarget target = sensitivePolicy.decideFromIntent(null, null, intent, null);

        assertThat(target.type()).isEqualTo(RouteType.AGENT_RUNTIME);
        assertThat(target.runtimeProfile()).isEqualTo(RuntimeProfile.DELEGATE);
        assertThat(target.runtimeRoleName()).isNull();
        assertThat(target.relayOutputMode()).isEqualTo(RelayOutputMode.ANSWER_STREAM_ONLY);
        assertThat(target.selectedAgentCode()).isNull();
        assertThat(target.invocationSkillId()).isEqualTo("sensitive_information");
    }

    @Test
    void sensitiveInformationExactMatchTakesPriorityOverDomainExpertPrefix() {
        RoutingPolicy sensitivePolicy = new RoutingPolicy(
                0.85,
                0.85,
                "RE_",
                new SensitiveInformationAccessNameResolver("RE_"));
        IntentDecision intent = new IntentDecision(
                "finance.sensitive", "敏感信息", TaskComplexity.SIMPLE, 0.95, true,
                "RE_", Map.of("routeAction", "ROUTE_SINGLE"), java.util.List.of(), Map.of());

        RouteTarget target = sensitivePolicy.decideFromIntent(null, null, intent, null);

        assertThat(target.type()).isEqualTo(RouteType.AGENT_RUNTIME);
        assertThat(target.runtimeProfile()).isEqualTo(RuntimeProfile.DELEGATE);
        assertThat(target.runtimeRoleName()).isNull();
        assertThat(target.relayOutputMode()).isEqualTo(RelayOutputMode.ANSWER_STREAM_ONLY);
    }

    @Test
    void sensitiveInformationAccessNameMatchIsCaseSensitive() {
        RoutingPolicy sensitivePolicy = new RoutingPolicy(
                0.85,
                0.85,
                "RE_",
                new SensitiveInformationAccessNameResolver("sensitive_information"));
        IntentDecision intent = new IntentDecision(
                "finance.sensitive", "敏感信息", TaskComplexity.SIMPLE, 0.95, true,
                "SENSITIVE_INFORMATION", Map.of("routeAction", "ROUTE_SINGLE"),
                java.util.List.of(), Map.of());

        RouteTarget target = sensitivePolicy.decideFromIntent(null, null, intent, null);

        assertThat(target.type()).isEqualTo(RouteType.DOMAIN_AGENT);
        assertThat(target.selectedAgentCode()).isEqualTo("SENSITIVE_INFORMATION");
    }

    @Test
    void domainExpertAccessNameMatchIsCaseSensitive() {
        RoutingPolicy expertPolicy = new RoutingPolicy(0.85, 0.85, "RE_");
        IntentDecision intent = new IntentDecision(
                "finance.expert", "领域专家", TaskComplexity.SIMPLE, 0.95, true,
                "re_system-awareness", Map.of("routeAction", "ROUTE_SINGLE"), java.util.List.of(), Map.of());

        RouteTarget target = expertPolicy.decideFromIntent(null, null, intent, null);

        assertThat(target.type()).isEqualTo(RouteType.DOMAIN_AGENT);
        assertThat(target.selectedAgentCode()).isEqualTo("re_system-awareness");
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
        assertThat(target.invocationSkillId()).isNull();
    }

    @Test
    void recordsLegalNoMatchWithoutTaggingOtherRelayFallbacks() {
        IntentDecision noMatch = new IntentDecision(
                "no-match", "未匹配", TaskComplexity.COMPLEX, 0.0, false,
                null, Map.of("routeAction", "NO_MATCH"), java.util.List.of(), Map.of());
        IntentDecision routeMulti = new IntentDecision(
                "multi", "多意图", TaskComplexity.COMPLEX, 0.8, false,
                null, Map.of("routeAction", "ROUTE_MULTI"), java.util.List.of(), Map.of());

        assertThat(policy.decideFromIntent(null, null, noMatch, null).invocationSkillId())
                .isEqualTo("NO_MATCH");
        assertThat(policy.decideFromIntent(null, null, routeMulti, null).invocationSkillId())
                .isNull();
    }

    @Test
    void routesUnsupportedIntentToSystemResponse() {
        IntentDecision intent = new IntentDecision("unsupported", "unsupported", TaskComplexity.UNSUPPORTED, 0.95, false,
                null, Map.of(), java.util.List.of(), Map.of());

        RouteTarget target = policy.decideFromIntent(null, null, intent, null);

        assertThat(target.type()).isEqualTo(RouteType.SYSTEM_RESPONSE);
    }
}
