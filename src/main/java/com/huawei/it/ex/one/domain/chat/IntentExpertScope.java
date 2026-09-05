/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

/** 会话当前选中的聚合意图专家范围。 */
public record IntentExpertScope(
        String expertId,
        String expertName,
        String intentAccessName
) {
    public IntentExpertScope {
        expertId = requireText(expertId, "expertId");
        expertName = normalize(expertName);
        intentAccessName = requireText(intentAccessName, "intentAccessName");
        if (expertId.length() > 128) {
            throw new IllegalArgumentException("expertId 长度不能超过 128");
        }
        if (expertName != null && expertName.length() > 256) {
            throw new IllegalArgumentException("expertName 长度不能超过 256");
        }
        if (intentAccessName.length() > 128) {
            throw new IllegalArgumentException("intentAccessName 长度不能超过 128");
        }
    }

    public boolean sameIdentity(IntentExpertScope other) {
        return other != null
                && expertId.equals(other.expertId)
                && intentAccessName.equals(other.intentAccessName);
    }

    public String displayName() {
        return expertName == null ? expertId : expertName;
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
