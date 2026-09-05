/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.chat.IntentExpertScope;

import org.junit.jupiter.api.Test;

import java.util.Map;

class IntentExpertContextTest {
    @Test
    void storesScopeWithoutReplacingOtherSessionMetadata() {
        IntentExpertScope scope = new IntentExpertScope("expert-a", "专家A", "expert_a_entry");

        String metadataJson = IntentExpertContext.replaceSessionMetadata(
                "{\"titleSource\":\"AUTO\"}", scope);

        assertThat(IntentExpertContext.fromSessionMetadata(metadataJson)).contains(scope);
        assertThat(metadataJson).contains("\"titleSource\":\"AUTO\"");
        assertThat(IntentExpertContext.replaceSessionMetadata(metadataJson, null))
                .isEqualTo("{\"titleSource\":\"AUTO\"}");
    }

    @Test
    void bindingAffinityUsesExpertIdentityAndAllowsDisplayNameRefresh() {
        IntentExpertScope original = new IntentExpertScope("expert-a", "旧名称", "expert_entry");
        IntentExpertScope renamed = new IntentExpertScope("expert-a", "新名称", "expert_entry");
        IntentExpertScope other = new IntentExpertScope("expert-b", "专家B", "expert_entry");
        Map<String, Object> metadata = IntentExpertContext.withScope(Map.of("routeSource", "intent-expert"), original);

        assertThat(IntentExpertContext.matches(metadata, renamed)).isTrue();
        assertThat(IntentExpertContext.matches(metadata, other)).isFalse();
        assertThat(IntentExpertContext.matches(metadata, null)).isFalse();
        assertThat(IntentExpertContext.matches(Map.of(), null)).isTrue();
    }

    @Test
    void selectionPayloadContainsOnlyTrustedParentExpertSummary() {
        IntentExpertScope scope = new IntentExpertScope("expert-a", "专家A", "expert_a_entry");

        Map<String, Object> payload = IntentExpertSelectionPayload.create(scope);

        assertThat(payload)
                .containsEntry("source", "chatservice")
                .containsEntry("sourceType", "selectedIntentExpert")
                .containsEntry("metadataType", "selected_intent_expert")
                .containsEntry("targetType", "INTENT_EXPERT")
                .containsEntry("targetId", "expert-a");
        assertThat(payload.get("selectedExpert")).isEqualTo(Map.of(
                "expertId", "expert-a",
                "expertName", "专家A",
                "intentAccessName", "expert_a_entry"));
    }
}
