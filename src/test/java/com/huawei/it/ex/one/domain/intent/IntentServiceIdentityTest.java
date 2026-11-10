/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.intent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class IntentServiceIdentityTest {
    @Test
    void retainsOnlyTheReturnedIdAndChecksBindingNameAndTarget() {
        IntentDecision decision = decision(true, Map.of("routeAction", "ROUTE_SINGLE", "intentId", "EX_raw-id"));
        Map<String, Object> binding = Map.of(IntentServiceIdentity.BINDING_KEY,
                IntentServiceIdentity.bindingSnapshot(decision));
        assertThat(IntentServiceIdentity.id(decision)).isEqualTo("EX_raw-id");
        assertThat(IntentServiceIdentity.bindingId(binding, "skill-a", "Knowledge")).isEqualTo("EX_raw-id");
        assertThat(IntentServiceIdentity.bindingId(binding, "skill-b", "Knowledge")).isNull();
        assertThat(IntentServiceIdentity.bindingId(binding, "skill-a", "Other")).isNull();
        assertThat(IntentServiceIdentity.bindingId(Map.of("intentCode", "EX_raw-id"), "skill-a", "Knowledge"))
                .isNull();
    }

    @Test
    void missingMalformedAndTechnicalResultIdsAreNotInferredFromCodes() {
        for (Object value : List.of("", " ", 42, List.of("id"), Map.of("id", "a"))) {
            assertThat(IntentServiceIdentity.id(decision(true,
                    Map.of("routeAction", "ROUTE_SINGLE", "intentId", value)))).isNull();
        }
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("routeAction", "ROUTE_SINGLE");
        slots.put("intentId", null);
        assertThat(IntentServiceIdentity.idFromSlots(slots)).isNull();
        assertThat(IntentServiceIdentity.id(decision(true, Map.of("routeAction", "ROUTE_SINGLE")))).isNull();
        assertThat(IntentServiceIdentity.id(decision(false,
                Map.of("routeAction", "ROUTE_SINGLE", "intentId", "error-item-id")))).isNull();
        assertThat(IntentServiceIdentity.bindingSnapshot(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROUTE_MULTI", "NO_MATCH", "CLARIFY", "", " ROUTE_SINGLE "})
    void excludesNonSingleResults(String action) {
        assertThat(IntentServiceIdentity.id(decision(true, Map.of("routeAction", action, "intentId", "id"))))
                .isNull();
    }

    private IntentDecision decision(boolean simple, Map<String, Object> slots) {
        return new IntentDecision("internal-code", "Knowledge", TaskComplexity.SIMPLE,
                1.0, simple, "skill-a", slots, List.of(), Map.of());
    }
}
