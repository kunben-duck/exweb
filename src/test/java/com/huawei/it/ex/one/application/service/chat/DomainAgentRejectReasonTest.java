/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

class DomainAgentRejectReasonTest {
    @Test
    void restoresServiceIdFromPersistedRerouteStateWithoutChangingLegacyFields() {
        var reason = new DomainAgentRejectReason("Knowledge", "rejected", "EX_original-id");
        Map<String, Object> state = Map.of("lastIntentRejectReason", reason.toMap());
        assertThat(DomainAgentRejectReason.fromRerouteState(state)).isEqualTo(reason);
        assertThat(reason.toMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "lastIntent", "Knowledge", "domainRejectMessage", "rejected", "lastIntentId", "EX_original-id"));
    }

    @Test
    void omitsUnknownIdsAndDoesNotInferFromLegacyCodesOrTargets() {
        assertThat(new DomainAgentRejectReason("Knowledge", "rejected").toMap())
                .doesNotContainKey("lastIntentId");
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("lastIntent", "Knowledge");
        reason.put("intentCode", "fake-id");
        reason.put("lastIntentId", null);
        assertThat(DomainAgentRejectReason.fromRerouteState(Map.of(
                "currentTargetId", "skill-a", "lastIntentRejectReason", reason)).toMap())
                .doesNotContainKey("lastIntentId");
        reason.put("lastIntentId", 123);
        assertThat(DomainAgentRejectReason.fromRerouteState(Map.of("lastIntentRejectReason", reason)).lastIntentId())
                .isNull();
    }
}
