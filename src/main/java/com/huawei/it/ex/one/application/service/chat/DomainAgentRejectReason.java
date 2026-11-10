/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.intent.IntentServiceIdentity;

import java.util.LinkedHashMap;
import java.util.Map;

/** 在拒答重路由及后续意图澄清之间传递的当前拒答原因。 */
record DomainAgentRejectReason(String lastIntent, String domainRejectMessage, String lastIntentId) {
    static final String METADATA_KEY = "lastIntentRejectReason";
    private static final String UNKNOWN_INTENT = "未知意图";

    DomainAgentRejectReason {
        lastIntent = textOrDefault(lastIntent, UNKNOWN_INTENT);
        domainRejectMessage = domainRejectMessage == null ? "" : domainRejectMessage;
        lastIntentId = IntentServiceIdentity.text(lastIntentId);
    }

    DomainAgentRejectReason(String lastIntent, String domainRejectMessage) {
        this(lastIntent, domainRejectMessage, null);
    }

    static DomainAgentRejectReason from(String intentName, DomainAgentRefusal refusal) {
        return from(intentName, null, refusal);
    }

    static DomainAgentRejectReason from(String intentName, String intentId, DomainAgentRefusal refusal) {
        return new DomainAgentRejectReason(
                intentName,
                refusal == null ? null : refusal.message(), intentId);
    }

    static DomainAgentRejectReason fromRerouteState(Map<?, ?> state) {
        Map<?, ?> reason = state != null && state.get(METADATA_KEY) instanceof Map<?, ?> value
                ? value
                : Map.of();
        return new DomainAgentRejectReason(
                firstText(reason.get("lastIntent")),
                firstText(reason.get("domainRejectMessage"),
                        state == null ? null : state.get("refusalReason")),
                IntentServiceIdentity.text(reason.get("lastIntentId")));
    }

    Map<String, Object> toMap() {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("lastIntent", lastIntent);
        reason.put("domainRejectMessage", domainRejectMessage);
        if (lastIntentId != null) {
            reason.put("lastIntentId", lastIntentId);
        }
        return Map.copyOf(reason);
    }

    private static String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
