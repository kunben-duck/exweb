/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.ChatInteractionProperties;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentQuestionnaire;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在问卷卡片落库前按 provider 补齐前端自动动作截止时间，两种 Runtime 的开关互不影响。
 */
final class RelayQuestionnaireWaitPolicy {
    static final String AUTO_ACTION_TYPE = "IGNORE_QUESTIONNAIRE";

    private final Duration timeout;
    private final Duration domainAgentTimeout;

    RelayQuestionnaireWaitPolicy(ChatInteractionProperties interactionProperties,
                                 Duration configuredTimeout) {
        this(interactionProperties, configuredTimeout, Duration.ZERO);
    }

    RelayQuestionnaireWaitPolicy(ChatInteractionProperties interactionProperties,
                                 Duration configuredTimeout, Duration domainAgentTimeout) {
        this.timeout = normalize(configuredTimeout);
        this.domainAgentTimeout = normalize(domainAgentTimeout);
        validateInteractionExpiry(interactionProperties);
    }

    ChatEvent decorate(ChatEvent event) {
        return decorate(event, Instant.now());
    }

    ChatEvent decorate(ChatEvent event, Instant now) {
        Duration timeout = DomainAgentQuestionnaire.isRequest(event) ? domainAgentTimeout : this.timeout;
        if (event == null || timeout.isZero() || !"runtime.card".equals(event.type())
                || !RelayQuestionnaireAnswerValidator.isRelayQuestionnaire(event.payload())) {
            return event;
        }
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        payload.put("autoActionAt", now.plus(timeout).toString());
        payload.put("autoActionTimeoutMs", timeout.toMillis());
        payload.put("autoActionType", AUTO_ACTION_TYPE);
        return new RuntimeEvent(
                event.runId(),
                event.sessionId(),
                event.sequence(),
                event.createdAt(),
                event.type(),
                ChatPayloadMaps.immutableCopy(payload));
    }

    private Duration normalize(Duration configured) {
        return configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ZERO
                : configured;
    }

    private void validateInteractionExpiry(ChatInteractionProperties properties) {
        if (timeout.isZero() && domainAgentTimeout.isZero()) {
            return;
        }
        Duration expiry = properties == null ? null : properties.getDefaultExpireDuration();
        if (expiry != null && !expiry.isZero() && !expiry.isNegative()
                && (expiry.compareTo(timeout) <= 0 || expiry.compareTo(domainAgentTimeout) <= 0)) {
            throw new IllegalStateException(
                    "financeex.chat-interaction.default-expire-duration 必须大于 "
                            + "financeex.relay.questionnaire-wait-timeout / "
                            + "financeex.domain-agent.questionnaire-wait-timeout");
        }
    }
}
