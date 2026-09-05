/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.agent;

import com.huawei.it.ex.one.domain.chat.IntentExpertScope;

import java.util.LinkedHashMap;
import java.util.Map;

/** 构造聚合意图专家首次选择或切换事件。 */
public final class IntentExpertSelectionPayload {
    private IntentExpertSelectionPayload() {
    }

    public static Map<String, Object> create(IntentExpertScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("Intent expert scope must not be null");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "selectedIntentExpert");
        payload.put("metadataType", "selected_intent_expert");
        payload.put("targetType", "INTENT_EXPERT");
        payload.put("targetId", scope.expertId());
        payload.put("selectedExpert", IntentExpertContext.sourceExpert(scope));
        return Map.copyOf(payload);
    }
}
