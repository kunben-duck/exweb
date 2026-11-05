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
import java.util.Map;

class RelayQuestionnaireWaitPolicyTest {
    @Test
    void domainAgentTimeoutIsIndependentOfRelayAndDefaultsToDisabled() {
        ChatEvent event = RuntimeEvent.card("r", "s", Map.of("source", "domain-agent",
                "sourceType", "approval-request", "operation_type", "questionnaire", "approval_id", "q"));
        assertThat(new RelayQuestionnaireWaitPolicy(interactionProperties(Duration.ofHours(24)),
                Duration.ofSeconds(30)).decorate(event)).isSameAs(event);
        RelayQuestionnaireWaitPolicy policy = new RelayQuestionnaireWaitPolicy(
                interactionProperties(Duration.ofHours(24)), Duration.ZERO, Duration.ofSeconds(12));
        assertThat(policy.decorate(event, Instant.parse("2026-08-01T10:00:00Z")).payload())
                .containsEntry("autoActionAt", "2026-08-01T10:00:12Z")
                .containsEntry("autoActionType", "IGNORE_QUESTIONNAIRE")
                .containsEntry("autoActionTimeoutMs", 12_000L);
        ChatEvent relay = questionnaireEvent();
        assertThat(policy.decorate(relay)).isSameAs(relay);
        assertThatThrownBy(() -> new RelayQuestionnaireWaitPolicy(
                interactionProperties(Duration.ofSeconds(12)), Duration.ZERO, Duration.ofSeconds(12)))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test
    void eachProviderUsesItsOwnPositiveTimeoutWithoutChangingTheOriginalPayload() {
        RelayQuestionnaireWaitPolicy policy = new RelayQuestionnaireWaitPolicy(
                interactionProperties(Duration.ofHours(24)), Duration.ofSeconds(30), Duration.ofSeconds(12));
        Instant now = Instant.parse("2026-08-01T10:00:00Z");
        ChatEvent relay = questionnaireEvent();
        ChatEvent domainAgent = RuntimeEvent.card("r", "s", Map.of("source", "domain-agent",
                "sourceType", "approval-request", "operation_type", "questionnaire", "approval_id", "q"));

        assertThat(policy.decorate(relay, now).payload()).hasSize(relay.payload().size() + 3)
                .containsAllEntriesOf(relay.payload())
                .containsEntry("autoActionAt", "2026-08-01T10:00:30Z")
                .containsEntry("autoActionTimeoutMs", 30_000L)
                .containsEntry("autoActionType", "IGNORE_QUESTIONNAIRE");
        assertThat(policy.decorate(domainAgent, now).payload()).hasSize(domainAgent.payload().size() + 3)
                .containsAllEntriesOf(domainAgent.payload())
                .containsEntry("autoActionAt", "2026-08-01T10:00:12Z")
                .containsEntry("autoActionTimeoutMs", 12_000L)
                .containsEntry("autoActionType", "IGNORE_QUESTIONNAIRE");
        assertThat(relay.payload()).doesNotContainKeys("autoActionAt", "autoActionTimeoutMs", "autoActionType");
        assertThat(domainAgent.payload()).doesNotContainKeys("autoActionAt", "autoActionTimeoutMs", "autoActionType");
    }

    @Test
    void nullAndNonCardEventsStayUnchangedWhenBothTimeoutsAreEnabled() {
        RelayQuestionnaireWaitPolicy policy = new RelayQuestionnaireWaitPolicy(
                interactionProperties(Duration.ofHours(24)), Duration.ofSeconds(30), Duration.ofSeconds(12));
        Instant now = Instant.parse("2026-08-01T10:00:00Z");
        ChatEvent metadata = RuntimeEvent.metadata("r", "s", questionnaireEvent().payload());

        assertThat(policy.decorate(null, now)).isNull();
        assertThat(policy.decorate(metadata, now)).isSameAs(metadata);
    }

    @Test
    void zeroTimeoutLeavesQuestionnaireEventUnchanged() {
        ChatEvent event = questionnaireEvent();
        RelayQuestionnaireWaitPolicy policy = new RelayQuestionnaireWaitPolicy(
                interactionProperties(Duration.ofHours(24)), Duration.ZERO);

        ChatEvent decorated = policy.decorate(event, Instant.parse("2026-08-01T10:00:00Z"));

        assertThat(decorated).isSameAs(event);
    }

    @Test
    void configuredTimeoutAddsFrontendAutoActionFacts() {
        ChatEvent event = questionnaireEvent();
        RelayQuestionnaireWaitPolicy policy = new RelayQuestionnaireWaitPolicy(
                interactionProperties(Duration.ofHours(24)), Duration.ofSeconds(30));

        ChatEvent decorated = policy.decorate(event, Instant.parse("2026-08-01T10:00:00Z"));

        assertThat(decorated.payload())
                .containsEntry("autoActionAt", "2026-08-01T10:00:30Z")
                .containsEntry("autoActionTimeoutMs", 30_000L)
                .containsEntry("autoActionType", "IGNORE_QUESTIONNAIRE")
                .containsEntry("approval_id", "approval-1");
        assertThat(decorated.runId()).isEqualTo(event.runId());
        assertThat(decorated.sessionId()).isEqualTo(event.sessionId());
        assertThat(decorated.createdAt()).isEqualTo(event.createdAt());
    }

    @Test
    void unrelatedRuntimeCardIsNotDecorated() {
        ChatEvent event = RuntimeEvent.card("run1", "session1", Map.of("sourceType", "card-list"));
        RelayQuestionnaireWaitPolicy policy = new RelayQuestionnaireWaitPolicy(
                interactionProperties(Duration.ofHours(24)), Duration.ofSeconds(30));

        assertThat(policy.decorate(event)).isSameAs(event);
    }

    @Test
    void interactionExpiryMustExceedFrontendTimeout() {
        ChatInteractionProperties properties = interactionProperties(Duration.ofSeconds(30));

        assertThatThrownBy(() -> new RelayQuestionnaireWaitPolicy(properties, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-expire-duration 必须大于");
    }

    private ChatEvent questionnaireEvent() {
        return RuntimeEvent.card("run1", "session1", Map.of(
                "source", "relay",
                "sourceType", "approval-request",
                "operation_type", "questionnaire",
                "approval_id", "approval-1"));
    }

    private ChatInteractionProperties interactionProperties(Duration expiry) {
        ChatInteractionProperties properties = new ChatInteractionProperties();
        properties.setDefaultExpireDuration(expiry);
        return properties;
    }
}
