package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.ChatInteractionProperties;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在拒答路由切换确认卡片落库前补齐由前端执行的自动同意截止时间。
 */
final class RouteSwitchConfirmationWaitPolicy {
    static final String AUTO_ACTION_TYPE = "APPROVE_ROUTE_SWITCH";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Duration timeout;

    RouteSwitchConfirmationWaitPolicy(ChatInteractionProperties interactionProperties,
                                      Duration configuredTimeout) {
        this.timeout = normalize(configuredTimeout);
        validateInteractionExpiry(interactionProperties);
    }

    ChatEvent decorate(ChatEvent event) {
        return decorate(event, Instant.now());
    }

    ChatEvent decorate(ChatEvent event, Instant now) {
        if (!routeSwitchConfirmationRequest(event)) {
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

    private boolean routeSwitchConfirmationRequest(ChatEvent event) {
        return event != null
                && "runtime.card".equals(event.type())
                && event.payload() != null
                && "chatservice".equals(event.payload().get("source"))
                && "route-switch-confirmation-request".equals(event.payload().get("sourceType"))
                && ChatInteractionType.ROUTE_SWITCH_CONFIRMATION.name().equals(
                event.payload().get("interactionType"));
    }

    private Duration normalize(Duration configured) {
        return configured == null || configured.isZero() || configured.isNegative()
                ? DEFAULT_TIMEOUT
                : configured;
    }

    private void validateInteractionExpiry(ChatInteractionProperties properties) {
        Duration expiry = properties == null ? null : properties.getDefaultExpireDuration();
        if (expiry != null && !expiry.isZero() && !expiry.isNegative()
                && expiry.compareTo(timeout) <= 0) {
            throw new IllegalStateException(
                    "financeex.chat-interaction.default-expire-duration 必须大于 "
                            + "financeex.intent.ambiguous-route-wait-timeout");
        }
    }
}
