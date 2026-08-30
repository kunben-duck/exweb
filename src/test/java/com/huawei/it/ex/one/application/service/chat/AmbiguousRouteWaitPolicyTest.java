/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.ChatInteractionProperties;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

class AmbiguousRouteWaitPolicyTest {
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-30T10:00:00Z");

    @Test
    void decoratesRoutableAmbiguousCardWithActionsAndDeadline() {
        AmbiguousRouteWaitPolicy policy = policy(Duration.ofSeconds(30));
        ChatEvent event = card(List.of(candidate("skill-a", 0.8)));

        ChatEvent decorated = policy.decorate(event, CREATED_AT);

        assertThat(decorated.payload())
                .containsEntry("clarificationType", "AMBIGUOUS_ROUTE")
                .containsEntry("autoSelectAt", "2026-07-30T10:00:30Z")
                .containsEntry("autoSelectTimeoutMs", 30_000L);
        assertThat(decorated.payload().get("actions")).isEqualTo(List.of(
                Map.of("type", "AUTO_SELECT", "displayName", "代为选择"),
                Map.of("type", "OTHER", "displayName", "其他")));
    }

    @Test
    void invalidCandidatesOnlyExposeOtherActionAndNoDeadline() {
        AmbiguousRouteWaitPolicy policy = policy(Duration.ofSeconds(30));
        ChatEvent event = card(List.of(candidate(null, 0.9)));

        ChatEvent decorated = policy.decorate(event);

        assertThat(decorated.payload().get("actions")).isEqualTo(List.of(
                Map.of("type", "OTHER", "displayName", "其他")));
        assertThat(decorated.payload())
                .doesNotContainKeys("autoSelectAt", "autoSelectTimeoutMs");
    }

    @Test
    void ordinaryClarificationCardRemainsUnchanged() {
        AmbiguousRouteWaitPolicy policy = policy(Duration.ofSeconds(30));
        RuntimeEvent event = new RuntimeEvent(
                "run-a",
                "session-1",
                0L,
                CREATED_AT,
                "runtime.card",
                Map.of(
                        "sourceType", "intent-clarification-request",
                        "clarificationType", "UNCLEAR_REFERENCE"));

        assertThat(policy.decorate(event)).isSameAs(event);
    }

    @Test
    void interactionExpiryMustBeLongerThanAutoSelectionTimeout() {
        ChatInteractionProperties properties = new ChatInteractionProperties();
        properties.setDefaultExpireDuration(Duration.ofSeconds(30));

        assertThatThrownBy(() -> new AmbiguousRouteWaitPolicy(
                new AmbiguousRouteSelectionResolver(),
                properties,
                Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-expire-duration");
    }

    private AmbiguousRouteWaitPolicy policy(Duration timeout) {
        return new AmbiguousRouteWaitPolicy(
                new AmbiguousRouteSelectionResolver(),
                new ChatInteractionProperties(),
                timeout);
    }

    private ChatEvent card(List<Map<String, Object>> candidates) {
        return new RuntimeEvent(
                "run-a",
                "session-1",
                0L,
                CREATED_AT,
                "runtime.card",
                Map.of(
                        "source", "intent-agent",
                        "sourceType", "intent-clarification-request",
                        "clarificationType", "AMBIGUOUS_ROUTE",
                        "candidateIntents", candidates));
    }

    private Map<String, Object> candidate(String skillId, double confidence) {
        if (skillId == null) {
            return Map.of(
                    "intentId", "intent-a",
                    "intentName", "技能A",
                    "confidence", confidence);
        }
        return Map.of(
                "intentId", "intent-a",
                "intentName", "技能A",
                "skillId", skillId,
                "confidence", confidence);
    }
}
