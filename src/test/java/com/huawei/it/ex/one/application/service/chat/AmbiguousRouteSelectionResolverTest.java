package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.routing.RelayOutputMode;
import com.huawei.it.ex.one.domain.routing.RouteType;
import com.huawei.it.ex.one.domain.routing.RuntimeProfile;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class AmbiguousRouteSelectionResolverTest {
    private final AmbiguousRouteSelectionResolver resolver =
            new AmbiguousRouteSelectionResolver("RE_");

    @Test
    void autoSelectsHighestValidConfidenceAndKeepsOriginalOrderOnTie() {
        Map<String, Object> payload = payload(List.of(
                candidate("intent-a", "技能A", "skill-a", "invalid"),
                candidate("intent-b", "技能B", "skill-b", 0.9),
                candidate("intent-c", "技能C", "skill-c", 0.9),
                candidate("intent-d", "技能D", null, 1.0)));

        AmbiguousRouteSelectionResolver.Candidate selected =
                resolver.autoSelect(payload).orElseThrow();

        assertThat(selected.skillId()).isEqualTo("skill-b");
        assertThat(selected.responseOrder()).isEqualTo(1);
        assertThat(selected.confidence()).isEqualTo(0.9);
    }

    @Test
    void selectedSkillMustExactlyMatchTrustedCandidate() {
        ChatInteractionRequest interaction = interaction(payload(List.of(
                candidate("intent-a", "技能A", "skill-a", 0.8),
                candidate("intent-b", "技能B", "skill-b", 0.7))));

        assertThat(resolver.select(interaction, "skill-b"))
                .get()
                .extracting(AmbiguousRouteSelectionResolver.Candidate::intentName)
                .isEqualTo("技能B");
        assertThat(resolver.select(interaction, "SKILL-B")).isEmpty();
        assertThat(resolver.select(interaction, "unknown")).isEmpty();
    }

    @Test
    void noValidSkillDisablesAutomaticSelection() {
        Map<String, Object> payload = payload(List.of(
                candidate("intent-a", "技能A", null, 0.9),
                candidate("intent-b", "技能B", " ", 0.8)));

        assertThat(resolver.autoSelect(payload)).isEmpty();
    }

    @Test
    void selectedCandidateBuildsDomainAgentRouteWithoutCallingIntentAgain() {
        AmbiguousRouteSelectionResolver.Candidate candidate =
                resolver.autoSelect(payload(List.of(
                        candidate("intent-a", "技能A", "skill-a", 0.86))))
                        .orElseThrow();

        var result = resolver.routeSignal(candidate, "user-confirmed");

        assertThat(result.route().selectedAgentCode()).isEqualTo("skill-a");
        assertThat(result.route().invocationSkillId()).isEqualTo("skill-a");
        assertThat(result.route().routeSource()).isEqualTo("user-confirmed");
        assertThat(result.intentDecision().intentCode()).isEqualTo("intent-a");
        assertThat(result.intentDecision().intentName()).isEqualTo("技能A");
    }

    @Test
    void selectedDomainExpertCandidateBuildsRelayProfileWithoutCallingIntentAgain() {
        AmbiguousRouteSelectionResolver.Candidate candidate =
                resolver.autoSelect(payload(List.of(
                        candidate("intent-expert", "领域专家", "RE_system-awareness", 0.91))))
                        .orElseThrow();

        var result = resolver.routeSignal(candidate, "user-confirmed");

        assertThat(result.route().type()).isEqualTo(RouteType.AGENT_RUNTIME);
        assertThat(result.route().runtimeProfile()).isEqualTo(RuntimeProfile.DOMAIN_EXPERT);
        assertThat(result.route().runtimeRoleName()).isEqualTo("system-awareness");
        assertThat(result.route().relayOutputMode()).isEqualTo(RelayOutputMode.FULL_STREAM);
        assertThat(result.route().invocationSkillId()).isEqualTo("RE_system-awareness");
        assertThat(result.intentDecision().intentCode()).isEqualTo("intent-expert");
    }

    @Test
    void selectedSensitiveInformationCandidateBuildsDelegateRouteWithoutCallingIntentAgain() {
        AmbiguousRouteSelectionResolver sensitiveResolver =
                new AmbiguousRouteSelectionResolver("RE_", "sensitive_information");
        AmbiguousRouteSelectionResolver.Candidate candidate =
                sensitiveResolver.autoSelect(payload(List.of(
                        candidate("intent-sensitive", "敏感信息", "sensitive_information", 0.92))))
                        .orElseThrow();

        var result = sensitiveResolver.routeSignal(candidate, "user-confirmed");

        assertThat(result.route().type()).isEqualTo(RouteType.AGENT_RUNTIME);
        assertThat(result.route().runtimeProfile()).isEqualTo(RuntimeProfile.DELEGATE);
        assertThat(result.route().runtimeRoleName()).isNull();
        assertThat(result.route().relayOutputMode()).isEqualTo(RelayOutputMode.ANSWER_STREAM_ONLY);
        assertThat(result.route().invocationSkillId()).isEqualTo("sensitive_information");
        assertThat(result.intentDecision().intentCode()).isEqualTo("intent-sensitive");
        assertThat(result.intentDecision().candidateDomainAgentId()).isEqualTo("sensitive_information");
    }

    private Map<String, Object> payload(List<Map<String, Object>> candidates) {
        return Map.of(
                "clarificationType", AmbiguousRouteSupport.CLARIFICATION_TYPE,
                "candidateIntents", candidates);
    }

    private Map<String, Object> candidate(
            String intentId,
            String intentName,
            String skillId,
            Object confidence) {
        java.util.LinkedHashMap<String, Object> candidate = new java.util.LinkedHashMap<>();
        candidate.put("intentId", intentId);
        candidate.put("intentName", intentName);
        candidate.put("confidence", confidence);
        if (skillId != null) {
            candidate.put("skillId", skillId);
        }
        candidate.put("resourceInstruction", Map.of("resourceId", "resource-" + intentId));
        return Map.copyOf(candidate);
    }

    private ChatInteractionRequest interaction(Map<String, Object> payload) {
        Instant now = Instant.parse("2026-07-30T10:00:00Z");
        return new ChatInteractionRequest(
                "interaction-1",
                "tenant-1",
                "user-1",
                "session-1",
                "run-a",
                null,
                "message-user",
                "message-assistant",
                "intent-agent",
                null,
                null,
                null,
                ChatInteractionType.INTENT_CLARIFICATION,
                ChatInteractionStatus.WAITING,
                payload,
                Map.of(),
                now.plusSeconds(3600),
                null,
                null,
                now,
                now);
    }
}
