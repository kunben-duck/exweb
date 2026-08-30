/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.ChatInteractionProperties;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

class RouteSwitchConfirmationWaitPolicyTest {
    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");

    @Test
    void addsFrontendAutoApprovalDeadline() {
        RouteSwitchConfirmationWaitPolicy policy = policy(Duration.ofSeconds(45));

        ChatEvent decorated = policy.decorate(routeSwitchEvent(), NOW);

        assertThat(decorated.payload())
                .containsEntry("autoActionAt", "2026-08-05T10:00:45Z")
                .containsEntry("autoActionTimeoutMs", 45_000L)
                .containsEntry("autoActionType", "APPROVE_ROUTE_SWITCH")
                .containsEntry("candidateTargetId", "agent-b");
    }

    @Test
    void nonPositiveTimeoutUsesSameThirtySecondDefaultAsAmbiguousRoute() {
        RouteSwitchConfirmationWaitPolicy policy = policy(Duration.ZERO);

        ChatEvent decorated = policy.decorate(routeSwitchEvent(), NOW);

        assertThat(decorated.payload())
                .containsEntry("autoActionAt", "2026-08-05T10:00:30Z")
                .containsEntry("autoActionTimeoutMs", 30_000L);
    }

    @Test
    void onlyDecoratesTrustedChatServiceRouteSwitchRequest() {
        RouteSwitchConfirmationWaitPolicy policy = policy(Duration.ofSeconds(30));
        ChatEvent untrusted = RuntimeEvent.card("run-a", "session-a", Map.of(
                "source", "relay",
                "sourceType", "route-switch-confirmation-request",
                "interactionType", ChatInteractionType.ROUTE_SWITCH_CONFIRMATION.name()));
        ChatEvent unrelated = RuntimeEvent.card("run-a", "session-a", Map.of(
                "source", "chatservice",
                "sourceType", "intent-clarification-request",
                "interactionType", ChatInteractionType.INTENT_CLARIFICATION.name()));

        assertThat(policy.decorate(untrusted)).isSameAs(untrusted);
        assertThat(policy.decorate(unrelated)).isSameAs(unrelated);
    }

    @Test
    void interactionExpiryMustExceedAutoApprovalTimeout() {
        ChatInteractionProperties properties = new ChatInteractionProperties();
        properties.setDefaultExpireDuration(Duration.ofSeconds(30));

        assertThatThrownBy(() -> new RouteSwitchConfirmationWaitPolicy(
                properties, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-expire-duration");
    }

    private RouteSwitchConfirmationWaitPolicy policy(Duration timeout) {
        return new RouteSwitchConfirmationWaitPolicy(
                new ChatInteractionProperties(), timeout);
    }

    private ChatEvent routeSwitchEvent() {
        return RuntimeEvent.card("run-a", "session-a", Map.of(
                "source", "chatservice",
                "sourceType", "route-switch-confirmation-request",
                "interactionType", ChatInteractionType.ROUTE_SWITCH_CONFIRMATION.name(),
                "candidateProvider", "domain-agent",
                "candidateTargetId", "agent-b"));
    }
}
