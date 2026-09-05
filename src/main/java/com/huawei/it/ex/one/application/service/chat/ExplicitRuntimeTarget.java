/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

/** 前端显式选择的可信 Runtime 目标。 */
record ExplicitRuntimeTarget(Type type, String targetId) {
    ExplicitRuntimeTarget {
        if (type == null) {
            throw new IllegalArgumentException("显式 Runtime targetType 不能为空");
        }
        targetId = normalize(targetId);
        if (targetId == null) {
            throw new IllegalArgumentException("显式 Runtime targetId 不能为空");
        }
    }

    boolean domainAgent() {
        return type == Type.DOMAIN_AGENT;
    }

    boolean domainExpert() {
        return type == Type.DOMAIN_EXPERT;
    }

    boolean intentExpert() {
        return type == Type.INTENT_EXPERT;
    }

    enum Type {
        DOMAIN_AGENT,
        DOMAIN_EXPERT,
        INTENT_EXPERT
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
