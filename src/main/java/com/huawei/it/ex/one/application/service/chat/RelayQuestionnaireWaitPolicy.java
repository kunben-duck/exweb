package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.ChatInteractionProperties;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在 Relay 问卷卡片落库前补齐由前端执行的自动动作截止时间。
 */
final class RelayQuestionnaireWaitPolicy {
    static final String AUTO_ACTION_TYPE = "IGNORE_QUESTIONNAIRE";

    private final Duration timeout;

    RelayQuestionnaireWaitPolicy(ChatInteractionProperties interactionProperties,
                                 Duration configuredTimeout) {
        this.timeout = normalize(configuredTimeout);
        validateInteractionExpiry(interactionProperties);
    }

    ChatEvent decorate(ChatEvent event) {
        return decorate(event, Instant.now());
    }

    ChatEvent decorate(ChatEvent event, Instant now) {
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
        if (timeout.isZero()) {
            return;
        }
        Duration expiry = properties == null ? null : properties.getDefaultExpireDuration();
        if (expiry != null && !expiry.isZero() && !expiry.isNegative()
                && expiry.compareTo(timeout) <= 0) {
            throw new IllegalStateException(
                    "financeex.chat-interaction.default-expire-duration 必须大于 "
                            + "financeex.relay.questionnaire-wait-timeout");
        }
    }
}
