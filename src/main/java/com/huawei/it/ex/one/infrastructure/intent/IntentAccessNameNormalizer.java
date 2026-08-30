/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.intent;

/** Shared accessName prefix normalization for Intent wire responses. */
final class IntentAccessNameNormalizer {
    private IntentAccessNameNormalizer() {
    }

    static String normalize(String accessName, String configuredPrefix) {
        if (accessName == null || accessName.isBlank()) {
            return null;
        }
        String normalized = accessName.trim();
        String prefix = configuredPrefix == null ? "" : configuredPrefix.trim();
        if (!prefix.isEmpty() && normalized.startsWith(prefix)) {
            normalized = normalized.substring(prefix.length()).trim();
        }
        return normalized.isEmpty() ? null : normalized;
    }
}
