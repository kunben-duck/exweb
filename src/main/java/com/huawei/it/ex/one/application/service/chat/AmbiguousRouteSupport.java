/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;

import java.util.Map;

/**
 * AMBIGUOUS_ROUTE 协议字段读取工具。
 */
final class AmbiguousRouteSupport {
    static final String CLARIFICATION_TYPE = "AMBIGUOUS_ROUTE";
    static final String ACTION_AUTO_SELECT = "AUTO_SELECT";
    static final String ACTION_OTHER = "OTHER";
    static final String SELECTION_SOURCE_USER = "USER";
    static final String SELECTION_SOURCE_DELEGATED = "DELEGATED";

    private AmbiguousRouteSupport() {
    }

    static boolean isAmbiguous(ChatInteractionRequest interaction) {
        return interaction != null && isAmbiguous(interaction.requestPayload());
    }

    static boolean isAmbiguous(Map<String, Object> payload) {
        return CLARIFICATION_TYPE.equalsIgnoreCase(clarificationType(payload));
    }

    static String clarificationType(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        String direct = firstText(payload.get("clarificationType"), payload.get("type"));
        if (direct != null) {
            return direct;
        }
        Object clarification = payload.get("clarification");
        return clarification instanceof Map<?, ?> map
                ? firstText(map.get("type"), map.get("clarificationType"))
                : null;
    }

    static String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }
}
