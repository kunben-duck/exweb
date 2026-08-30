/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.AgentModeSelection;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class AgentModeBindingContextTest {
    @Test
    void roundTripsMultipleUnknownModeSchemesWithoutEnums() {
        AgentModeProfile profile = profile(
                new AgentModeSelection("thinking", "deep", "深度思考"),
                new AgentModeSelection("execution", "long_task", "长任务执行"),
                new AgentModeSelection("thinking_level", "3", null));

        Map<String, Object> metadata = AgentModeBindingContext.apply(Map.of("routeSource", "intent"), profile);

        assertThat(AgentModeBindingContext.fromMetadata(metadata)).isEqualTo(profile);
        assertThat(metadata).containsEntry("routeSource", "intent");
    }

    @Test
    void nullUpdateLeavesExistingRecordAndExplicitEmptySnapshotClears() {
        AgentModeProfile profile = profile(new AgentModeSelection("thinking", "fast", "快速"));
        Map<String, Object> existing = AgentModeBindingContext.apply(Map.of("routeSource", "intent"), profile);

        Map<String, Object> unchanged = AgentModeBindingContext.apply(existing, null);
        Map<String, Object> cleared = AgentModeBindingContext.apply(existing, AgentModeProfile.empty());

        assertThat(AgentModeBindingContext.fromMetadata(unchanged)).isEqualTo(profile);
        assertThat(cleared).containsEntry("routeSource", "intent").doesNotContainKey("agentMode");
    }

    @Test
    void selectedDomainAgentPayloadDoesNotExposeBindingModeSnapshot() {
        AgentModeProfile profile = profile(
                new AgentModeSelection("thinking", "deep", "深度思考"),
                new AgentModeSelection("execution", "long_task", null));
        Map<String, Object> bindingMetadata = AgentModeBindingContext.apply(
                Map.of("intentName", "资金管理"), profile);

        Map<String, Object> payload = DomainAgentSelectionPayload.create(
                "fund-agent", "front-selected", "session1", null, bindingMetadata);

        assertThat(payload).doesNotContainKey("agentMode");
    }

    @Test
    void rejectsDuplicateSchemeAndMoreThanSixteenSelections() {
        assertThatThrownBy(() -> profile(
                new AgentModeSelection("thinking", "fast", null),
                new AgentModeSelection("thinking", "deep", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复 scheme");

        List<AgentModeSelection> selections = java.util.stream.IntStream.range(0, 17)
                .mapToObj(index -> new AgentModeSelection("scheme_" + index, String.valueOf(index), null))
                .toList();
        assertThatThrownBy(() -> new AgentModeProfile(selections))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多允许 16 项");
    }

    private AgentModeProfile profile(AgentModeSelection... selections) {
        return new AgentModeProfile(List.of(selections));
    }
}
