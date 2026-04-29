package com.huawei.finance.front.one.domain.agent;

public enum AgentBindingStatus {
    ACTIVE,
    REQUIRES_USER_INPUT,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED;

    public boolean routable() {
        return this == ACTIVE || this == REQUIRES_USER_INPUT;
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }

    public static AgentBindingStatus fromTaskStatus(String value, AgentBindingStatus fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return AgentBindingStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
