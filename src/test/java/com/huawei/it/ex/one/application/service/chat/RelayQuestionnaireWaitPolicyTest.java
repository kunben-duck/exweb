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
