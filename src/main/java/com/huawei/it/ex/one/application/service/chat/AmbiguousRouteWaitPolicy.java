package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.ChatInteractionProperties;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 AMBIGUOUS_ROUTE 等待卡片落库前补齐动作和前端代选截止时间。
 */
final class AmbiguousRouteWaitPolicy {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final AmbiguousRouteSelectionResolver selectionResolver;
    private final Duration timeout;

    AmbiguousRouteWaitPolicy(AmbiguousRouteSelectionResolver selectionResolver,
                             ChatInteractionProperties interactionProperties,
                             Duration configuredTimeout) {
        this.selectionResolver = selectionResolver;
        this.timeout = normalize(configuredTimeout);
        validateInteractionExpiry(interactionProperties);
    }

    ChatEvent decorate(ChatEvent event) {
        return decorate(event, Instant.now());
    }

    ChatEvent decorate(ChatEvent event, Instant now) {
        if (event == null || !"runtime.card".equals(event.type())
                || event.payload() == null
                || !"intent-clarification-request".equals(
                String.valueOf(event.payload().get("sourceType")))
                || !AmbiguousRouteSupport.isAmbiguous(event.payload())) {
            return event;
        }
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        payload.put("clarificationType", AmbiguousRouteSupport.CLARIFICATION_TYPE);
        List<Map<String, Object>> actions = new ArrayList<>();
        boolean autoSelectable = selectionResolver.autoSelect(payload).isPresent();
        if (autoSelectable) {
            actions.add(Map.of(
                    "type", AmbiguousRouteSupport.ACTION_AUTO_SELECT,
                    "displayName", "代为选择"));
        }
        actions.add(Map.of(
                "type", AmbiguousRouteSupport.ACTION_OTHER,
                "displayName", "其他"));
        payload.put("actions", List.copyOf(actions));
        if (autoSelectable) {
            payload.put("autoSelectAt", now.plus(timeout).toString());
            payload.put("autoSelectTimeoutMs", timeout.toMillis());
        }
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
