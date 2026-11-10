/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.intent;

import java.util.Map;
import java.util.Objects;

/** Preserves service-returned identity separately from display codes and client selections. */
public final class IntentServiceIdentity {
    public static final String BINDING_KEY = "_intentServiceIdentity";

    private IntentServiceIdentity() {
    }

    public static String id(IntentDecision decision) {
        return decision == null || !decision.simpleTask() ? null : idFromSlots(decision.slots());
    }

    public static String idFromSlots(Map<?, ?> slots) {
        if (slots == null || !"ROUTE_SINGLE".equalsIgnoreCase(text(slots.get("routeAction")))) {
            return null;
        }
        return text(slots.get("intentId"));
    }

    public static Map<String, Object> bindingSnapshot(IntentDecision decision) {
        String id = id(decision);
        if (id == null || text(decision.intentName()) == null || text(decision.candidateDomainAgentId()) == null) {
            return Map.of();
        }
        return Map.of("intentId", id, "intentName", decision.intentName(),
                "targetId", decision.candidateDomainAgentId());
    }

    public static String bindingId(Map<String, Object> metadata, String targetId, String intentName) {
        if (metadata == null || !(metadata.get(BINDING_KEY) instanceof Map<?, ?> identity)
                || !Objects.equals(targetId, identity.get("targetId"))
                || !Objects.equals(intentName, identity.get("intentName"))) {
            return null;
        }
        return text(identity.get("intentId"));
    }

    public static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
