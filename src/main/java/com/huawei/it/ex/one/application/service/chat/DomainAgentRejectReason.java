package com.huawei.it.ex.one.application.service.chat;

import java.util.Map;

/** 在拒答重路由及后续意图澄清之间传递的当前拒答原因。 */
record DomainAgentRejectReason(String lastIntent, String domainRejectMessage) {
    static final String METADATA_KEY = "lastIntentRejectReason";
    private static final String UNKNOWN_INTENT = "未知意图";

    DomainAgentRejectReason {
        lastIntent = textOrDefault(lastIntent, UNKNOWN_INTENT);
        domainRejectMessage = domainRejectMessage == null ? "" : domainRejectMessage;
    }

    static DomainAgentRejectReason from(String intentName, DomainAgentRefusal refusal) {
        return new DomainAgentRejectReason(
                intentName,
                refusal == null ? null : refusal.message());
    }

    static DomainAgentRejectReason fromRerouteState(Map<?, ?> state) {
        Map<?, ?> reason = state != null && state.get(METADATA_KEY) instanceof Map<?, ?> value
                ? value
                : Map.of();
        return new DomainAgentRejectReason(
                firstText(reason.get("lastIntent")),
                firstText(reason.get("domainRejectMessage"),
                        state == null ? null : state.get("refusalReason")));
    }

    Map<String, Object> toMap() {
        return Map.of(
                "lastIntent", lastIntent,
                "domainRejectMessage", domainRejectMessage);
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
